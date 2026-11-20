import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { handle, resetForTests } from "../src/handler.js";
import { parseRequest, safeEqual, trim, upstreamUrl } from "../src/lib.js";

const NOW = Date.parse("2026-11-14T12:00:00Z");
const START = "2026-11-13T21:00:00Z";
const ctx = { waitUntil() {} };

let keyPair;
let pem;
let calls;
const realFetch = globalThis.fetch;

const UPSTREAM = {
  currentWeather: {
    name: "CurrentWeather", metadata: { latitude: 41.01, longitude: 28.95 }, asOf: "2026-11-14T12:00:00Z",
    conditionCode: "Rain", temperature: 12.1, temperatureApparent: 10.4, humidity: 0.9, pressure: 1003,
    precipitationIntensity: 1.2, uvIndex: 1, daylight: true, windSpeed: 18, windGust: 30, windDirection: 210,
  },
  forecastHourly: { name: "HourlyForecast", metadata: {}, hours: [
    { forecastStart: START, conditionCode: "Cloudy", temperature: 9, temperatureApparent: 8, precipitationChance: 0.1,
      precipitationAmount: 0, windSpeed: 10, windGust: 20, uvIndex: 0, pressure: 1004, visibility: 20000 },
  ] },
  forecastDaily: { name: "DailyForecast", metadata: {}, days: [
    { forecastStart: START, conditionCode: "Rain", temperatureMax: 14, temperatureMin: 8, precipitationChance: 0.8,
      sunrise: "2026-11-14T04:02:00Z", sunset: "2026-11-14T13:52:00Z", moonPhase: "full", daytimeForecast: {} },
  ] },
};

beforeEach(async () => {
  resetForTests();
  keyPair ??= await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const der = Buffer.from(await crypto.subtle.exportKey("pkcs8", keyPair.privateKey)).toString("base64");
  pem = `-----BEGIN PRIVATE KEY-----\n${der.match(/.{1,64}/g).join("\n")}\n-----END PRIVATE KEY-----\n`;
  calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    return new Response(JSON.stringify(UPSTREAM), { status: 200, headers: { "content-type": "application/json" } });
  };
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

const env = (extra = {}) => ({ TEAM_ID: "TEAM123456", KEY_ID: "KEY1234567", SERVICE_ID: "com.pofuduk.weather", PRIVATE_KEY: pem, ...extra });
const get = (path, headers = {}) => new Request(`https://proxy.example${path}`, { headers });
const weatherPath = `/v1/weather?lat=41.01384&lon=28.94966&tz=Europe/Istanbul&start=${START}`;
const decode = (part) => JSON.parse(Buffer.from(part, "base64url").toString());

test("forecast request: rounded coordinates, 7-day range, trimmed body", async () => {
  const res = await handle(get(weatherPath), env(), ctx, () => NOW);
  assert.equal(res.status, 200);
  const upstream = new URL(calls[0].url);
  assert.equal(upstream.pathname, "/api/v1/weather/tr/41.01/28.95");
  assert.equal(upstream.searchParams.get("timezone"), "Europe/Istanbul");
  assert.equal(upstream.searchParams.get("dataSets"), "currentWeather,forecastHourly,forecastDaily");
  assert.equal(upstream.searchParams.get("hourlyStart"), START);
  assert.equal(upstream.searchParams.get("hourlyEnd"), "2026-11-20T21:00:00Z");
  assert.equal(upstream.searchParams.get("dailyEnd"), "2026-11-20T21:00:00Z");
  const body = await res.json();
  assert.equal(body.currentWeather.conditionCode, "Rain");
  assert.equal(body.currentWeather.metadata, undefined, "metadata (echoes coordinates) is dropped");
  assert.equal(body.currentWeather.pressure, undefined);
  assert.equal(body.forecastHourly.hours[0].visibility, undefined);
  assert.equal(body.forecastDaily.days[0].sunrise, "2026-11-14T04:02:00Z");
  assert.equal(body.forecastDaily.days[0].moonPhase, undefined);
});

test("token is a valid ES256 JWT with Apple's header and claims", async () => {
  await handle(get(weatherPath), env(), ctx, () => NOW);
  const jwt = calls[0].init.headers.authorization.replace("Bearer ", "");
  const [h, p, s] = jwt.split(".");
  assert.deepEqual(decode(h), { alg: "ES256", kid: "KEY1234567", id: "TEAM123456.com.pofuduk.weather" });
  const payload = decode(p);
  assert.equal(payload.iss, "TEAM123456");
  assert.equal(payload.sub, "com.pofuduk.weather");
  assert.equal(payload.exp - payload.iat, 3600);
  assert.equal(payload.iat, NOW / 1000);
  const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, keyPair.publicKey,
    Buffer.from(s, "base64url"), new TextEncoder().encode(`${h}.${p}`));
  assert.ok(ok, "signature verifies with the public key");
});

test("current endpoint asks only for current weather", async () => {
  const res = await handle(get("/v1/current?lat=40.99&lon=29.03&tz=Europe/Istanbul"), env(), ctx, () => NOW);
  assert.equal(res.status, 200);
  const upstream = new URL(calls[0].url);
  assert.equal(upstream.searchParams.get("dataSets"), "currentWeather");
  assert.equal(upstream.searchParams.get("hourlyStart"), null);
});

test("same ~1 km cell is served from memory cache", async () => {
  await handle(get(weatherPath), env(), ctx, () => NOW);
  const second = await handle(get(`/v1/weather?lat=41.012&lon=28.951&tz=Europe/Istanbul&start=${START}`), env(), ctx, () => NOW + 60_000);
  assert.equal(second.headers.get("x-cache"), "memory");
  assert.equal(calls.length, 1);
  await handle(get(weatherPath), env(), ctx, () => NOW + 31 * 60_000);
  assert.equal(calls.length, 2, "expires after 30 minutes");
});

test("rejects invalid input before calling Apple", async () => {
  const bad = [
    "/v1/weather?lat=91&lon=29&tz=Europe/Istanbul&start=" + START,
    "/v1/weather?lat=abc&lon=29&tz=Europe/Istanbul&start=" + START,
    "/v1/weather?lat=41&lon=29&tz=Mars/Base&start=" + START,
    "/v1/weather?lat=41&lon=29&tz=Europe/Istanbul",
    "/v1/weather?lat=41&lon=29&tz=Europe/Istanbul&start=2026-11-13T21:07:00Z",
    "/v1/weather?lat=41&lon=29&tz=Europe/Istanbul&start=2026-10-01T21:00:00Z",
    "/v1/current?lat=41&lon=29",
  ];
  for (const path of bad) {
    const res = await handle(get(path), env(), ctx, () => NOW);
    assert.equal(res.status, 400, path);
  }
  assert.equal((await handle(get("/admin"), env(), ctx, () => NOW)).status, 404);
  assert.equal((await handle(new Request("https://proxy.example/v1/current", { method: "POST" }), env(), ctx, () => NOW)).status, 405);
  assert.equal(calls.length, 0);
});

test("optional app key is enforced", async () => {
  const withKey = env({ APP_KEY: "s3cret" });
  assert.equal((await handle(get(weatherPath), withKey, ctx, () => NOW)).status, 401);
  assert.equal((await handle(get(weatherPath, { "x-pofuduk-key": "wrong" }), withKey, ctx, () => NOW)).status, 401);
  assert.equal((await handle(get(weatherPath, { "x-pofuduk-key": "s3cret" }), withKey, ctx, () => NOW)).status, 200);
});

test("rate limiter binding is honoured", async () => {
  const limited = env({ LIMITER: { limit: async () => ({ success: false }) } });
  assert.equal((await handle(get(weatherPath), limited, ctx, () => NOW)).status, 429);
  assert.equal(calls.length, 0);
});

test("upstream failures map to 502/429, missing secrets to 500", async () => {
  globalThis.fetch = async () => new Response("no", { status: 401 });
  assert.equal((await handle(get(weatherPath), env(), ctx, () => NOW)).status, 502);
  globalThis.fetch = async () => new Response("slow down", { status: 429 });
  assert.equal((await handle(get(weatherPath), env(), ctx, () => NOW)).status, 429);
  globalThis.fetch = async () => { throw new Error("offline"); };
  assert.equal((await handle(get(weatherPath), env(), ctx, () => NOW)).status, 502);
  globalThis.fetch = async () => new Response("<html>", { status: 200 });
  assert.equal((await handle(get(weatherPath), env(), ctx, () => NOW)).status, 502);
  assert.equal((await handle(get(weatherPath), env({ PRIVATE_KEY: "" }), ctx, () => NOW)).status, 500);
});

test("helpers", () => {
  assert.ok(safeEqual("abc", "abc"));
  assert.ok(!safeEqual("abc", "abd"));
  assert.ok(!safeEqual("abc", "abcd"));
  assert.deepEqual(trim({}), {});
  const req = parseRequest(new URL("https://x/v1/weather?lat=-33.8688&lon=151.2093&tz=Australia/Sydney&start=2026-11-13T13:00:00Z"), NOW);
  assert.equal(req.lat, "-33.87");
  assert.ok(upstreamUrl(req).startsWith("https://weatherkit.apple.com/api/v1/weather/tr/-33.87/151.21?"));
  // Half-hour offsets (e.g. India) are valid local midnights.
  assert.equal(parseRequest(new URL("https://x/v1/weather?lat=19&lon=72&tz=Asia/Kolkata&start=2026-11-13T18:30:00Z"), NOW).start,
    "2026-11-13T18:30:00Z");
});
