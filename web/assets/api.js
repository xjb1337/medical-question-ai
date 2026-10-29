import { isOfficialDeepSeekBaseUrl, modelSupportsImages, getModelCapability } from "./store.js";

export class ApiError extends Error {
  constructor(message, status = 0, raw = "", code = "") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.raw = raw;
    this.code = code;
  }
}

export const MODEL_PRESETS = [
  {
    id: "deepseek-flash",
    label: "DeepSeek Flash",
    shortLabel: "Flash",
    description: "速度快，支持图片和文字，适合大多数题目。",
    supportsImages: true,
    badge: "推荐"
  },
  {
    id: "deepseek-v4-pro",
    label: "DeepSeek V4 Pro",
    shortLabel: "V4 Pro",
    description: "推理更深入，仅支持文字题，适合疑难文字题。",
    supportsImages: false,
    badge: "Pro · 不支持图片"
  }
];

export const ANSWER_MODES = [
  {
    id: "fast",
    label: "快速",
    shortLabel: "快速",
    description: "只给答案和核心依据，适合先看结论。",
    maxTokens: 3072,
    timeoutMs: 90000,
    thinking: "disabled",
    reasoningEffort: "",
    temperature: 0.3
  },
  {
    id: "standard",
    label: "标准",
    shortLabel: "标准",
    description: "完整选项判断与简明讲解，日常刷题推荐。",
    maxTokens: 8192,
    timeoutMs: 150000,
    thinking: "enabled",
    reasoningEffort: "low",
    temperature: null
  },
  {
    id: "detailed",
    label: "详细",
    shortLabel: "详细",
    description: "更完整的推理、鉴别与知识点，适合疑难题。",
    maxTokens: 16384,
    timeoutMs: 240000,
    thinking: "enabled",
    reasoningEffort: "high",
    temperature: null
  }
];

export const DEFAULT_ANSWER_MODE = "standard";
export const DEFAULT_MODEL = "deepseek-flash";

export function getAnswerMode(mode) {
  return ANSWER_MODES.find((item) => item.id === mode) || ANSWER_MODES.find((item) => item.id === DEFAULT_ANSWER_MODE);
}

export function getModelPreset(model) {
  return MODEL_PRESETS.find((item) => item.id === model) || null;
}

export function normalizeAnswerModeValue(value) {
  const id = String(value || "").trim().toLowerCase();
  return ANSWER_MODES.some((item) => item.id === id) ? id : DEFAULT_ANSWER_MODE;
}

export const SYSTEM_PROMPT = `你是「医学拍题助手」，帮助医学生理解医学课程题目。请严格依据题目文字和图片中能够看清的信息作答，不要编造教材、指南、页码、参考文献或病历信息。遇到图片模糊、题干缺失或信息不足时，必须明确写出不确定之处，不要把猜测写成事实。若输入内容不属于医学或医学课程相关范围，将 status 设为 out_of_scope。

只输出一个合法 JSON 对象，不要输出 Markdown 代码块，不要输出 JSON 之外的文字。字段如下：
{
  "status": "answered" | "needs_confirmation" | "out_of_scope",
  "question_type": "single" | "multiple" | "fill" | "short" | "unknown",
  "recognized_question": "识别出的完整题干；看不清的部分用[看不清]标注",
  "answer": ["A", "C"],
  "answer_text": "填空或简答题的文字答案；选择题可留空",
  "image_observations": [
    {"image_index": 1, "observation": "从这张图能确认什么", "uncertainty": "看不清或需要补充什么"}
  ],
  "option_analyses": [
    {"option": "A", "verdict": "正确|错误|不确定", "reason": "判断理由"}
  ],
  "reasoning_steps": [
    {"title": "步骤标题", "detail": "具体推理过程"}
  ],
  "key_points": ["需要掌握的知识点"],
  "pitfalls": ["容易混淆或答错的地方"],
  "confidence": 0.0,
  "warnings": ["需要用户核对的风险提示"]
}

规则：
1. 选择题把选项字母放进 answer 数组；多选题可以返回多个字母。
2. 填空题按空格顺序给出答案；简答题在 answer_text 中给出条理清晰的答案。
3. 有图片时，image_observations 必须逐图说明，且区分“题目明确给出”与“图片推断”。
4. confidence 使用 0 到 1 之间的小数；低置信度或信息缺失时降低数值并写入 warnings。
5. 信息不足时 status 使用 needs_confirmation，并在 answer_text 或 warnings 中说明需要补充什么。`;

export const FOLLOW_UP_PROMPT = `你是「医学拍题助手」，正在围绕一道医学课程题目回答学生追问。请结合原题、已有解析和追问历史，用简洁、准确的中文回答。不要编造教材、指南或页码；看不清或不确定时明确指出。不要输出 JSON，直接输出自然语言答案，可用短段落或编号列表。`;

export function buildChatPayload(mode = DEFAULT_ANSWER_MODE, options = {}) {
  const spec = getAnswerMode(mode);
  const model = options.model || DEFAULT_MODEL;
  const knownPreset = Boolean(getModelPreset(model)) &&
    isOfficialDeepSeekBaseUrl(options.baseUrl || "https://api.deepseek.com");
  const stream = options.stream !== false;
  const payload = {
    model,
    messages: Array.isArray(options.messages) ? options.messages : [],
    max_tokens: Number(options.maxTokens) || spec.maxTokens,
    stream
  };
  // 只有已知官方预设才发送思考参数与 response_format。自定义模型先按基础
  // Chat Completions 参数降级，避免把不支持的字段直接发给未知网关。
  if (knownPreset) {
    if (spec.thinking === "disabled") {
      payload.thinking = { type: "disabled" };
    } else {
      payload.thinking = { type: "enabled" };
      payload.reasoning_effort = spec.reasoningEffort;
    }
  }
  if (spec.temperature !== null && spec.temperature !== undefined) payload.temperature = spec.temperature;
  if (options.temperature !== undefined) payload.temperature = options.temperature;
  if (stream) payload.stream_options = { include_usage: true };
  else delete payload.stream_options;
  if (options.json && knownPreset) payload.response_format = { type: "json_object" };
  return payload;
}

/* ------------------------------------------------------------------ */
/* SSE parsing                                                         */
/* ------------------------------------------------------------------ */

function parseSseBlock(block) {
  const dataLines = [];
  let eventName = "";
  for (const rawLine of String(block || "").replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    if (!rawLine || rawLine.startsWith(":")) continue; // keep-alive / comment
    const colon = rawLine.indexOf(":");
    const field = colon === -1 ? rawLine : rawLine.slice(0, colon);
    let value = colon === -1 ? "" : rawLine.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "data") dataLines.push(value);
    else if (field === "event") eventName = value;
  }
  if (!dataLines.length) return null;
  const data = dataLines.join("\n");
  if (data === "[DONE]") return { done: true, data };
  return { data, event: eventName };
}

/**
 * 纯函数：把上一次残留 buffer 与新 chunk 合并，切出完整 SSE 事件。
 * 保留未结束的尾部，支持任意网络切块、CRLF、多行 data。
 */
export function parseSseChunk(buffer, chunk) {
  const combined = `${buffer || ""}${chunk || ""}`;
  // 先合并 CRLF；末尾孤立的 \r 保留到下一次 push，避免 CRLF 恰好被切成两半时
  // 提前制造出一个空事件边界，把 event/data 行拆散。
  const normalized = combined.replace(/\r\n/g, "\n").replace(/\r(?!$)/g, "\n");
  const blocks = normalized.split("\n\n");
  const rest = blocks.pop() ?? "";
  const events = [];
  for (const block of blocks) {
    const event = parseSseBlock(block);
    if (event) events.push(event);
  }
  return { buffer: normalized.endsWith("\n\n") ? "" : rest, events };
}

/**
 * 流式解析器：push 接受 string 或 Uint8Array。
 * 内部使用 TextDecoder 的 stream 模式，保证跨 chunk 的 UTF-8 汉字不会乱码。
 */
export function createSseParser(handlers = {}) {
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let done = false;

  const emit = (events) => {
    for (const event of events) {
      if (done) return;
      if (event.done) {
        done = true;
        handlers.onDone?.();
      } else {
        handlers.onEvent?.(event);
      }
    }
  };

  return {
    push(chunk) {
      if (done) return;
      const text = typeof chunk === "string" ? chunk : decoder.decode(chunk, { stream: true });
      if (!text) return;
      const parsed = parseSseChunk(buffer, text);
      buffer = parsed.buffer;
      emit(parsed.events);
    },
    flush(chunk) {
      if (done) return;
      let text = "";
      if (typeof chunk === "string") {
        text = decoder.decode() + chunk;
      } else if (chunk) {
        text = decoder.decode(chunk, { stream: false });
      } else {
        text = decoder.decode();
      }
      if (text) {
        const parsed = parseSseChunk(buffer, text);
        buffer = parsed.buffer;
        emit(parsed.events);
      }
      if (buffer.trim()) {
        const event = parseSseBlock(buffer);
        buffer = "";
        if (event) emit([event]);
      }
    },
    isDone() {
      return done;
    }
  };
}

/* ------------------------------------------------------------------ */
/* Incremental JSON field extraction                                    */
/* ------------------------------------------------------------------ */

function readJsonStringAt(text, index) {
  let i = index + 1;
  let escaped = false;
  while (i < text.length) {
    const ch = text[i];
    if (escaped) {
      escaped = false;
    } else if (ch === "\\") {
      escaped = true;
    } else if (ch === '"') {
      const raw = text.slice(index, i + 1);
      try {
        return { complete: true, value: JSON.parse(raw), end: i + 1 };
      } catch {
        return { complete: false, end: i + 1 };
      }
    }
    i += 1;
  }
  return { complete: false, end: text.length };
}

function scanJsonValueAt(text, index) {
  if (text[index] === '"') return readJsonStringAt(text, index);
  const stack = [];
  let i = index;
  let inString = false;
  let escaped = false;
  while (i < text.length) {
    const ch = text[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      i += 1;
      continue;
    }
    if (ch === '"') {
      inString = true;
      i += 1;
      continue;
    }
    if (ch === "{" || ch === "[") {
      stack.push(ch);
      i += 1;
      continue;
    }
    if (ch === "}" || ch === "]") {
      if (stack.length) {
        stack.pop();
        i += 1;
        if (!stack.length) return { complete: true, end: i };
        continue;
      }
      return { complete: true, end: i };
    }
    if (!stack.length && (ch === "," || ch === "}")) return { complete: true, end: i };
    i += 1;
  }
  return { complete: false, end: text.length };
}

/**
 * 扫描 JSON 文本中“已经完整结束”的顶层字段。未闭合的字符串 / 数组 / 对象不会返回。
 */
export function extractCompletedTopLevelFields(text) {
  const source = String(text || "");
  const start = source.indexOf("{");
  if (start < 0) return {};
  const fields = {};
  let i = start + 1;
  while (i < source.length) {
    while (i < source.length && /\s/.test(source[i])) i += 1;
    if (i >= source.length || source[i] === "}") break;
    if (source[i] !== '"') break;
    const key = readJsonStringAt(source, i);
    if (!key.complete) break;
    i = key.end;
    while (i < source.length && /\s/.test(source[i])) i += 1;
    if (source[i] !== ":") break;
    i += 1;
    while (i < source.length && /\s/.test(source[i])) i += 1;
    const valueStart = i;
    const value = scanJsonValueAt(source, i);
    if (!value.complete) break;
    const rawValue = source.slice(valueStart, value.end);
    try {
      fields[key.value] = JSON.parse(rawValue);
    } catch {
      break;
    }
    i = value.end;
    while (i < source.length && /\s/.test(source[i])) i += 1;
    if (source[i] === ",") {
      i += 1;
      continue;
    }
    break;
  }
  return fields;
}

/**
 * 增量预览：只有状态、题型、题干、答案、答案正文与 warnings 六个顶层字段
 * 全部完整结束，且答案通过基础校验时才回调。缺少 warnings 或字段半截时
 * 一律视为无效预览，避免把“还没收到的空数组”误认为模型确认无警告。
 */
export function createPreviewExtractor(selectedType = "auto") {
  let lastSignature = "";
  return (fullText) => {
    const fields = extractCompletedTopLevelFields(fullText);
    const required = ["status", "question_type", "recognized_question", "answer", "answer_text", "warnings"];
    if (!required.every((key) => Object.prototype.hasOwnProperty.call(fields, key))) return null;
    if (!Array.isArray(fields.warnings)) return null;
    const statusValue = normalizeStatus(fields.status);
    if (!statusValue) return null;
    const questionType = normalizeType(fields.question_type, selectedType || "auto");
    if (!questionType || questionType === "unknown") return null;
    const selected = String(selectedType || "auto").toLowerCase();
    if (["single", "multiple", "fill", "short"].includes(selected) && questionType !== selected) return null;
    const recognizedQuestion = toText(fields.recognized_question);
    const answer = normalizeAnswerValues(fields.answer);
    const answerText = toText(fields.answer_text);
    const optionKeys = optionKeysFrom(fields, recognizedQuestion);
    const reasons = validateAnswerForType(questionType, answer, answerText, optionKeys);
    if (statusValue === "answered" && !answer.length && !answerText) return null;
    if (statusValue === "answered" && reasons.length) return null;
    const warnings = fields.warnings.map(toText).filter(Boolean);
    const preview = {
      status: statusValue,
      questionType,
      recognizedQuestion,
      answer: statusValue === "answered" ? answer : [],
      answerText: statusValue === "answered" ? answerText : "",
      warnings,
      validated: true
    };
    const signature = JSON.stringify(preview);
    if (signature === lastSignature) return null;
    lastSignature = signature;
    return preview;
  };
}

/* ------------------------------------------------------------------ */
/* Shared request plumbing                                              */
/* ------------------------------------------------------------------ */

function buildHeaders(settings) {
  return {
    "Content-Type": "application/json",
    "X-API-Key": settings.apiKey || "",
    "X-API-Base": (settings.baseUrl || "https://api.deepseek.com").trim()
  };
}

function humanizeHttpError(status, upstreamMessage) {
  const suffix = upstreamMessage ? `：${String(upstreamMessage).slice(0, 220)}` : "";
  if (status === 400) return `请求格式未被接口接受${suffix}`;
  if (status === 401) return `API Key 无效或已过期${suffix}`;
  if (status === 402) return `接口账户余额不足${suffix}`;
  if (status === 403) return `当前 Key 没有调用权限${suffix}`;
  if (status === 404) return `接口地址或模型名称不正确${suffix}`;
  if (status === 413) return `图片或请求体过大${suffix}`;
  if (status === 422) return `请求参数不完整${suffix}`;
  if (status === 429) return `请求过于频繁，请稍后重试${suffix}`;
  if (status >= 500) return `模型服务暂时不可用（${status}）${suffix}`;
  return `接口请求失败（${status}）${suffix}`;
}

function extractContent(payload) {
  const content = payload?.choices?.[0]?.message?.content ?? payload?.choices?.[0]?.delta?.content;
  if (typeof content === "string" && content.trim()) return content;
  if (Array.isArray(content)) {
    const text = content.map((item) => item?.text || item?.content || "").join("");
    if (text.trim()) return text;
  }
  const errorMessage = payload?.error?.message;
  if (errorMessage) throw new ApiError(String(errorMessage), 0);
  throw new ApiError("模型没有返回可读取的内容。", 0);
}

function extractDeltaText(value) {
  if (typeof value === "string") return value;
  if (Array.isArray(value)) {
    return value.map((item) => typeof item === "string" ? item : item?.text || item?.content || "").join("");
  }
  return "";
}

// 仅在接口实际提供完整 usage 时返回；缺失或中断返回 null，不能伪造 0。
export function usageFromResponse(payload) {
  const usage = payload?.usage;
  if (!usage || typeof usage !== "object") return null;
  const promptTokens = Number(usage.prompt_tokens ?? usage.promptTokens);
  const completionTokens = Number(usage.completion_tokens ?? usage.completionTokens);
  let totalTokens = Number(usage.total_tokens ?? usage.totalTokens);
  if (!Number.isFinite(promptTokens) || !Number.isFinite(completionTokens)) return null;
  if (!Number.isFinite(totalTokens)) totalTokens = promptTokens + completionTokens;
  return { promptTokens, completionTokens, totalTokens };
}

export function formatUsageLabel(usage) {
  if (!usage || typeof usage !== "object") return "";
  const prompt = Number(usage.promptTokens);
  const completion = Number(usage.completionTokens);
  const total = Number(usage.totalTokens);
  if (![prompt, completion, total].every(Number.isFinite)) return "";
  const prefix = usage.simulated ? "模拟用量" : "Token 用量";
  return `${prefix}：输入 ${prompt.toLocaleString()} · 输出 ${completion.toLocaleString()} · 合计 ${total.toLocaleString()}`;
}

async function proxyRequest(endpoint, payload, settings, timeoutMs = 180000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(endpoint, {
      method: payload ? "POST" : "GET",
      headers: buildHeaders(settings),
      body: payload ? JSON.stringify(payload) : undefined,
      signal: controller.signal
    });
    const raw = await response.text();
    let data = null;
    try {
      data = raw ? JSON.parse(raw) : null;
    } catch {
      data = null;
    }
    if (!response.ok) {
      const upstreamMessage = data?.error?.message || data?.message || raw;
      throw new ApiError(humanizeHttpError(response.status, upstreamMessage), response.status, raw);
    }
    return data;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (error?.name === "AbortError") throw new ApiError("请求超时，请检查网络或稍后重试。", 0, "", "timeout");
    throw new ApiError("无法连接本地服务，请确认 web 服务仍在运行。", 0, String(error));
  } finally {
    clearTimeout(timer);
  }
}

async function requestChatStream(settings, payload, handlers = {}) {
  const mode = getAnswerMode(payload?._answerMode || DEFAULT_ANSWER_MODE);
  const timeoutMs = Number(handlers.timeoutMs) || mode.timeoutMs;
  const firstByteTimeoutMs = Number(handlers.firstByteTimeoutMs) || 45000;
  const progressTimeoutMs = Number(handlers.progressTimeoutMs) || 45000;
  const controller = new AbortController();
  let abortKind = "";
  let progressTimer = null;
  let overallTimer = null;
  let externalListener = null;

  const abortWith = (kind) => {
    if (controller.signal.aborted) return;
    abortKind = kind;
    controller.abort();
  };

  const external = handlers.signal;
  if (external) {
    if (external.aborted) {
      throw new ApiError("已取消本次解析。", 0, "", "cancelled");
    }
    externalListener = () => abortWith("cancelled");
    external.addEventListener("abort", externalListener, { once: true });
  }

  let firstByteSeen = false;
  const markFirstByte = () => {
    firstByteSeen = true;
    armProgressTimer();
  };
  const armProgressTimer = () => {
    if (progressTimer) clearTimeout(progressTimer);
    progressTimer = setTimeout(
      () => abortWith(firstByteSeen ? "no_progress" : "first_byte_timeout"),
      progressTimeoutMs
    );
  };
  const markProgress = () => {
    markFirstByte();
    armProgressTimer();
  };

  armProgressTimer();
  overallTimer = setTimeout(() => abortWith("timeout"), timeoutMs);

  try {
    handlers.onStage?.("waiting");
    const requestPayload = { ...payload };
    delete requestPayload._answerMode;
    const response = await fetch("/api/deepseek/chat", {
      method: "POST",
      headers: buildHeaders(settings),
      body: JSON.stringify(requestPayload),
      signal: controller.signal
    });
    if (!response.ok) {
      const raw = await response.text();
      let data = null;
      try {
        data = raw ? JSON.parse(raw) : null;
      } catch {
        data = null;
      }
      const upstreamMessage = data?.error?.message || data?.message || raw;
      throw new ApiError(humanizeHttpError(response.status, upstreamMessage), response.status, raw);
    }

    const contentType = String(response.headers.get("content-type") || "").toLowerCase();
    if (!contentType.includes("text/event-stream")) {
      const raw = await response.text();
      markProgress();
      let data;
      try {
        data = raw ? JSON.parse(raw) : null;
      } catch {
        throw new ApiError("接口返回了无法解析的响应，请重试。", response.status, raw);
      }
      if (data?.error) throw new ApiError(String(data.error.message || data.error), response.status, raw);
      const content = extractContent(data);
      if (!content.trim()) {
        const reason = data?.choices?.[0]?.finish_reason || "";
        throw new ApiError(
          reason === "length" ? "输出未完成（达到长度上限），请切换详细模式或重试。" : "模型没有返回可读取的内容。",
          response.status,
          raw,
          reason === "length" ? "length" : "empty_content"
        );
      }
      handlers.onStage?.("receiving");
      handlers.onContentDelta?.(content, content);
      return {
        content,
        usage: usageFromResponse(data),
        finishReason: data?.choices?.[0]?.finish_reason ?? "",
        model: data?.model || payload.model,
        nonStream: true
      };
    }

    let content = "";
    let reasoning = "";
    let usage = null;
    let finishReason = "";
    let sawDone = false;
    const parser = createSseParser({
      onDone() {
        sawDone = true;
      },
      onEvent(event) {
        let data;
        try {
          data = JSON.parse(event.data);
        } catch {
          return;
        }
        if (data?.error) {
          throw new ApiError(String(data.error.message || data.error), 0, event.data);
        }
        const choice = data?.choices?.[0];
        const delta = choice?.delta || choice?.message || {};
        const reasoningPiece = extractDeltaText(delta.reasoning_content ?? choice?.reasoning_content);
        if (reasoningPiece) {
          reasoning += reasoningPiece;
          markProgress();
          handlers.onStage?.("thinking");
          handlers.onReasoningDelta?.(reasoning);
        }
        const piece = extractDeltaText(delta.content);
        if (piece) {
          if (!content) handlers.onStage?.("receiving");
          content += piece;
          markProgress();
          handlers.onContentDelta?.(content, piece);
        }
        const parsedUsage = usageFromResponse(data);
        if (parsedUsage) usage = parsedUsage;
        if (choice?.finish_reason) finishReason = choice.finish_reason;
      }
    });

    if (!response.body) {
      const raw = await response.text();
      parser.push(raw);
    } else {
      const reader = response.body.getReader();
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          parser.push(value);
          // [DONE] 是应用层终止信号：立即停止等待 HTTP EOF，取消 reader，
          // 避免上游保持连接时一直挂到 no_progress / 整体超时。
          if (parser.isDone()) {
            try { await reader.cancel("sse_done"); } catch { /* noop */ }
            try { controller.abort(); } catch { /* noop */ }
            break;
          }
        }
      } finally {
        try { reader.releaseLock(); } catch { /* noop */ }
      }
    }
    parser.flush();
    if (!sawDone) {
      throw new ApiError(
        "流式响应在收到 [DONE] 前结束，内容可能不完整，请重试。",
        0,
        content || reasoning,
        "incomplete_stream"
      );
    }
    if (!content.trim()) {
      if (finishReason === "length") {
        throw new ApiError("输出未完成（达到长度上限），请切换详细模式或重试。", 0, "", "length");
      }
      throw new ApiError("模型没有返回可读取的内容。", 0, reasoning, "empty_content");
    }
    return {
      content,
      usage,
      finishReason,
      model: payload.model,
      nonStream: false
    };
  } catch (error) {
    if (error instanceof ApiError) {
      if (abortKind === "cancelled" || error.code === "cancelled") {
        throw new ApiError("已取消本次解析。", 0, "", "cancelled");
      }
      throw error;
    }
    if (abortKind === "first_byte_timeout") {
      throw new ApiError("等待模型响应超过 45 秒，请稍后重试。", 0, "", "first_byte_timeout");
    }
    if (abortKind === "no_progress") {
      throw new ApiError("模型响应中断超过 45 秒，已停止等待。", 0, "", "no_progress");
    }
    if (abortKind === "timeout") {
      throw new ApiError(`解析超时（${Math.round(timeoutMs / 1000)} 秒），可切换到快速模式后重试。`, 0, "", "timeout");
    }
    if (abortKind === "cancelled" || error?.name === "AbortError") {
      throw new ApiError("已取消本次解析。", 0, "", "cancelled");
    }
    throw new ApiError("解析请求失败，请检查网络后重试。", 0, String(error));
  } finally {
    if (progressTimer) clearTimeout(progressTimer);
    if (overallTimer) clearTimeout(overallTimer);
    if (external && externalListener) external.removeEventListener("abort", externalListener);
    void controller;
  }
}

/* ------------------------------------------------------------------ */
/* Analysis                                                             */
/* ------------------------------------------------------------------ */

function toArray(value) {
  if (Array.isArray(value)) return value;
  if (value === null || value === undefined || value === "") return [];
  return [value];
}

function normalizeAnswerItem(value) {
  if (typeof value !== "string") value = toText(value?.value ?? value?.option ?? value);
  const text = String(value || "").trim();
  if (!text) return "";
  // 选项答案既接受 "A"，也兼容 "A. 急性心肌梗死" / "A、C" 这类模型输出。
  const choice = text.match(/^([A-Za-z])(?=$|[\s.、．:：)）])/);
  return choice ? choice[1].toUpperCase() : text;
}

function normalizeAnswerValues(value) {
  const result = [];
  for (const item of toArray(value)) {
    if (typeof item === "string" && /[A-Za-z]/.test(item) && /^[A-Za-z](?:\s*[,，、;；/]\s*[A-Za-z])+$/.test(item)) {
      result.push(...item.split(/[,，、;；/]/).map((part) => part.trim().toUpperCase()).filter(Boolean));
    } else {
      const normalized = normalizeAnswerItem(item);
      if (normalized) result.push(normalized);
    }
  }
  return [...new Set(result)];
}

function normalizeConfidenceValue(value) {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "string") {
    const word = value.trim().toLowerCase();
    if (["high", "高", "较高"].includes(word)) return 0.9;
    if (["medium", "mid", "中", "中等"].includes(word)) return 0.65;
    if (["low", "低", "较低"].includes(word)) return 0.35;
    if (word.endsWith("%")) {
      const percent = Number(word.slice(0, -1));
      return Number.isFinite(percent) ? Math.max(0, Math.min(1, percent / 100)) : null;
    }
  }
  const number = Number(value);
  if (!Number.isFinite(number)) return null;
  if (number > 1 && number <= 100) return Math.max(0, Math.min(1, number / 100));
  if (number >= 0 && number <= 1) return number;
  return null;
}

function optionKeysFrom(parsed, recognizedQuestion = "") {
  const keys = new Set();
  for (const item of toArray(parsed?.option_analyses ?? parsed?.optionAnalyses)) {
    const key = toText(item?.option ?? item?.key).toUpperCase();
    if (/^[A-Z]$/.test(key)) keys.add(key);
  }
  const text = String(recognizedQuestion || "");
  for (const match of text.matchAll(/(?:^|\n)\s*([A-Z])[\s.、．:：)）]/g)) keys.add(match[1].toUpperCase());
  return keys;
}

function validateAnswerForType(type, answer, answerText, optionKeys) {
  const reasons = [];
  if (type === "single") {
    if (answer.length !== 1) reasons.push("单选题答案必须恰好包含一个选项字母");
    else if (!/^[A-Z]$/.test(answer[0])) reasons.push("单选题答案不是有效选项字母");
  } else if (type === "multiple") {
    if (answer.length < 2) reasons.push("多选题答案应包含两个或以上选项字母");
    else if (answer.some((item) => !/^[A-Z]$/.test(item))) reasons.push("多选题答案包含非选项内容");
  } else if (type === "fill" || type === "short") {
    if (!answerText) reasons.push("主观题缺少答案正文");
  }
  if (optionKeys?.size && answer.length) {
    const missing = answer.filter((item) => !optionKeys.has(item));
    if (missing.length) reasons.push(`答案选项 ${missing.join("、")} 不在题目识别出的选项中`);
  }
  return reasons;
}

function toText(value) {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number") return String(value);
  return "";
}

function parseJsonResponse(text) {
  const cleaned = String(text || "").trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  try {
    return JSON.parse(cleaned);
  } catch {
    const start = cleaned.indexOf("{");
    const end = cleaned.lastIndexOf("}");
    if (start >= 0 && end > start) {
      return JSON.parse(cleaned.slice(start, end + 1));
    }
    throw new ApiError("模型返回的内容不是合法 JSON，请重试。", 0, cleaned);
  }
}

function normalizeType(value, fallback = "unknown") {
  const type = String(value || "").toLowerCase().trim();
  if (["single", "multiple", "fill", "short", "unknown"].includes(type)) return type;
  if (["单选", "单选题"].includes(type)) return "single";
  if (["多选", "多选题"].includes(type)) return "multiple";
  if (["填空", "填空题"].includes(type)) return "fill";
  if (["简答", "简答题"].includes(type)) return "short";
  return fallback === "auto" ? "unknown" : fallback || "unknown";
}

function normalizeStatus(value) {
  const status = String(value || "").toLowerCase().trim();
  if (["answered", "needs_confirmation", "out_of_scope"].includes(status)) return status;
  return "";
}

export function parseAnalysis(rawText, selectedType = "auto") {
  const parsed = parseJsonResponse(rawText);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new ApiError("模型返回的 JSON 结构不正确，请重试。", 0, String(rawText || ""));
  }
  const answer = normalizeAnswerValues(parsed.answer ?? parsed.answers);
  const answerText = toText(parsed.answer_text ?? parsed.answerText ?? parsed.explanation_answer);
  const selected = String(selectedType || "auto").toLowerCase();
  const rawQuestionType = parsed.question_type ?? parsed.questionType ?? parsed.type;
  let questionType = normalizeType(rawQuestionType, selected);
  if (questionType === "unknown") {
    if (answer.length) questionType = answer.length > 1 ? "multiple" : "single";
    else if (answerText) questionType = "short";
  }

  const malformed = [];
  if (selected !== "auto" && selected !== "unknown" && !toText(rawQuestionType)) {
    malformed.push("question_type 字段缺失");
  }
  let status = normalizeStatus(parsed.status);
  if (!status) {
    status = "needs_confirmation";
    malformed.push("status 字段缺失或不是 answered/needs_confirmation/out_of_scope");
  }
  const warnings = toArray(parsed.warnings ?? parsed.warning).map(toText).filter(Boolean);
  const rawWarnings = parsed.warnings ?? parsed.warning;
  if (!Object.prototype.hasOwnProperty.call(parsed, "warnings") && !Object.prototype.hasOwnProperty.call(parsed, "warning")) {
    malformed.push("warnings 字段缺失");
  } else if (rawWarnings !== undefined && rawWarnings !== null && typeof rawWarnings !== "string" && !Array.isArray(rawWarnings)) {
    malformed.push("warnings 字段不是数组");
  }
  const optionKeys = optionKeysFrom(parsed, toText(parsed.recognized_question ?? parsed.recognizedQuestion ?? parsed.question));
  const reasons = [...validateAnswerForType(questionType, answer, answerText, optionKeys)];
  if (selected !== "auto" && selected !== "unknown" && questionType !== selected) {
    reasons.push(`模型识别的题型（${questionType}）与所选题型（${selected}）不一致`);
  }
  if (status === "answered" && !answer.length && !answerText) reasons.push("回答状态为 answered 但没有答案或解析正文");
  if (status === "answered" && (reasons.length || malformed.length)) status = "needs_confirmation";
  if (malformed.length) status = "needs_confirmation";
  if (status === "needs_confirmation") {
    warnings.unshift("当前信息不足以给出确定答案，请补充题干或清晰图片后重试。");
    if (reasons.length) warnings.push(...reasons);
    if (malformed.length) warnings.push(`模型响应字段异常：${malformed.join("；")}`);
  }
  if (status === "out_of_scope") {
    warnings.unshift("这道题看起来不属于医学课程范围，系统不会给出医学解析。");
  }

  const confidence = normalizeConfidenceValue(parsed.confidence ?? parsed.confidence_score);
  if (confidence !== null && confidence > 0 && confidence < 0.55 && status === "answered") {
    warnings.push("模型把握程度较低，请以教材和课堂标准答案核对。");
  }

  const imageObservations = toArray(parsed.image_observations ?? parsed.imageObservations).map((item, index) => ({
    imageIndex: Number(item?.image_index ?? item?.imageIndex ?? index + 1) || index + 1,
    observation: toText(item?.observation ?? item?.text ?? (typeof item === "string" ? item : "")),
    uncertainty: toText(item?.uncertainty ?? item?.uncertain)
  })).filter((item) => item.observation || item.uncertainty);

  const optionAnalyses = toArray(parsed.option_analyses ?? parsed.optionAnalyses).map((item, index) => ({
    option: toText(item?.option ?? item?.key ?? String.fromCharCode(65 + index)).toUpperCase(),
    verdict: toText(item?.verdict ?? item?.status ?? (typeof item === "string" ? item : "不确定")),
    reason: toText(item?.reason ?? item?.detail)
  })).filter((item) => item.option || item.reason);

  const reasoningSteps = toArray(parsed.reasoning_steps ?? parsed.reasoningSteps).map((item, index) => ({
    title: toText(item?.title ?? item?.step ?? `步骤 ${index + 1}`),
    detail: toText(item?.detail ?? item?.description ?? item)
  })).filter((item) => item.title || item.detail);

  return {
    status,
    questionType,
    recognizedQuestion: toText(parsed.recognized_question ?? parsed.recognizedQuestion ?? parsed.question),
    answer: status === "answered" ? answer : [],
    answerText: status === "answered" ? answerText : "",
    imageObservations,
    optionAnalyses,
    reasoningSteps,
    keyPoints: toArray(parsed.key_points ?? parsed.keyPoints).map(toText).filter(Boolean),
    pitfalls: toArray(parsed.pitfalls).map(toText).filter(Boolean),
    confidence,
    warnings: [...new Set(warnings)],
    usage: usageFromResponse(parsed)
  };
}

function buildPromptText(draft) {
  const typeLabels = { auto: "自动识别", single: "单选题", multiple: "多选题", fill: "填空题", short: "简答题" };
  return [
    `【用户选择题型】${typeLabels[draft.type] || "自动识别"}`,
    `【题目文字】${draft.text?.trim() || "（没有额外文字，请以图片为准）"}`,
    `【图片数量】${draft.images?.length || 0}`,
    "",
    "请按系统要求判断题型、识别题干，并返回严格 JSON。"
  ].join("\n");
}

function buildUserContent(draft) {
  const content = [{ type: "text", text: buildPromptText(draft) }];
  for (const image of draft.images || []) {
    content.push({ type: "image_url", image_url: { url: image.dataUrl } });
  }
  return content;
}

export function analyzeQuestionStream(settings, draft, handlers = {}) {
  if (settings.demoMode) {
    const result = makeDemoAnalysis(draft);
    result.mode = normalizeAnswerModeValue(settings.answerMode);
    result.model = settings.model || DEFAULT_MODEL;
    result.finishReason = "stop";
    handlers.onStage?.("validating");
    handlers.onPreview?.({
      status: result.status,
      questionType: result.questionType,
      answer: result.answer,
      answerText: result.answerText,
      warnings: result.warnings,
      validated: true
    });
    handlers.onContentDelta?.(JSON.stringify(result), JSON.stringify(result));
    return Promise.resolve(result);
  }
  return (async () => {
    if (!settings.apiKey) throw new ApiError("尚未配置 API Key，请先到设置页填写。", 0);
    if ((draft.images?.length || 0) > 0 && !modelSupportsImages(settings.model || DEFAULT_MODEL, settings)) {
      throw new ApiError("当前模型未声明支持图片，请切换到 DeepSeek Flash，或在高级设置中确认该模型支持图片。", 0, "", "unsupported_image");
    }
    const mode = normalizeAnswerModeValue(settings.answerMode);
    const capability = getModelCapability(settings.model || DEFAULT_MODEL, settings);
    const payload = buildChatPayload(mode, {
      model: settings.model || DEFAULT_MODEL,
      baseUrl: settings.baseUrl,
      messages: [
        { role: "system", content: SYSTEM_PROMPT },
        { role: "user", content: buildUserContent(draft) }
      ],
      json: true
    });
    // 自定义接口能力未知：默认走非流式，避免把不支持 SSE 的代理误标为支持。
    if (!capability.known) payload.stream = false;
    if (!payload.stream) delete payload.stream_options;
    payload._answerMode = mode;
    const previewer = createPreviewExtractor(draft.type);
    const outcome = await requestChatStream(settings, payload, {
      signal: handlers.signal,
      timeoutMs: getAnswerMode(mode).timeoutMs,
      onStage: handlers.onStage,
      onContentDelta(fullText) {
        handlers.onDelta?.(fullText);
        const preview = previewer(fullText);
        if (preview) handlers.onPreview?.(preview);
      },
      onReasoningDelta: handlers.onReasoningDelta
    });
    if (!outcome.finishReason) {
      throw new ApiError("模型响应缺少 finish_reason，无法确认答案是否完整。", 0, outcome.content, "missing_finish_reason");
    }
    if (outcome.finishReason === "length") {
      throw new ApiError("输出未完成（达到长度上限），请切换详细模式或重试。", 0, "", "length");
    }
    if (outcome.finishReason !== "stop") {
      throw new ApiError(`模型输出未正常结束（${outcome.finishReason}），请重试。`, 0, outcome.content, "incomplete_finish");
    }
    handlers.onStage?.("validating");
    const result = parseAnalysis(outcome.content, draft.type);
    result.usage = outcome.usage;
    result.finishReason = outcome.finishReason;
    result.mode = mode;
    result.model = outcome.model || settings.model || DEFAULT_MODEL;
    handlers.onComplete?.(result);
    return result;
  })();
}

// 兼容旧调用：非流式包装。
export async function analyzeQuestion(settings, draft) {
  return analyzeQuestionStream(settings, draft, {});
}

function questionContext(question) {
  const result = question.result || {};
  const answers = Array.isArray(result.answer) ? result.answer.filter(Boolean) : (result.answer ? [result.answer] : []);
  const answer = answers.length ? answers.join("、") : result.answerText || "（暂无确定答案）";
  const steps = (result.reasoningSteps || []).map((step) => `${step.title}：${step.detail}`).join("\n");
  return [
    `【原题】${question.questionText || "（图片题）"}`,
    `【识别题干】${result.recognizedQuestion || "未识别"}`,
    `【当前答案】${answer}`,
    result.keyPoints?.length ? `【关键点】${result.keyPoints.join("；")}` : "",
    steps ? `【已有解析】\n${steps}` : ""
  ].filter(Boolean).join("\n");
}

export function askFollowUpStream(settings, question, userText, images, history, handlers = {}) {
  if (settings.demoMode) {
    const text = makeDemoFollowUp(question, userText);
    const usage = { promptTokens: 320, completionTokens: 180, totalTokens: 500, simulated: true };
    handlers.onContentDelta?.(text, text);
    return Promise.resolve({ text, usage, demo: true, finishReason: "stop" });
  }
  return (async () => {
    if (!settings.apiKey) throw new ApiError("尚未配置 API Key，请先到设置页填写。", 0);
    if ((images?.length || 0) > 0 && !modelSupportsImages(settings.model || DEFAULT_MODEL, settings)) {
      throw new ApiError("当前模型未声明支持图片，请切换到 DeepSeek Flash，或在高级设置中确认该模型支持图片。", 0, "", "unsupported_image");
    }
    const historyText = (history || []).slice(-8).map((item) => `${item.role === "user" ? "学生" : "助手"}：${item.text}`).join("\n");
    const text = [
      questionContext(question),
      historyText ? `【此前追问】\n${historyText}` : "",
      `【本次追问】${userText.trim() || "请结合图片解释这道题的关键步骤。"}`
    ].filter(Boolean).join("\n\n");
    let content = text;
    if (images?.length) {
      content = [{ type: "text", text }, ...images.map((image) => ({ type: "image_url", image_url: { url: image.dataUrl } }))];
    }
    const mode = normalizeAnswerModeValue(settings.answerMode);
    const capability = getModelCapability(settings.model || DEFAULT_MODEL, settings);
    const payload = buildChatPayload(mode, {
      model: settings.model || DEFAULT_MODEL,
      baseUrl: settings.baseUrl,
      messages: [
        { role: "system", content: FOLLOW_UP_PROMPT },
        { role: "user", content }
      ]
    });
    if (!capability.known) payload.stream = false;
    if (!payload.stream) delete payload.stream_options;
    payload._answerMode = mode;
    const outcome = await requestChatStream(settings, payload, {
      signal: handlers.signal,
      timeoutMs: getAnswerMode(mode).timeoutMs,
      onStage: handlers.onStage,
      onContentDelta: handlers.onDelta,
      onReasoningDelta: handlers.onReasoningDelta
    });
    if (!outcome.content.trim()) throw new ApiError("模型没有返回可读取的内容。", 0, "", "empty_content");
    if (!outcome.finishReason) {
      throw new ApiError("模型响应缺少 finish_reason，无法确认回答是否完整。", 0, outcome.content, "missing_finish_reason");
    }
    if (outcome.finishReason !== "stop") {
      throw new ApiError(`模型输出未正常结束（${outcome.finishReason}），请重试。`, 0, outcome.content, "incomplete_finish");
    }
    return {
      text: outcome.content.trim(),
      usage: outcome.usage,
      demo: false,
      finishReason: outcome.finishReason
    };
  })();
}

export async function askFollowUp(settings, question, userText, images, history) {
  return askFollowUpStream(settings, question, userText, images, history, {});
}

export async function testConnection(settings) {
  if (settings.demoMode) return { demo: true, message: "当前处于演示模式，不需要连接真实接口。", models: [] };
  if (!settings.apiKey) throw new ApiError("请先填写 API Key。", 0);
  const response = await proxyRequest("/api/deepseek/models", null, settings, 30000);
  const list = Array.isArray(response?.data) ? response.data : null;
  if (!list) throw new ApiError("模型列表响应格式不正确，请检查接口地址。", 0, JSON.stringify(response || {}));
  const models = list.map((item) => String(item?.id || "")).filter(Boolean);
  const selected = settings.model || DEFAULT_MODEL;
  if (!models.includes(selected)) {
    throw new ApiError(`连接成功，但模型列表中不存在当前选择的 ${selected}。`, 0, JSON.stringify(response));
  }
  return { demo: false, message: `连接成功，API Key 可用（可用模型 ${models.length} 个）。`, models };
}

export async function verifyModel(settings) {
  if (settings.demoMode) {
    return { demo: true, message: "演示模式下使用本地模拟结果，无需真实验证。", model: settings.model || DEFAULT_MODEL };
  }
  if (!settings.apiKey) throw new ApiError("请先填写 API Key。", 0);
  const mode = "fast";
  const payload = buildChatPayload(mode, {
    model: settings.model || DEFAULT_MODEL,
    baseUrl: settings.baseUrl,
    messages: [{ role: "user", content: "只回复两个字：可用" }],
    stream: false,
    maxTokens: 32
  });
  const response = await proxyRequest("/api/deepseek/chat", payload, settings, 60000);
  const finishReason = response?.choices?.[0]?.finish_reason;
  if (!finishReason) throw new ApiError("模型响应缺少 finish_reason，验证未通过。", 0, JSON.stringify(response));
  if (finishReason !== "stop") {
    throw new ApiError(`模型返回未正常结束（${finishReason}），验证未通过。`, 0, JSON.stringify(response));
  }
  const text = extractContent(response).trim();
  if (!text) throw new ApiError("模型返回为空，验证未通过。", 0, JSON.stringify(response), "empty_content");
  return {
    demo: false,
    message: `模型验证通过，返回：${text.slice(0, 40)}`,
    model: settings.model || DEFAULT_MODEL,
    finishReason,
    usage: usageFromResponse(response)
  };
}

/* ------------------------------------------------------------------ */
/* Demo data                                                            */
/* ------------------------------------------------------------------ */

function makeDemoAnalysis(draft) {
  const text = (draft.text || "").trim();
  const optionMatches = [...text.matchAll(/(?:^|\n)\s*([A-F])[.、)]\s*([^\n]+)/g)];
  const isMultiple = draft.type === "multiple" || /多选|选择所有|哪些/.test(text);
  const type = draft.type === "auto" ? (optionMatches.length ? (isMultiple ? "multiple" : "single") : "short") : draft.type;
  const usedImages = draft.images?.length || 0;
  const isCardiacSample = /胸骨后压榨性疼痛/.test(text) && /Ⅱ、Ⅲ、aVF/.test(text);
  let answer = isCardiacSample ? ["A"] : ["B"];
  let answerText = "";
  let recognized = text || "（示例图片题）患者突发胸痛，心电图提示 ST 段改变，最可能的诊断是？";
  if (type === "fill") {
    answer = [];
    answerText = "左心室前壁";
  } else if (type === "short") {
    answer = [];
    answerText = "先稳定生命体征，再结合病史、体格检查和必要辅助检查明确诊断；处理时应优先排除危及生命的急症。";
  } else if (isMultiple) {
    answer = ["A", "C"];
  }
  const optionAnalyses = optionMatches.length
    ? optionMatches.slice(0, 6).map((match) => ({
      option: match[1],
      verdict: answer.includes(match[1]) ? "正确" : "错误",
      reason: answer.includes(match[1]) ? "与题干关键信息一致，为本题应选项。" : "与题干关键条件不符，不能作为答案。"
    }))
    : type === "single" || type === "multiple"
      ? [
        { option: "A", verdict: "错误", reason: "该选项描述与题干关键信息不一致。" },
        { option: "B", verdict: "正确", reason: "符合题干中的核心临床表现与判断依据。" },
        { option: "C", verdict: "错误", reason: "概念相近，但缺少题干要求的关键条件。" },
        { option: "D", verdict: "不确定", reason: "题干信息不足，需要结合教材或补充材料核对。" }
      ]
      : [];
  return {
    status: "answered",
    questionType: type,
    recognizedQuestion: recognized,
    answer,
    answerText,
    imageObservations: Array.from({ length: usedImages }, (_, index) => ({
      imageIndex: index + 1,
      observation: index === 0 ? "图片主体清晰，可辨认题干和主要选项；边缘有一处反光。" : "图片内容已读取，关键信息基本可见。",
      uncertainty: index === 0 ? "右下角文字略模糊，建议结合原题核对。" : ""
    })),
    optionAnalyses,
    reasoningSteps: [
      { title: "提取题干线索", detail: "先定位患者的主要症状、体征和检查结果，区分题目明确给出的信息与需要推断的部分。" },
      { title: "排除干扰项", detail: "逐项对照题干条件，先排除与核心表现不一致的选项，再比较剩余选项的适用范围。" },
      { title: "回到教材概念", detail: "用课程中的定义和诊断标准复核答案，注意相似疾病之间的关键鉴别点。" }
    ],
    keyPoints: ["先看题干中的时间、部位和伴随症状", "选择题要逐项核对，不只凭关键词作答", "不确定的图片细节要回到原题确认"],
    pitfalls: ["不要仅凭单个关键词直接选答案", "相似选项之间要比较限定条件"],
    confidence: 0.86,
    warnings: ["当前为演示模式生成的本地模拟结果，不代表真实模型判断。", "实际作答请结合教材、课堂笔记和标准答案核对。"],
    usage: { promptTokens: 1280, completionTokens: 760, totalTokens: 2040, simulated: true }
  };
}

function makeDemoFollowUp(question, prompt) {
  const focused = prompt.trim() || "这道题的关键判断思路";
  return `这是演示模式下的追问回答。\n\n针对「${focused}」，建议先回到题干中的核心线索：${question.result?.recognizedQuestion || question.questionText || "题目信息"}。\n\n复习时可以按三步检查：\n1. 先确认题目问的是诊断、机制还是处理；\n2. 再对照教材中的定义和鉴别点逐项排除；\n3. 最后把答案还原到题干条件中验证是否自洽。\n\n真实测试时关闭演示模式并配置 API Key，就能得到模型针对这道题的完整回答。`;
}
