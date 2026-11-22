import { get, put } from "@vercel/blob";
import {
  authorized, headers, json, sameOrigin, validId, readMetadata, writeMetadata,
  allMetadata, MAX_AUDIO_BYTES,
} from "../lib/server";

export const config = { api: { bodyParser: false }, maxDuration: 60 };
async function rawBody(req: any, maximum: number): Promise<Buffer> {
  if (Buffer.isBuffer(req.body)) return req.body;
  if (typeof req.body === "string") return Buffer.from(req.body, "binary");
  const pieces: Buffer[] = [];
  let size = 0;
  for await (const part of req) {
    const chunk = Buffer.isBuffer(part) ? part : Buffer.from(part);
    size += chunk.length;
    if (size > maximum) throw new Error("too_large");
    pieces.push(chunk);
  }
  return Buffer.concat(pieces);
}
async function jsonBody(req: any): Promise<any> {
  const raw = await rawBody(req, 200_000);
  if (raw.length > 200_000) throw new Error("too_large");
  return JSON.parse(raw.toString("utf8"));
}
const STATES = new Set(["ready", "done", "failed", "interrupted"]);
function smallString(value: unknown, size: number): string {
  return typeof value === "string" ? value.slice(0, size) : "";
}
export default async function handler(req: any, res: any) {
  headers(res);
  if (!authorized(req)) return json(res, 401, { error: "Connexion requise" });
  if (req.method !== "GET" && req.method !== "POST") return json(res, 405, { error: "Méthode non autorisée" });
  if (req.method === "POST" && !sameOrigin(req)) return json(res, 403, { error: "Origine refusée" });

  const action = String(req.query.action || "");
  const id = String(req.query.id || "");
  try {
    if (action === "list" && req.method === "GET") {
      return json(res, 200, { recordings: await allMetadata() });
    }
    if (action === "audio" && req.method === "GET" && validId(id)) {
      const index = Number(req.query.part);
      const metadata = await readMetadata(id);
      if (!metadata || !Number.isInteger(index) || index < 0 || index >= metadata.segmentsCount) {
        return json(res, 404, { error: "Audio introuvable" });
      }
      const blob = await get("audio/" + id + "/" + index + ".m4a", { access: "private" });
      if (!blob || !blob.stream || blob.statusCode !== 200) return json(res, 404, { error: "Segment absent" });
      res.setHeader("Content-Type", "audio/mp4");
      res.setHeader("Content-Disposition", 'inline; filename="part-' + (index + 1) + '.m4a"');
      const buffer = Buffer.from(await new Response(blob.stream).arrayBuffer());
      return res.status(200).send(buffer);
    }
    if (action === "upload-audio" && req.method === "POST" && validId(id)) {
      const index = Number(req.query.part);
      if (!Number.isInteger(index) || index < 0 || index > 999) return json(res, 400, { error: "Index invalide" });
      if (!String(req.headers["content-type"] || "").startsWith("audio/mp4"))
        return json(res, 415, { error: "M4A attendu" });
      const size = Number(req.headers["content-length"] || 0);
      if (!Number.isFinite(size) || size < 128 || size > MAX_AUDIO_BYTES)
        return json(res, 413, { error: "Segment trop volumineux" });
      const data = await rawBody(req, MAX_AUDIO_BYTES);
      if (data.length !== size || data.length > MAX_AUDIO_BYTES || data.toString("ascii", 4, 8) !== "ftyp")
        return json(res, 400, { error: "Fichier M4A invalide" });
      await put("audio/" + id + "/" + index + ".m4a", data, {
        access: "private", addRandomSuffix: false, allowOverwrite: true, contentType: "audio/mp4",
      });
      return json(res, 200, { ok: true });
    }
    if (action === "upsert" && req.method === "POST") {
      const input = await jsonBody(req);
      if (!validId(input.id)) return json(res, 400, { error: "ID invalide" });
      const count = Number(input.segmentsCount);
      if (!Number.isInteger(count) || count < 1 || count > 1000 ||
        !Number.isSafeInteger(input.createdAt) || !STATES.has(input.state)) {
        return json(res, 400, { error: "Métadonnées invalides" });
      }
      const previous = await readMetadata(input.id);
      const updated = {
        id: input.id, createdAt: input.createdAt,
        durationMs: Math.max(0, Number(input.durationMs) || 0),
        source: smallString(input.source, 40), state: input.state,
        segmentsCount: count, transcript: smallString(input.transcript, 150_000),
        summary: smallString(previous?.summary, 12_000),
        updatedAt: new Date().toISOString(),
      };
      await writeMetadata(input.id, updated);
      return json(res, 200, { ok: true });
    }
    if (action === "update-summary" && req.method === "POST") {
      const input = await jsonBody(req);
      if (!validId(input.id)) return json(res, 400, { error: "ID invalide" });
      const metadata = await readMetadata(input.id);
      if (!metadata) return json(res, 404, { error: "Dictée inconnue" });
      metadata.summary = smallString(input.summary, 12_000);
      metadata.updatedAt = new Date().toISOString();
      await writeMetadata(input.id, metadata);
      return json(res, 200, { summary: metadata.summary });
    }
    if (action === "generate-summary" && req.method === "POST") {
      const input = await jsonBody(req);
      if (!validId(input.id)) return json(res, 400, { error: "ID invalide" });
      const metadata = await readMetadata(input.id);
      if (!metadata || !metadata.transcript) return json(res, 400, { error: "Transcription nécessaire" });
      const key = process.env.OPENAI_API_KEY;
      if (!key) return json(res, 503, { error: "Clé OpenAI absente sur le serveur ; résumé manuel disponible" });
      const response = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST", headers: { Authorization: "Bearer " + key, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "gpt-4o-mini", temperature: 0.2, max_tokens: 700,
          messages: [
            { role: "system", content: "Résume fidèlement cet enregistrement en français. Trois à six points clairs, puis décisions et actions uniquement si elles figurent dans le texte. Ne rien inventer." },
            { role: "user", content: String(metadata.transcript).slice(0, 60_000) },
          ],
        }), signal: AbortSignal.timeout(45000),
      });
      if (!response.ok) return json(res, 502, { error: "Service de résumé momentanément indisponible" });
      const answer = await response.json() as any;
      const summary = smallString(answer?.choices?.[0]?.message?.content, 12_000);
      if (!summary) return json(res, 502, { error: "Résumé vide" });
      metadata.summary = summary;
      await writeMetadata(input.id, metadata);
      return json(res, 200, { summary });
    }
    return json(res, 404, { error: "Opération inconnue" });
  } catch (error) {
    console.error("Private audio library failed", error instanceof Error ? error.name : "unknown");
    return json(res, 500, { error: "Opération impossible sur la bibliothèque" });
  }
}
