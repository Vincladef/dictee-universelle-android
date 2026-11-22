import { createHmac, timingSafeEqual } from "node:crypto";
import { get, put, list } from "@vercel/blob";

export const MAX_AUDIO_BYTES = 3_300_000;
const ID = /^d_[0-9]+_[a-f0-9]{8}$/;
const SESSION_COOKIE = "dictee_session";

export function secret(): string { return process.env.DICTEE_SYNC_TOKEN || ""; }
function constantEqual(a: string, b: string): boolean {
  const left = Buffer.from(a, "utf8"), right = Buffer.from(b, "utf8");
  return left.length > 0 && left.length === right.length && timingSafeEqual(left, right);
}
function signature(input: string): string {
  return createHmac("sha256", secret()).update(input).digest("base64url");
}
export function loginCookie(): string {
  const expires = Math.floor(Date.now() / 1000) + 7 * 86400;
  const payload = "v1." + expires;
  return SESSION_COOKIE + "=" + payload + "." + signature(payload) +
    "; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=604800";
}
export function clearCookie(): string {
  return SESSION_COOKIE + "=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0";
}
function validCookie(req: any): boolean {
  const raw = String(req.headers.cookie || "").split(";").map((s: string) => s.trim())
    .find((s: string) => s.startsWith(SESSION_COOKIE + "="));
  const value = raw?.slice(SESSION_COOKIE.length + 1) || "";
  const match = /^(v1\.[0-9]{10,11})\.([A-Za-z0-9_-]{43})$/.exec(value);
  if (!match || Number(match[1].slice(3)) < Date.now() / 1000) return false;
  return constantEqual(match[2], signature(match[1]));
}
export function authorized(req: any): boolean {
  const value = secret();
  if (value.length < 32) return false;
  const match = /^Bearer\s+(.+)$/i.exec(String(req.headers.authorization || ""));
  return (match != null && constantEqual(match[1], value)) || validCookie(req);
}
export function sameOrigin(req: any): boolean {
  const origin = req.headers.origin;
  if (!origin) return true; // Native Android client, authenticated with Bearer token.
  try {
    return new URL(String(origin)).host === String(req.headers.host) &&
      new URL(String(origin)).protocol === "https:";
  } catch { return false; }
}
export function json(res: any, status: number, content: unknown) {
  return res.status(status).json(content);
}
export function headers(res: any) {
  res.setHeader("Cache-Control", "private, no-store, max-age=0");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Vary", "Cookie, Authorization");
}
export function validId(id: unknown): id is string { return typeof id === "string" && ID.test(id); }
export function parseBody(req: any): any {
  if (typeof req.body === "string") return JSON.parse(req.body);
  return req.body || {};
}
export function pathFor(id: string): string { return "meta/" + id + ".json"; }
export async function readMetadata(id: string): Promise<any | null> {
  const blob = await get(pathFor(id), { access: "private" });
  if (!blob || !blob.stream || blob.statusCode !== 200) return null;
  const raw = await new Response(blob.stream).text();
  if (raw.length > 180_000) return null;
  return JSON.parse(raw);
}
export async function writeMetadata(id: string, data: any) {
  await put(pathFor(id), JSON.stringify(data), {
    access: "private", allowOverwrite: true, addRandomSuffix: false,
    contentType: "application/json",
  });
}
export async function allMetadata(): Promise<any[]> {
  let cursor: string | undefined;
  const results: any[] = [];
  do {
    const page = await list({ prefix: "meta/", limit: 200, cursor });
    for (const entry of page.blobs) {
      const match = /^meta\/(d_[0-9]+_[a-f0-9]{8})\.json$/.exec(entry.pathname);
      if (!match) continue;
      try {
        const metadata = await readMetadata(match[1]);
        if (metadata) results.push(metadata);
      } catch { /* Skip an individual corrupt record; preserve it in storage. */ }
    }
    cursor = page.hasMore ? page.cursor : undefined;
    if (results.length >= 500) break;
  } while (cursor);
  return results.sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0));
}
