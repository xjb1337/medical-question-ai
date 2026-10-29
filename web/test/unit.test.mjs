import test from "node:test";
import assert from "node:assert/strict";

import {
  buildChatPayload,
  createSseParser,
  parseSseChunk,
  createPreviewExtractor,
  parseAnalysis,
  analyzeQuestionStream,
  askFollowUpStream,
  ANSWER_MODES,
  MODEL_PRESETS
} from "../assets/api.js";
import {
  DEFAULT_SETTINGS,
  SETTINGS_VERSION,
  isOfficialDeepSeekBaseUrl,
  migrateSettings,
  modelSupportsImages,
  getModelCapability
} from "../assets/store.js";

function collectParser() {
  const events = [];
  let doneCount = 0;
  const parser = createSseParser({
    onEvent: (event) => events.push(event),
    onDone: () => {
      doneCount += 1;
    }
  });
  return { parser, events, getDoneCount: () => doneCount };
}

test("parseSseChunk 能跨任意 JSON 分片拼接出完整 data", () => {
  const first = parseSseChunk("", 'data: {"choices":[{"delta":{"content":"你');
  assert.equal(first.events.length, 0);
  const second = parseSseChunk(first.buffer, '好"}}]}\n\n');
  assert.equal(second.events.length, 1);
  const payload = JSON.parse(second.events[0].data);
  assert.equal(payload.choices[0].delta.content, "你好");
  assert.equal(second.buffer, "");
});

test("createSseParser 支持任意 chunk 边界、CRLF 和逐字节 UTF-8 汉字", () => {
  const text = [
    ": keep-alive",
    "",
    'event: message',
    'data: {"choices":[{"delta":{"content":"胸骨后压榨性疼痛"}}]}',
    "",
    "data: [DONE]",
    "",
    ""
  ].join("\r\n");
  const bytes = new TextEncoder().encode(text);
  const collector = collectParser();
  for (const byte of bytes) collector.parser.push(Uint8Array.of(byte));
  collector.parser.flush();

  assert.equal(collector.events.length, 1);
  assert.equal(collector.events[0].event, "message");
  assert.equal(JSON.parse(collector.events[0].data).choices[0].delta.content, "胸骨后压榨性疼痛");
  assert.equal(collector.getDoneCount(), 1);
});

test("多行 data 按 SSE 规则以换行拼接，keep-alive 注释被忽略", () => {
  const collector = collectParser();
  collector.parser.push(': ping\n\n');
  collector.parser.push('data: {"a":\n');
  collector.parser.push('data: 1}\n\n');
  collector.parser.push("data: [DONE]\n\n");
  collector.parser.flush();
  assert.equal(collector.events.length, 1);
  assert.deepEqual(JSON.parse(collector.events[0].data), { a: 1 });
  assert.equal(collector.getDoneCount(), 1);
});

test("[DONE] 只触发一次，之后的数据不再派发", () => {
  const collector = collectParser();
  collector.parser.push("data: [DONE]\n\n");
  collector.parser.push('data: {"late":true}\n\n');
  collector.parser.flush();
  assert.equal(collector.getDoneCount(), 1);
  assert.equal(collector.events.length, 0);
});

test("parseSseChunk 保留未闭合尾部，不把半个 JSON 当事件", () => {
  const result = parseSseChunk("", 'data: {"status":"answered","answer":["A');
  assert.equal(result.events.length, 0);
  assert.match(result.buffer, /answer/);
});

test("三个模式的请求参数符合上限、思考开关与 include_usage", () => {
  const cases = [
    ["fast", 3072, { type: "disabled" }, undefined],
    ["standard", 8192, { type: "enabled" }, "low"],
    ["detailed", 16384, { type: "enabled" }, "high"]
  ];
  for (const [mode, maxTokens, thinking, effort] of cases) {
    const payload = buildChatPayload(mode, { model: "deepseek-flash", messages: [] });
    assert.equal(payload.max_tokens, maxTokens);
    assert.deepEqual(payload.thinking, thinking);
    assert.equal(payload.reasoning_effort, effort);
    assert.equal(payload.stream, true);
    assert.deepEqual(payload.stream_options, { include_usage: true });
  }
  assert.equal(ANSWER_MODES.length, 3);
  assert.equal(MODEL_PRESETS.length, 2);
  assert.equal(modelSupportsImages("deepseek-flash"), true);
  assert.equal(modelSupportsImages("deepseek-v4-pro"), false);
});

test("Pro 模型遇到图片会在发请求前被拦截", async () => {
  await assert.rejects(
    analyzeQuestionStream(
      { apiKey: "sk-test", model: "deepseek-v4-pro", baseUrl: "https://api.deepseek.com", demoMode: false },
      { type: "auto", text: "", images: [{ dataUrl: "data:image/png;base64,AA==" }] },
      {}
    ),
    (error) => error?.code === "unsupported_image"
  );
});

test("官方 base 的旧模型迁移到新预设，自定义代理保持原模型", () => {
  const chat = migrateSettings({
    settingsVersion: 1,
    baseUrl: "https://api.deepseek.com",
    model: "deepseek-chat",
    apiKey: "sk-test"
  });
  assert.equal(chat.settings.settingsVersion, SETTINGS_VERSION);
  assert.equal(chat.settings.model, "deepseek-flash");
  assert.equal(chat.settings.answerMode, "fast");
  assert.equal(chat.settings.apiKey, "sk-test");
  assert.equal(chat.changed, true);
  assert.match(chat.note, /deepseek-chat/);

  const reasoner = migrateSettings({
    settingsVersion: 1,
    baseUrl: "https://api.deepseek.com/v1",
    model: "deepseek-reasoner"
  });
  assert.equal(reasoner.settings.model, "deepseek-flash");
  assert.equal(reasoner.settings.answerMode, "detailed");
  assert.equal(reasoner.settings.settingsVersion, 2);

  const custom = migrateSettings({
    settingsVersion: 1,
    baseUrl: "https://proxy.example.com/v1",
    model: "deepseek-chat",
    answerMode: "standard"
  });
  assert.equal(custom.settings.model, "deepseek-chat");
  assert.equal(custom.settings.answerMode, "standard");
  assert.equal(custom.changed, true, "缺少版本号仍需升级结构，但不能改模型");
});

test("迁移幂等且默认标准模式；官方判定拒绝仿冒域名", () => {
  assert.equal(DEFAULT_SETTINGS.answerMode, "standard");
  const first = migrateSettings({ settingsVersion: 1, model: "deepseek-chat" });
  const second = migrateSettings(first.settings);
  assert.equal(second.settings.model, "deepseek-flash");
  assert.equal(second.settings.answerMode, first.settings.answerMode);
  assert.equal(second.changed, false);
  assert.equal(second.note, "");

  assert.equal(isOfficialDeepSeekBaseUrl("https://api.deepseek.com"), true);
  assert.equal(isOfficialDeepSeekBaseUrl("https://api.deepseek.com/v1/"), true);
  assert.equal(isOfficialDeepSeekBaseUrl("https://api.deepseek.com.evil.example"), false);
  assert.equal(isOfficialDeepSeekBaseUrl("http://api.deepseek.com"), false);
  assert.equal(isOfficialDeepSeekBaseUrl("https://proxy.example.com"), false);
});

test("预览必须等 warnings 等完整字段且答案通过校验", () => {
  const extractor = createPreviewExtractor("single");
  const withoutWarnings = JSON.stringify({
    status: "answered",
    question_type: "single",
    recognized_question: "题干",
    answer: ["A"],
    answer_text: "解析"
  });
  assert.equal(extractor(withoutWarnings), null, "warnings 未完整时不能预览");

  const valid = JSON.stringify({
    status: "answered",
    question_type: "single",
    recognized_question: "题干",
    answer: ["A"],
    answer_text: "解析",
    warnings: []
  });
  const preview = extractor(valid);
  assert.equal(preview?.validated, true);
  assert.deepEqual(preview?.answer, ["A"]);

  const invalidAnswer = JSON.stringify({
    status: "answered",
    question_type: "single",
    recognized_question: "题目\nA. 甲\nB. 乙",
    answer: ["D"],
    answer_text: "解析",
    warnings: []
  });
  assert.equal(extractor(invalidAnswer), null, "答案不在选项内不能预览");
});

test("parseAnalysis 兼容置信度字符串并按题型做严格校验", () => {
  const base = {
    status: "answered",
    question_type: "single",
    recognized_question: "题目\nA. 甲\nB. 乙",
    answer: ["A"],
    answer_text: "解析",
    warnings: []
  };
  assert.equal(parseAnalysis(JSON.stringify({ ...base, confidence: "high" })).confidence, 0.9);
  assert.equal(parseAnalysis(JSON.stringify({ ...base, confidence: "medium" })).confidence, 0.65);
  assert.equal(parseAnalysis(JSON.stringify({ ...base, confidence: "low" })).confidence, 0.35);
  assert.equal(parseAnalysis(JSON.stringify({ ...base, confidence: "not-a-value" })).confidence, null);

  const selectedMismatch = parseAnalysis(JSON.stringify({ ...base, question_type: "multiple", answer: ["A", "B"] }), "single");
  assert.equal(selectedMismatch.status, "needs_confirmation");
  assert.deepEqual(selectedMismatch.answer, []);

  const needs = parseAnalysis(JSON.stringify({
    status: "needs_confirmation",
    question_type: "single",
    recognized_question: "题目\nA. 甲\nB. 乙",
    answer: ["A"],
    answer_text: "不确定",
    warnings: ["图片模糊"]
  }));
  assert.equal(needs.status, "needs_confirmation");
  assert.deepEqual(needs.answer, []);
  assert.equal(needs.answerText, "");
});

test("自定义接口默认不假设图片/思考/JSON 能力", () => {
  const custom = getModelCapability("my-model", { baseUrl: "https://proxy.example.com/v1" });
  assert.deepEqual(custom, { supportsImages: false, supportsThinking: false, supportsJson: false, verified: false, known: false });
  assert.equal(modelSupportsImages("my-model", { baseUrl: "https://proxy.example.com/v1" }), false);
  assert.equal(modelSupportsImages("my-model", { baseUrl: "https://proxy.example.com/v1", customSupportsImages: true }), true);
  assert.equal(modelSupportsImages("deepseek-flash", { baseUrl: "https://proxy.example.com/v1" }), false);
});

async function withFetchResponse(body, fn) {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(body, {
    status: 200,
    headers: { "content-type": "text/event-stream" }
  });
  try {
    return await fn();
  } finally {
    globalThis.fetch = original;
  }
}

test("流式响应缺少 finish_reason 时拒绝并保留错误码", async () => {
  const settings = { apiKey: "sk-test", model: "deepseek-flash", baseUrl: "https://api.deepseek.com", demoMode: false };
  await withFetchResponse(
    'data: {"choices":[{"delta":{"content":"{}"}}]}\n\ndata: [DONE]\n\n',
    () => assert.rejects(analyzeQuestionStream(settings, { type: "auto", text: "题目", images: [] }, {}), (error) => error?.code === "missing_finish_reason")
  );
});

test("流式响应 finish_reason=length 或内容为空时明确失败", async () => {
  const settings = { apiKey: "sk-test", model: "deepseek-flash", baseUrl: "https://api.deepseek.com", demoMode: false };
  await withFetchResponse(
    'data: {"choices":[{"delta":{"content":"{}"},"finish_reason":"length"}]}\n\ndata: [DONE]\n\n',
    () => assert.rejects(analyzeQuestionStream(settings, { type: "auto", text: "题目", images: [] }, {}), (error) => error?.code === "length")
  );
  await withFetchResponse(
    'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n',
    () => assert.rejects(analyzeQuestionStream(settings, { type: "auto", text: "题目", images: [] }, {}), (error) => error?.code === "empty_content")
  );
});

const streamSettings = { apiKey: "sk-test", model: "deepseek-flash", baseUrl: "https://api.deepseek.com", demoMode: false };
const streamResult = {
  status: "answered",
  question_type: "single",
  recognized_question: "Choose A.\nA. yes\nB. no",
  answer: ["A"],
  answer_text: "",
  warnings: []
};
const contentEvent = (text) => `data: ${JSON.stringify({ choices: [{ delta: { content: text }, finish_reason: null }] })}\n\n`;
const stopEvent = 'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n';

test("SSE 缺少 [DONE] 时即使 finish_reason=stop 也必须失败", async () => {
  const body = contentEvent(JSON.stringify(streamResult)) + stopEvent;
  await withFetchResponse(body, () => assert.rejects(
    analyzeQuestionStream(streamSettings, { type: "single", text: "Choose A", images: [] }, {}),
    (error) => error?.code === "incomplete_stream"
  ));
  await withFetchResponse(body, () => assert.rejects(
    askFollowUpStream(streamSettings, { questionText: "Choose A", result: { answer: ["A"] } }, "why?", [], [], {}),
    (error) => error?.code === "incomplete_stream"
  ));
});

test("收到 [DONE] 后即使 HTTP 仍保持连接也立即完成", async () => {
  const body = contentEvent(JSON.stringify(streamResult)) + stopEvent + "data: [DONE]\n\n";
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(body));
      // 故意不 close，模拟上游收到 DONE 后仍保持连接。
    }
  }), { status: 200, headers: { "content-type": "text/event-stream" } });
  const started = Date.now();
  try {
    const result = await analyzeQuestionStream(streamSettings, { type: "single", text: "Choose A", images: [] }, {});
    assert.equal(result.status, "answered");
    assert.equal(result.finishReason, "stop");
    assert.ok(Date.now() - started < 1000, "收到 DONE 后不应继续等待 EOF");
  } finally {
    globalThis.fetch = original;
  }
});

test("非流式 JSON 响应仍可直接兼容", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({
    choices: [{ message: { content: JSON.stringify(streamResult) }, finish_reason: "stop" }],
    usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 }
  }), { status: 200, headers: { "content-type": "application/json" } });
  try {
    const result = await analyzeQuestionStream(streamSettings, { type: "single", text: "Choose A", images: [] }, {});
    assert.equal(result.status, "answered");
    assert.equal(result.usage.totalTokens, 15);
  } finally {
    globalThis.fetch = original;
  }
});

test("预览在 answer 已完成但 warnings 尚未到达时必须保持 null", () => {
  const extract = createPreviewExtractor("single");
  const prefix = '{"status":"answered","question_type":"single","recognized_question":"题目\\nA. 甲\\nB. 乙","answer":["A"],"answer_text":"因为 A 正确"';
  assert.equal(extract(prefix), null);
  const full = `${prefix},"warnings":[]}`;
  const preview = extract(full);
  assert.ok(preview);
  assert.equal(preview.validated, true);
  assert.deepEqual(preview.answer, ["A"]);
  assert.deepEqual(preview.warnings, []);
});

test("parseAnalysis 按题型要求答案格式，题型不一致时降级", () => {
  const fillMissingText = parseAnalysis(JSON.stringify({
    status: "answered",
    question_type: "fill",
    recognized_question: "填空题",
    answer: ["甲"],
    warnings: []
  }), "fill");
  assert.equal(fillMissingText.status, "needs_confirmation");
  assert.deepEqual(fillMissingText.answer, []);

  const shortMissingText = parseAnalysis(JSON.stringify({
    status: "answered",
    question_type: "short",
    recognized_question: "简答题",
    answer: [],
    warnings: []
  }), "short");
  assert.equal(shortMissingText.status, "needs_confirmation");

  const multipleMismatch = parseAnalysis(JSON.stringify({
    status: "answered",
    question_type: "multiple",
    recognized_question: "题目\\nA. 甲\\nB. 乙",
    answer: ["A", "B"],
    answer_text: "解析",
    warnings: []
  }), "single");
  assert.equal(multipleMismatch.status, "needs_confirmation");
});
