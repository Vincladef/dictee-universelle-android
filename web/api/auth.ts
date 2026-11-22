import { authorized, clearCookie, headers, json, loginCookie, parseBody, sameOrigin, secret } from "../lib/server";

export default async function handler(req: any, res: any) {
  headers(res);
  if (req.method === "GET") return json(res, 200, { authenticated: authorized(req), configured: secret().length >= 32 });
  if (req.method !== "POST") return json(res, 405, { error: "Méthode non autorisée" });
  if (!sameOrigin(req)) return json(res, 403, { error: "Origine refusée" });
  const body = parseBody(req);
  if (body.action === "logout") {
    res.setHeader("Set-Cookie", clearCookie());
    return json(res, 200, { ok: true });
  }
  if (secret().length < 32) return json(res, 503, { error: "Synchronisation non configurée côté serveur" });
  const candidate = String(body.token || "");
  if (!authorized({ headers: { authorization: "Bearer " + candidate } })) {
    return json(res, 401, { error: "Code incorrect" });
  }
  res.setHeader("Set-Cookie", loginCookie());
  return json(res, 200, { ok: true });
}
