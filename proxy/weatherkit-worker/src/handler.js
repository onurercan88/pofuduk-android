// Pofuduk WeatherKit proxy (Cloudflare Worker). Holds the WeatherKit key, signs requests, caches per ~1 km cell.
// Never logs coordinates, IPs or request URLs. See README.md for setup.
import { cacheKey, importPrivateKey, parseRequest, safeEqual, signToken, trim, upstreamUrl } from "./lib.js";

const TTL_SECONDS = { weather: 30 * 60, current: 10 * 60 };
const MEMORY_LIMIT = 500;

// Per-isolate state (best effort; isolates come and go).
let cachedToken = null; // { value, exp }
let cachedKey = null; // { pem, key }
const memory = new Map(); // cacheKey → { body, exp }

const json = (status, body, extra = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
  });

async function token(env, nowSeconds) {
  if (cachedToken && cachedToken.exp - 300 > nowSeconds) return cachedToken.value;
  if (!cachedKey || cachedKey.pem !== env.PRIVATE_KEY) cachedKey = { pem: env.PRIVATE_KEY, key: await importPrivateKey(env.PRIVATE_KEY) };
  const value = await signToken({
    teamId: env.TEAM_ID.trim(), keyId: env.KEY_ID.trim(), serviceId: env.SERVICE_ID.trim(), key: cachedKey.key, nowSeconds,
  });
  cachedToken = { value, exp: nowSeconds + 3600 };
  return value;
}

function remember(key, body, ttl, nowMs) {
  if (memory.size >= MEMORY_LIMIT) {
    for (const [k, v] of memory) if (v.exp <= nowMs) memory.delete(k);
    if (memory.size >= MEMORY_LIMIT) memory.delete(memory.keys().next().value);
  }
  memory.set(key, { body, exp: nowMs + ttl * 1000 });
}

export async function handle(request, env, ctx, now = () => Date.now()) {
  if (request.method !== "GET") return json(405, { error: "method_not_allowed" }, { allow: "GET" });
  if (env.APP_KEY && !safeEqual(request.headers.get("x-pofuduk-key") ?? "", env.APP_KEY.trim())) {
    return json(401, { error: "unauthorized" });
  }
  if (env.LIMITER) {
    const { success } = await env.LIMITER.limit({ key: request.headers.get("cf-connecting-ip") ?? "unknown" });
    if (!success) return json(429, { error: "rate_limited" }, { "retry-after": "60" });
  }
  const nowMs = now();
  const req = parseRequest(new URL(request.url), nowMs);
  if (req.error) return json(req.status, { error: req.error });
  if (!env.TEAM_ID || !env.KEY_ID || !env.SERVICE_ID || !env.PRIVATE_KEY) return json(500, { error: "not_configured" });

  const key = cacheKey(req);
  const ttl = TTL_SECONDS[req.kind];
  const hit = memory.get(key);
  if (hit && hit.exp > nowMs) return json(200, hit.body, { "x-cache": "memory" });

  // Cloudflare edge cache (effective on a custom domain; a no-op on *.workers.dev).
  const edge = globalThis.caches?.default;
  const edgeRequest = new Request(`https://cache.pofuduk.internal/${key}`);
  const edgeHit = edge ? await edge.match(edgeRequest) : undefined;
  if (edgeHit) {
    const body = await edgeHit.json();
    remember(key, body, ttl, nowMs);
    return json(200, body, { "x-cache": "edge" });
  }

  let upstream;
  try {
    upstream = await fetch(upstreamUrl(req), {
      headers: { authorization: `Bearer ${await token(env, Math.floor(nowMs / 1000))}` },
    });
  } catch {
    return json(502, { error: "upstream_unreachable" });
  }
  if (upstream.status === 429) return json(429, { error: "rate_limited" }, { "retry-after": "60" });
  if (!upstream.ok) {
    if (upstream.status === 401 || upstream.status === 403) cachedToken = null;
    console.error(`weatherkit status ${upstream.status}`);
    return json(502, { error: "upstream_error" });
  }
  let body;
  try {
    body = trim(await upstream.json());
  } catch {
    return json(502, { error: "upstream_invalid" });
  }
  remember(key, body, ttl, nowMs);
  if (edge) {
    const stored = new Response(JSON.stringify(body), {
      headers: { "content-type": "application/json", "cache-control": `public, max-age=${ttl}` },
    });
    ctx.waitUntil(edge.put(edgeRequest, stored));
  }
  return json(200, body, { "x-cache": "miss" });
}

export function resetForTests() {
  cachedToken = null;
  cachedKey = null;
  memory.clear();
}
