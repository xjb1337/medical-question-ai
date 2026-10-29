#!/usr/bin/env node
/**
 * Browser-level repair regression smoke test.
 *
 * Covers:
 *  - synthetic image import reaches the draft (R1 client flow)
 *  - crop "keep original" produces a second image and preserves the first (R2)
 *  - analysis token usage is rendered
 *  - follow-up token usage is rendered (R4)
 *  - history receives the completed question
 *
 * Usage: node web/test/browser-repair-smoke.mjs
 * Requires: Node 20+, a local Chrome/Edge/Chromium installation.
 */
import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const WEB_DIR = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const WEB_PORT = Number(process.env.WEB_TEST_PORT || 4191);
const MOCK_PORT = Number(process.env.MOCK_TEST_PORT || 4898);
const CDP_PORT = Number(process.env.CHROME_CDP_PORT || 9231);
const BASE = `http://127.0.0.1:${WEB_PORT}/`;
const CDP = `http://127.0.0.1:${CDP_PORT}`;
const sleep = (ms) => new Promise((resolvePromise) => setTimeout(resolvePromise, ms));

function chromeCandidates() {
  return [
    process.env.CHROME_PATH,
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  ].filter(Boolean);
}

async function waitHttp(url, timeout = 20000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    try {
      const response = await fetch(url);
      if (response.ok) return response;
    } catch {}
    await sleep(150);
  }
  throw new Error(`timeout waiting for ${url}`);
}

function waitForExit(child, timeoutMs) {
  return new Promise((resolvePromise) => {
    if (!child || child.exitCode !== null) {
      resolvePromise(true);
      return;
    }
    let settled = false;
    const finish = (exited) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      child.removeListener("exit", onExit);
      resolvePromise(exited);
    };
    const onExit = () => finish(true);
    const timer = setTimeout(() => finish(false), timeoutMs);
    child.once("exit", onExit);
  });
}

async function stopBrowser(chrome) {
  if (!chrome) return;
  if (await waitForExit(chrome, 0)) return;
  try { chrome.kill("SIGTERM"); } catch {}
  if (await waitForExit(chrome, 5000)) return;
  if (process.platform === "win32") {
    try { execFileSync("taskkill", ["/PID", String(chrome.pid), "/T", "/F"], { stdio: "ignore" }); } catch {}
  } else {
    try { chrome.kill("SIGKILL"); } catch {}
  }
  await waitForExit(chrome, 5000);
}

async function removeDirWithRetry(dir, attempts = 12) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      rmSync(dir, { recursive: true, force: true });
      return;
    } catch (error) {
      lastError = error;
      const retryable = ["EBUSY", "EPERM", "ENOTEMPTY", "EACCES"].includes(error?.code);
      if (!retryable || attempt === attempts) throw error;
      await sleep(100 * attempt);
    }
  }
  if (lastError) throw lastError;
}

function startMockDeepSeek() {
  const analysis = {
    status: "answered",
    question_type: "single",
    recognized_question: "胸骨后压榨性疼痛，心电图 II、III、aVF 导联 ST 段抬高，最可能的诊断是？",
    answer: ["B"],
    answer_text: "B. 急性下壁心肌梗死",
    image_observations: [],
    option_analyses: [{ option: "B", verdict: "正确", reason: "II、III、aVF 导联对应下壁。" }],
    reasoning_steps: [{ title: "定位导联", detail: "II、III、aVF 导联 ST 段抬高提示下壁。" }],
    key_points: ["下壁导联为 II、III、aVF", "心肌梗死需结合肌钙蛋白"],
    pitfalls: ["不要只凭胸痛诊断"],
    confidence: "high",
    warnings: []
  };
  return new Promise((resolveServer) => {
    const server = createServer((request, response) => {
      if (request.url?.endsWith("/models")) {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end(JSON.stringify({ data: [{ id: "deepseek-flash" }] }));
        return;
      }
      if (request.url?.endsWith("/chat/completions")) {
        let body = "";
        request.on("data", (chunk) => { body += chunk; });
        request.on("end", () => {
          let parsed = {};
          try { parsed = JSON.parse(body); } catch {}
          if (body.includes("失败测试")) {
            response.writeHead(500, { "Content-Type": "application/json" });
            response.end(JSON.stringify({ error: { message: "模拟上游失败" } }));
            return;
          }
          const systemText = typeof parsed?.messages?.[0]?.content === "string" ? parsed.messages[0].content : "";
          const isFollowUp = systemText.includes("正在围绕一道医学课程题目回答学生追问");
          const content = isFollowUp
            ? (body.includes("无用量测试") ? "这是一条没有 usage 字段的追问回答。" : "因为 II、III、aVF 是下壁导联，所以选 B。")
            : JSON.stringify(analysis);
          const payload = {
            id: "mock",
            object: "chat.completion",
            model: "deepseek-flash",
            choices: [{ index: 0, message: { role: "assistant", content }, finish_reason: "stop" }]
          };
          if (!body.includes("无用量测试")) {
            payload.usage = { prompt_tokens: 234, completion_tokens: 56, total_tokens: 290 };
          }
          response.writeHead(200, { "Content-Type": "application/json" });
          response.end(JSON.stringify(payload));
        });
        return;
      }
      response.writeHead(404);
      response.end();
    });
    server.listen(MOCK_PORT, "127.0.0.1", () => resolveServer(server));
  });
}

async function connectCdp() {
  let target;
  for (let attempt = 0; attempt < 80; attempt += 1) {
    try {
      target = await fetch(`${CDP}/json/new?about:blank`, { method: "PUT" }).then((response) => response.json());
      break;
    } catch {
      await sleep(150);
    }
  }
  if (!target) throw new Error("Chrome CDP endpoint did not become ready");
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolvePromise, reject) => { ws.onopen = resolvePromise; ws.onerror = reject; });
  let nextId = 0;
  const pending = new Map();
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (!message.id || !pending.has(message.id)) return;
    const { resolve: resolveCall, reject } = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) reject(new Error(JSON.stringify(message.error)));
    else resolveCall(message.result);
  };
  const send = (method, params = {}) => new Promise((resolveCall, reject) => {
    const id = ++nextId;
    pending.set(id, { resolve: resolveCall, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
  await send("Runtime.enable");
  await send("Page.enable");
  return { ws, send };
}

async function evaluate(send, expression) {
  const result = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text || "browser evaluation failed");
  return result.result?.value;
}

async function waitFor(send, expression, timeout = 15000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    if (await evaluate(send, `Boolean(${expression})`)) return;
    await sleep(100);
  }
  throw new Error(`timeout waiting for ${expression}`);
}

async function runBrowserChecks() {
  const { ws, send } = await connectCdp();
  try {
    await send("Page.navigate", { url: BASE });
    await sleep(400);
    await waitFor(send, `document.querySelector('.sidebar')`, 20000);
    await evaluate(send, "localStorage.clear(); sessionStorage.clear();");
    await evaluate(send, `localStorage.setItem("medical-question-ai.settings.v1", JSON.stringify({
      apiKey: "test-key", model: "deepseek-flash", baseUrl: "http://127.0.0.1:${MOCK_PORT}",
      customSupportsImages: true, saveHistory: true, demoMode: false, rememberKey: true
    }))`);
    await send("Page.reload");
    await sleep(400);
    await waitFor(send, `document.querySelector('.sidebar')`, 20000);

    await evaluate(send, `(async () => {
      const canvas = document.createElement("canvas");
      canvas.width = 640; canvas.height = 480;
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, 640, 480);
      ctx.fillStyle = "#0f766e"; ctx.font = "28px sans-serif"; ctx.fillText("SYNTHETIC TEST IMAGE", 80, 120);
      const blob = await new Promise((resolveBlob) => canvas.toBlob(resolveBlob, "image/png"));
      const file = new File([blob], "synthetic.png", { type: "image/png" });
      const transfer = new DataTransfer();
      transfer.items.add(file);
      const input = document.querySelector("#image-input");
      Object.defineProperty(input, "files", { value: transfer.files, configurable: true });
      input.dispatchEvent(new Event("change", { bubbles: true }));
    })()`);
    await waitFor(send, `document.querySelectorAll(".image-card").length === 1`);
    const imported = await evaluate(send, `document.querySelectorAll(".image-card").length`);
    const originalImageSrc = await evaluate(send, `document.querySelector(".image-card img").src`);

    await evaluate(send, `document.querySelector('[data-action="edit-image"]').click()`);
    await waitFor(send, `document.querySelector('[data-action="crop-image"]')`);
    await evaluate(send, `document.querySelector('[data-action="crop-image"]').click()`);
    await waitFor(send, `document.querySelector("#crop-stage")`);
    await sleep(300);
    await evaluate(send, `document.querySelector('[data-action="apply-crop"][data-mode="keep"]').click()`);
    await waitFor(send, `document.querySelectorAll(".image-card").length === 2`);
    const afterCrop = await evaluate(send, `document.querySelectorAll(".image-card").length`);
    const originalPreserved = await evaluate(send, `document.querySelectorAll(".image-card img")[0].src === ${JSON.stringify(originalImageSrc)}`);
    const cropIsDistinct = await evaluate(send, `document.querySelectorAll(".image-card img")[0].src !== document.querySelectorAll(".image-card img")[1].src`);
    if (!originalPreserved || !cropIsDistinct) throw new Error("crop did not preserve an independent original image");

    // Fill up to the 9-image limit, then verify the 10th is rejected without replacing any draft image.
    await evaluate(send, `(async () => {
      const transfer = new DataTransfer();
      for (let index = 0; index < 7; index += 1) {
        const canvas = document.createElement("canvas");
        canvas.width = 64; canvas.height = 48;
        const ctx = canvas.getContext("2d");
        ctx.fillStyle = index % 2 ? "#e2e8f0" : "#0f766e";
        ctx.fillRect(0, 0, 64, 48);
        ctx.fillStyle = index % 2 ? "#0f766e" : "#e2e8f0";
        ctx.font = "14px sans-serif";
        ctx.fillText("LIMIT " + index, 4, 24);
        const blob = await new Promise((resolveBlob) => canvas.toBlob(resolveBlob, "image/png"));
        transfer.items.add(new File([blob], "limit-" + index + ".png", { type: "image/png" }));
      }
      const input = document.querySelector("#image-input");
      Object.defineProperty(input, "files", { value: transfer.files, configurable: true });
      input.dispatchEvent(new Event("change", { bubbles: true }));
    })()`);
    await waitFor(send, `document.querySelectorAll(".image-card").length === 9`, 20000);
    const atLimit = await evaluate(send, `document.querySelectorAll(".image-card").length`);
    await evaluate(send, `(async () => {
      const canvas = document.createElement("canvas");
      canvas.width = 64; canvas.height = 48;
      const blob = await new Promise((resolveBlob) => canvas.toBlob(resolveBlob, "image/png"));
      const transfer = new DataTransfer();
      transfer.items.add(new File([blob], "limit-10.png", { type: "image/png" }));
      const input = document.querySelector("#image-input");
      Object.defineProperty(input, "files", { value: transfer.files, configurable: true });
      input.dispatchEvent(new Event("change", { bubbles: true }));
    })()`);
    await sleep(800);
    const afterTenth = await evaluate(send, `document.querySelectorAll(".image-card").length`);
    if (afterTenth !== 9) throw new Error(`image limit failed: ${afterTenth}`);

    await evaluate(send, `document.querySelector('[data-action="load-sample"]').click()`);
    await waitFor(send, `document.querySelector("#question-text").value.includes("胸骨后")`);
    await evaluate(send, `document.querySelector('[data-action="start-analysis"]').click()`);
    await waitFor(send, `document.querySelector("#followup-text") && document.body.innerText.includes("心肌梗死")`, 20000);
    const resultRendered = await evaluate(send, `document.body.innerText.includes("290") && document.body.innerText.includes("已解析")`);

    await evaluate(send, `(() => {
      const el = document.querySelector("#followup-text");
      el.value = "为什么选 B？";
      el.dispatchEvent(new Event("input", { bubbles: true }));
      document.querySelector('[data-action="send-followup"]').click();
    })()`);
    await waitFor(send, `document.querySelector(".follow-usage")`, 15000);
    const followUsage = await evaluate(send, `document.querySelector(".follow-usage").innerText`);
    if (!followUsage.includes("234") || !followUsage.includes("290")) throw new Error(`follow-up usage wrong: ${followUsage}`);

    // A response without usage must hide the usage label rather than display fabricated zeros.
    await evaluate(send, `(() => {
      const el = document.querySelector("#followup-text");
      el.value = "无用量测试";
      el.dispatchEvent(new Event("input", { bubbles: true }));
      document.querySelector('[data-action="send-followup"]').click();
    })()`);
    await waitFor(send, `document.querySelectorAll(".follow-msg").length >= 4 && !document.querySelector(".follow-stream-state")`, 15000);
    const noUsageLabelHidden = await evaluate(send, `!document.querySelector(".follow-msg:last-child .follow-usage")`);
    if (!noUsageLabelHidden) throw new Error("missing usage was rendered as a usage label");

    // Simulate an old history record that predates the usage field.
    await evaluate(send, `(async () => {
      const { listQuestions, saveQuestion } = await import("/assets/store.js");
      const [question] = await listQuestions();
      question.followUps = [{ role: "assistant", text: "旧记录回答（无用量）", createdAt: Date.now() }];
      await saveQuestion(question);
    })()`);
    await send("Page.reload");
    await sleep(400);
    await waitFor(send, `document.body.innerText.includes("旧记录回答（无用量）")`, 15000);
    const oldRecordUsageHidden = await evaluate(send, `!document.querySelector(".follow-usage")`);
    if (!oldRecordUsageHidden) throw new Error("old record without usage should not show a usage label");

    // A failed follow-up must show an error and restore the user's draft text.
    await evaluate(send, `(() => {
      const el = document.querySelector("#followup-text");
      el.value = "失败测试";
      el.dispatchEvent(new Event("input", { bubbles: true }));
      document.querySelector('[data-action="send-followup"]').click();
    })()`);
    await waitFor(send, `document.querySelector(".error-banner")`, 10000);
    const failureRestored = await evaluate(send, `document.querySelector("#followup-text").value === "失败测试" && document.querySelector(".error-banner").innerText.includes("模拟上游失败")`);
    if (!failureRestored) throw new Error("failed follow-up did not restore the draft or show the upstream error");

    // P1-1: if the initial analyzing row cannot be written to IndexedDB, no
    // question may be added and the user's draft must remain intact.
    await evaluate(send, `location.hash = "#/compose"`);
    await waitFor(send, `document.querySelector("#question-text")`, 10000);
    await evaluate(send, `(() => {
      document.querySelector('[data-action="load-sample"]').click();
      window.__originalIdbPut = IDBObjectStore.prototype.put;
      IDBObjectStore.prototype.put = function () { throw new DOMException("Quota exceeded", "QuotaExceededError"); };
    })()`);
    await waitFor(send, `document.querySelector("#question-text").value.includes("胸骨后")`, 10000);
    const questionCountBeforeFailure = await evaluate(send, `(async () => (await import("/assets/store.js")).listQuestions().then((items) => items.length))()`);
    await evaluate(send, `document.querySelector('[data-action="start-analysis"]').click()`);
    await waitFor(send, `document.querySelector(".error-banner")`, 10000);
    const dbFailureState = await evaluate(send, `({
      onCompose: location.hash === "#/compose",
      draftKept: document.querySelector("#question-text").value.includes("胸骨后"),
      errorShown: document.querySelector(".error-banner").innerText.includes("无法写入本地数据库")
    })`);
    if (!dbFailureState.onCompose || !dbFailureState.draftKept || !dbFailureState.errorShown) {
      throw new Error(`IndexedDB failure transaction regression: ${JSON.stringify(dbFailureState)}`);
    }
    await evaluate(send, `IDBObjectStore.prototype.put = window.__originalIdbPut`);
    const questionCountAfterFailure = await evaluate(send, `(async () => (await import("/assets/store.js")).listQuestions().then((items) => items.length))()`);
    if (questionCountAfterFailure !== questionCountBeforeFailure) throw new Error("failed analysis left a temporary IndexedDB row behind");

    // P1-1: a failed re-analysis must keep the old result and follow-ups.
    await evaluate(send, `location.hash = "#/history"`);
    await waitFor(send, `document.querySelector(".history-card")`, 10000);
    await evaluate(send, `document.querySelector(".history-card").click()`);
    await waitFor(send, `document.querySelector('[data-action="edit-question"]')`, 10000);
    const oldResultHash = await evaluate(send, `location.hash`);
    const oldHeadline = await evaluate(send, `document.querySelector(".answer-main").innerText`);
    await evaluate(send, `document.querySelector('[data-action="edit-question"]').click()`);
    await waitFor(send, `document.querySelector("#question-text")`, 10000);
    await evaluate(send, `(() => {
      const el = document.querySelector("#question-text");
      el.value = "失败测试：请保留旧解析";
      el.dispatchEvent(new Event("input", { bubbles: true }));
      document.querySelector('[data-action="start-analysis"]').click();
    })()`);
    await waitFor(send, `location.hash === ${JSON.stringify(oldResultHash)} && document.querySelector(".answer-main")`, 15000);
    const reanalysisActual = await evaluate(send, `document.querySelector(".answer-main").innerText`);
    const reanalysisPreserved = reanalysisActual === oldHeadline;
    if (!reanalysisPreserved) throw new Error(`failed re-analysis did not preserve the old answer: old=${JSON.stringify(oldHeadline)} actual=${JSON.stringify(reanalysisActual)} hash=${await evaluate(send, "location.hash")}`);

    // R2-03: a successful edit/re-analysis creates a new answer revision and must not
    // carry the old answer's follow-up thread into the new record.
    await evaluate(send, `document.querySelector('[data-action="edit-question"]').click()`);
    await waitFor(send, `document.querySelector("#question-text")`, 10000);
    await evaluate(send, `(() => {
      const el = document.querySelector("#question-text");
      el.value = "成功重答测试：请生成新答案";
      el.dispatchEvent(new Event("input", { bubbles: true }));
      document.querySelector('[data-action="start-analysis"]').click();
    })()`);
    await waitFor(send, `document.querySelector(".answer-main") && document.body.innerText.includes("心肌梗死")`, 20000);
    const followUpsAfterEdit = await evaluate(send, `document.querySelectorAll(".follow-msg").length`);
    if (followUpsAfterEdit !== 0) throw new Error(`successful edit leaked old follow-ups: ${followUpsAfterEdit}`);

    await evaluate(send, `location.hash = "#/history"`);
    await waitFor(send, `document.querySelector(".history-card")`, 10000);
    const historyCount = await evaluate(send, `document.querySelectorAll(".history-card").length`);
    return { imported, afterCrop, resultRendered, followUsage, noUsageLabelHidden, oldRecordUsageHidden, failureRestored, dbFailureRestored: true, reanalysisPreserved, followUpsClearedOnEdit: followUpsAfterEdit === 0, historyCount };
  } finally {
    ws.close();
  }
}

const mock = await startMockDeepSeek();
const web = spawn(process.execPath, [join(WEB_DIR, "server.mjs")], {
  env: { ...process.env, PORT: String(WEB_PORT), HOST: "127.0.0.1" },
  stdio: "pipe"
});
let chrome;
const profileDir = mkdtempSync(join(tmpdir(), "medical-web-test-"));
let testError;
let result;
try {
  await waitHttp(`${BASE}api/health`);
  const chromePath = chromeCandidates().find((candidate) => existsSync(candidate));
  if (!chromePath) throw new Error("No Chrome/Edge/Chromium executable found; set CHROME_PATH");
  chrome = spawn(chromePath, [
    "--headless=new", "--disable-gpu", "--no-sandbox",
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profileDir}`, "about:blank"
  ], { stdio: "ignore" });
  result = await runBrowserChecks();
  console.log(JSON.stringify({ ok: true, ...result }, null, 2));
} catch (error) {
  testError = error;
}

const cleanupErrors = [];
try {
  await stopBrowser(chrome);
} catch (error) {
  cleanupErrors.push(error);
}
try {
  web.kill("SIGKILL");
  await waitForExit(web, 3000);
} catch (error) {
  cleanupErrors.push(error);
}
try {
  await new Promise((resolvePromise) => mock.close(resolvePromise));
} catch (error) {
  cleanupErrors.push(error);
}
try {
  await removeDirWithRetry(profileDir);
} catch (error) {
  cleanupErrors.push(error);
}

if (testError) {
  if (cleanupErrors.length > 0) {
    testError.message += `\n[browser cleanup failed] ${cleanupErrors.map((error) => error?.message || error).join("; ")}`;
    if (testError.cause === undefined) testError.cause = cleanupErrors[0];
  }
  throw testError;
}
if (cleanupErrors.length > 0) {
  throw new AggregateError(cleanupErrors, `browser test cleanup failed: ${cleanupErrors.map((error) => error?.message || error).join("; ")}`);
}
