import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 4173);
const HOST = process.env.HOST || "127.0.0.1";
const MAX_BODY = 30 * 1024 * 1024;
const TIMEOUT_MS = 180_000;

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".ico": "image/x-icon"
};

function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(body)
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error("请求体超过 30 MB 限制。"), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function normalizeBase(value) {
  const raw = String(value || "https://api.deepseek.com").trim().replace(/\/+$/, "");
  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    throw Object.assign(new Error("接口地址格式不正确。"), { statusCode: 400 });
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw Object.assign(new Error("接口地址只支持 http/https。"), { statusCode: 400 });
  }
  return raw;
}

async function proxy(req, res, endpoint, { method = "POST", body = null } = {}) {
  const key = String(req.headers["x-api-key"] || "").trim();
  if (!key) return json(res, 401, { error: { message: "请先在设置页填写 API Key。" } });
  const base = normalizeBase(req.headers["x-api-base"]);
  const url = `${base}${endpoint}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      method,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${key}`
      },
      body: body ?? (method === "POST" ? await readBody(req) : null),
      signal: controller.signal
    });
    const text = await response.text();
    const contentType = response.headers.get("content-type") || "application/json; charset=utf-8";
    res.writeHead(response.status, {
      "Content-Type": contentType,
      "Cache-Control": "no-store",
      "Content-Length": Buffer.byteLength(text)
    });
    res.end(text);
  } catch (error) {
    if (error?.name === "AbortError") {
      return json(res, 504, { error: { message: "上游接口超时，请稍后重试。" } });
    }
    if (error?.statusCode) return json(res, error.statusCode, { error: { message: error.message } });
    console.error("[proxy]", error?.message || error);
    return json(res, 502, { error: { message: `无法连接上游接口：${error?.message || "网络错误"}` } });
  } finally {
    clearTimeout(timer);
  }
}

async function serveStatic(req, res, pathname) {
  const decoded = decodeURIComponent(pathname);
  const relative = decoded === "/" ? "index.html" : decoded.replace(/^\/+/, "");
  const target = path.resolve(__dirname, relative);
  if (!target.startsWith(`${__dirname}${path.sep}`) && target !== __dirname) {
    return json(res, 403, { error: { message: "禁止访问。" } });
  }
  try {
    const info = await stat(target);
    if (!info.isFile()) return json(res, 404, { error: { message: "Not found" } });
    const body = await readFile(target);
    res.writeHead(200, {
      "Content-Type": MIME[path.extname(target).toLowerCase()] || "application/octet-stream",
      "Cache-Control": "no-cache",
      "Content-Length": body.length
    });
    res.end(body);
  } catch {
    return json(res, 404, { error: { message: "Not found" } });
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || `${HOST}:${PORT}`}`);
  try {
    if (url.pathname === "/api/health") return json(res, 200, { ok: true, service: "medical-question-ai-web" });
    if (url.pathname === "/api/deepseek/chat") {
      if (req.method !== "POST") return json(res, 405, { error: { message: "Method not allowed" } });
      return proxy(req, res, "/chat/completions");
    }
    if (url.pathname === "/api/deepseek/models") {
      if (req.method !== "GET") return json(res, 405, { error: { message: "Method not allowed" } });
      return proxy(req, res, "/models", { method: "GET" });
    }
    return serveStatic(req, res, url.pathname);
  } catch (error) {
    console.error("[server]", error);
    return json(res, 500, { error: { message: "服务内部错误。" } });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`医学拍题助手 Web: http://${HOST}:${PORT}`);
  console.log("API 代理已就绪：/api/deepseek/chat, /api/deepseek/models");
});
