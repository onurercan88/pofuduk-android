// Pofuduk WeatherKit proxy (Cloudflare Worker) entry point. Logic lives in handler.js / lib.js (tested with node --test).
import { handle } from "./handler.js";

export default {
  fetch: (request, env, ctx) => handle(request, env, ctx),
};
