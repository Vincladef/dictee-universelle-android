import {timingSafeEqual} from 'node:crypto';
import type {IncomingMessage} from 'node:http';

/** Independent personal Android dictation endpoint, no DB and no public key exposure. */
export const config = {api: {bodyParser: false}, maxDuration: 60};
const MAX_BYTES = 3_000_000;
const MIME_TYPES = new Set(['audio/mp4', 'audio/wav', 'audio/webm', 'audio/mpeg']);
function fail(res: any, status: number, error: string) { return res.status(status).json({error}); }
function bearerMatch(incoming: string, secret: string): boolean {
    const a = Buffer.from(incoming, 'utf8');
    const b = Buffer.from(secret, 'utf8');
    return a.length > 0 && a.length === b.length && timingSafeEqual(a, b);
}
async function readAudio(req: IncomingMessage): Promise<Buffer> {
    const pieces: Buffer[] = [];
    let bytes = 0;
    for await (const entry of req) {
        const chunk = Buffer.isBuffer(entry) ? entry : Buffer.from(entry);
        bytes += chunk.length;
        if (bytes > MAX_BYTES) throw new Error('too_large');
        pieces.push(chunk);
    }
    return Buffer.concat(pieces, bytes);
}
export default async function handler(req: any, res: any) {
    res.setHeader('Cache-Control', 'no-store, private');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (req.method !== 'POST') return fail(res, 405, 'Méthode non autorisée.');
    const secret = process.env.DICTATION_ANDROID_TOKEN || '';
    const openAi = process.env.OPENAI_API_KEY || '';
    if (secret.length < 32 || !openAi) return fail(res, 503, 'Service de dictée non configuré.');
    const authorization = /^Bearer\s+(.+)$/i.exec(String(req.headers.authorization || ''));
    if (!authorization || !bearerMatch(authorization[1], secret)) return fail(res, 401, 'Accès refusé.');
    const mime = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
    if (!MIME_TYPES.has(mime)) return fail(res, 415, 'Format audio incompatible.');
    const announcedSize = Number(req.headers['content-length'] || '0');
    if (!Number.isFinite(announcedSize) || announcedSize > MAX_BYTES) return fail(res, 413, 'Segment trop volumineux.');
    try {
        const audio = await readAudio(req);
        if (audio.length < 128) return fail(res, 400, 'Enregistrement vide.');
        const valid = mime === 'audio/mp4' ? audio.toString('ascii', 4, 8) === 'ftyp'
            : mime === 'audio/wav' ? audio.toString('ascii', 0, 4) === 'RIFF' && audio.toString('ascii', 8, 12) === 'WAVE'
            : mime === 'audio/webm' ? audio.subarray(0, 4).equals(Buffer.from([0x1a, 0x45, 0xdf, 0xa3]))
            : audio.toString('ascii', 0, 3) === 'ID3' || audio[0] === 0xff;
        if (!valid) return fail(res, 400, 'En-tête audio invalide.');
        const extension = mime === 'audio/mp4' ? 'm4a' : mime === 'audio/webm' ? 'webm' : mime === 'audio/wav' ? 'wav' : 'mp3';
        const form = new FormData();
        form.append('model', 'gpt-transcribe');
        form.append('file', new Blob([new Uint8Array(audio)], {type: mime}), `dictee.${extension}`);
        form.append('prompt', 'Transcrire fidèlement en français si la personne parle français, sans reformulation ni ajout. Conserver la ponctuation et les noms propres.');
        const answer = await fetch('https://api.openai.com/v1/audio/transcriptions', {
            method: 'POST', headers: {Authorization: `Bearer ${openAi}`}, body: form,
            signal: AbortSignal.timeout(49000)
        });
        if (!answer.ok) return fail(res, answer.status === 429 ? 429 : 502,
            'Le fournisseur de transcription a refusé le traitement.');
        const data: any = await answer.json();
        if (typeof data?.text !== 'string' || !data.text.trim()) return fail(res, 502, 'Transcription vide.');
        return res.status(200).json({text: data.text.trim()});
    } catch (e: any) {
        if (e?.message === 'too_large') return fail(res, 413, 'Segment trop volumineux.');
        console.error('Dictation endpoint failed', e instanceof Error ? e.name : 'unknown');
        return fail(res, 502, 'Transcription temporairement indisponible.');
    }
}