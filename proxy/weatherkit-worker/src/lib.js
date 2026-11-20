// Pure helpers for the WeatherKit proxy (no Cloudflare-only APIs, so they run under `node --test`).

export const WEATHERKIT_BASE = "https://weatherkit.apple.com/api/v1/weather";
export const FORECAST_DAYS = 7;
const DAY_MS = 24 * 60 * 60 * 1000;

const b64url = (bytes) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlJson = (obj) => b64url(new TextEncoder().encode(JSON.stringify(obj)));

/** PKCS#8 PEM (the downloaded AuthKey_XXXX.p8) → CryptoKey for ES256. */
export async function importPrivateKey(pem) {
  const body = String(pem).replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

/**
 * WeatherKit developer token (ES256 JWT). WebCrypto returns the raw r||s signature, which is the JWS format.
 * https://developer.apple.com/documentation/weatherkitrestapi/request_authentication_for_weatherkit_rest_api
 */
export async function signToken({ teamId, keyId, serviceId, key, nowSeconds, lifetimeSeconds = 3600 }) {
  const header = { alg: "ES256", kid: keyId, id: `${teamId}.${serviceId}` };
  const payload = { iss: teamId, iat: nowSeconds, exp: nowSeconds + lifetimeSeconds, sub: serviceId };
  const input = `${b64urlJson(header)}.${b64urlJson(payload)}`;
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(input));
  return `${input}.${b64url(signature)}`;
}

function coordinate(value, limit) {
  if (value === null || value === "" || !/^-?\d{1,3}(\.\d+)?$/.test(value)) return null;
  const n = Number(value);
  return Number.isFinite(n) && Math.abs(n) <= limit ? n.toFixed(2) : null;
}

function timeZone(value) {
  if (!value || value.length > 64 || !/^[A-Za-z][A-Za-z0-9_+\-]*(\/[A-Za-z0-9_+\-]+){0,2}$/.test(value)) return null;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    return value;
  } catch {
    return null;
  }
}

const iso = (ms) => new Date(ms).toISOString().replace(".000Z", "Z");

/**
 * Validates and normalizes a request. Coordinates are rounded to 2 decimals (~1 km) — also the cache cell.
 * `start` must be a local midnight close to now (any UTC offset is a multiple of 15 minutes).
 * Returns { error } or { kind, lat, lon, tz, start, end }.
 */
export function parseRequest(url, nowMs) {
  const kind = url.pathname === "/v1/weather" ? "weather" : url.pathname === "/v1/current" ? "current" : null;
  if (!kind) return { error: "not_found", status: 404 };
  const q = url.searchParams;
  const lat = coordinate(q.get("lat"), 90);
  const lon = coordinate(q.get("lon"), 180);
  const tz = timeZone(q.get("tz"));
  if (!lat || !lon || !tz) return { error: "bad_request", status: 400 };
  if (kind === "current") return { kind, lat, lon, tz };
  const startMs = Date.parse(q.get("start") ?? "");
  if (!Number.isFinite(startMs) || startMs % (15 * 60 * 1000) !== 0 || Math.abs(startMs - nowMs) > 1.5 * DAY_MS) {
    return { error: "bad_request", status: 400 };
  }
  return { kind, lat, lon, tz, start: iso(startMs), end: iso(startMs + FORECAST_DAYS * DAY_MS) };
}

/** Normalized cache identity (no client-specific data). */
export function cacheKey(req) {
  return `${req.kind}/${req.lat}/${req.lon}/${encodeURIComponent(req.tz)}/${req.start ?? ""}`;
}

export function upstreamUrl(req) {
  const params = new URLSearchParams({ timezone: req.tz });
  if (req.kind === "current") {
    params.set("dataSets", "currentWeather");
  } else {
    params.set("dataSets", "currentWeather,forecastHourly,forecastDaily");
    params.set("hourlyStart", req.start);
    params.set("hourlyEnd", req.end);
    params.set("dailyStart", req.start);
    params.set("dailyEnd", req.end);
  }
  return `${WEATHERKIT_BASE}/tr/${req.lat}/${req.lon}?${params}`;
}

const pick = (obj, keys) =>
  obj ? Object.fromEntries(keys.filter((k) => obj[k] !== undefined).map((k) => [k, obj[k]])) : undefined;

const CURRENT_KEYS = ["asOf", "conditionCode", "daylight", "humidity", "precipitationIntensity", "temperature",
  "temperatureApparent", "uvIndex", "windDirection", "windGust", "windSpeed"];
const HOUR_KEYS = ["forecastStart", "conditionCode", "temperature", "temperatureApparent", "precipitationChance",
  "precipitationAmount", "windSpeed", "windGust", "uvIndex"];
const DAY_KEYS = ["forecastStart", "conditionCode", "temperatureMax", "temperatureMin", "precipitationChance", "sunrise", "sunset"];

/** Keeps only what the app reads (smaller responses, no metadata echoing the coordinates). */
export function trim(body) {
  const out = {};
  if (body.currentWeather) out.currentWeather = pick(body.currentWeather, CURRENT_KEYS);
  if (body.forecastHourly) out.forecastHourly = { hours: (body.forecastHourly.hours ?? []).map((h) => pick(h, HOUR_KEYS)) };
  if (body.forecastDaily) out.forecastDaily = { days: (body.forecastDaily.days ?? []).map((d) => pick(d, DAY_KEYS)) };
  return out;
}

/** Constant-time string comparison for the optional app key. */
export function safeEqual(a, b) {
  const x = new TextEncoder().encode(String(a));
  const y = new TextEncoder().encode(String(b));
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}
