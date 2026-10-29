import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 4173);
const HOST = process.env.HOST || "127.0.0.1";
const MAX_BODY = 30 * 1024 * 1024;
// 详细模式客户端整体预算 240s，代理层留一点余量。
const TIMEOUT_MS = 300_000;
const CONNECT_TIMEOUT_MS = 8_000;
const FIRST_BYTE_TIMEOUT_MS = 45_000;

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
  if (res.writableEnded) return;
  const body = JSON.stringify(payload);
  if (!res.headersSent) {
    res.writeHead(status, {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Content-Length": Buffer.byteLength(body)
    });
  }
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

function waitForDrain(res) {
  return new Promise((resolve) => {
    const finish = () => {
      res.off("drain", finish);
      res.off("close", finish);
      resolve();
    };
    res.once("drain", finish);
    res.once("close", finish);
  });
}

async function readUpstreamText(response, timeoutMs = 8000) {
  let timer = null;
  try {
    return await Promise.race([
      response.text(),
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          try { response.body?.cancel?.(); } catch { /* noop */ }
          const error = new Error("读取上游响应超时");
          error.statusCode = 504;
          reject(error);
        }, timeoutMs);
      })
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function proxy(req, res, endpoint, { method = "POST", body = null } = {}) {
  const key = String(req.headers["x-api-key"] || "").trim();
  if (!key) return json(res, 401, { error: { message: "请先在设置页填写 API Key。" } });
  const base = normalizeBase(req.headers["x-api-base"]);
  const url = `${base}${endpoint}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let clientGone = false;
  let streamingStarted = false;
  let connectTimedOut = false;
  let firstByteTimedOut = false;

  const onClientAbort = () => {
    clientGone = true;
    controller.abort();
  };
  req.on("aborted", onClientAbort);
  res.on("close", () => {
    if (!res.writableEnded) onClientAbort();
  });

  try {
    let requestBody = body;
    if (method === "POST" && requestBody === null) {
      requestBody = await readBody(req);
    }
    let streamRequested = false;
    if (requestBody) {
      try {
        streamRequested = JSON.parse(requestBody)?.stream === true;
      } catch {
        streamRequested = false;
      }
    }

    const connectTimer = setTimeout(() => {
      connectTimedOut = true;
      controller.abort();
    }, CONNECT_TIMEOUT_MS);
    let response;
    try {
      response = await fetch(url, {
        method,
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${key}`
        },
        body: requestBody,
        signal: controller.signal
      });
    } finally {
      clearTimeout(connectTimer);
    }
    const contentType = response.headers.get("content-type")
      || (streamRequested ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8");

    if (!streamRequested || !response.ok) {
      // 非流式请求（以及流式请求的上游错误）按原行为缓冲后返回。
      const text = await readUpstreamText(response, 8000);
      if (res.writableEnded) return;
      res.writeHead(response.status, {
        "Content-Type": contentType,
        "Cache-Control": "no-store",
        "Content-Length": Buffer.byteLength(text)
      });
      res.end(text);
      return;
    }

    // 流式：逐块转发，尊重 res.write 的背压；客户端断开时中止上游。
    res.writeHead(response.status, {
      "Content-Type": contentType || "text/event-stream; charset=utf-8",
      "Cache-Control": "no-store",
      "Connection": "keep-alive",
      "X-Accel-Buffering": "no"
    });
    streamingStarted = true;
    res.flushHeaders?.();

    if (!response.body) {
      const text = await readUpstreamText(response, 8000);
      if (!res.writableEnded) res.end(text);
      return;
    }

    const reader = response.body.getReader();
    let firstByteSeen = false;
    const firstByteTimer = setTimeout(() => {
      firstByteTimedOut = true;
      controller.abort();
    }, FIRST_BYTE_TIMEOUT_MS);
    try {
      while (!clientGone) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!value || !value.length) continue;
        if (!firstByteSeen) {
          firstByteSeen = true;
          clearTimeout(firstByteTimer);
        }
        if (!res.write(Buffer.from(value))) {
          await waitForDrain(res);
        }
      }
    } finally {
      clearTimeout(firstByteTimer);
      try { reader.releaseLock(); } catch { /* noop */ }
      if (!res.writableEnded) res.end();
    }
  } catch (error) {
    if (streamingStarted) {
      // 响应头已经按 SSE 发出，绝不能再写第二套 JSON HTTP 头。
      if (res.writableEnded) return;
      const message = firstByteTimedOut
        ? "上游超过 45 秒未返回首个数据，流式响应已中断。"
        : (error?.name === "AbortError"
          ? "上游接口超时，流式响应已中断。"
          : `上游流式响应中断：${error?.message || "网络错误"}`);
      try {
        res.write(`event: error\ndata: ${JSON.stringify({ error: { message } })}\n\n`);
      } catch { /* client already gone */ }
      res.end();
      return;
    }
    if (error?.name === "AbortError") {
      if (!clientGone) {
        return json(res, 504, {
          error: {
            message: connectTimedOut
              ? "连接上游接口超过 8 秒，请检查网络或服务地址。"
              : "上游接口超时，请稍后重试。"
          }
        });
      }
      return;
    }
    if (error?.statusCode) return json(res, error.statusCode, { error: { message: error.message } });
    if (clientGone || res.writableEnded) return;
    console.error("[proxy]", error?.message || error);
    return json(res, 502, { error: { message: `无法连接上游接口：${error?.message || "网络错误"}` } });
  } finally {
    clearTimeout(timer);
    req.off("aborted", onClientAbort);
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
