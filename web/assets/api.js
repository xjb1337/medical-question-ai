export class ApiError extends Error {
  constructor(message, status = 0, raw = "") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.raw = raw;
  }
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

function buildHeaders(settings) {
  return {
    "Content-Type": "application/json",
    "X-API-Key": settings.apiKey || "",
    "X-API-Base": (settings.baseUrl || "https://api.deepseek.com").trim()
  };
}

async function proxyRequest(endpoint, payload, settings, timeoutMs = 180000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response;
  try {
    response = await fetch(endpoint, {
      method: payload ? "POST" : "GET",
      headers: buildHeaders(settings),
      body: payload ? JSON.stringify(payload) : undefined,
      signal: controller.signal
    });
  } catch (error) {
    if (error?.name === "AbortError") throw new ApiError("请求超时，请检查网络或稍后重试。", 0);
    throw new ApiError("无法连接本地服务，请确认 web 服务仍在运行。", 0, String(error));
  } finally {
    clearTimeout(timer);
  }
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
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content === "string" && content.trim()) return content;
  if (Array.isArray(content)) {
    const text = content.map((item) => item?.text || "").join("");
    if (text.trim()) return text;
  }
  const errorMessage = payload?.error?.message;
  if (errorMessage) throw new ApiError(String(errorMessage), 0);
  throw new ApiError("模型没有返回可读取的内容。", 0);
}

// Returns null when the upstream omitted usage; do not fabricate zero usage.
export function usageFromResponse(payload) {
  const usage = payload?.usage;
  if (!usage || typeof usage !== "object") return null;
  const promptTokens = Number(usage.prompt_tokens ?? usage.promptTokens);
  const completionTokens = Number(usage.completion_tokens ?? usage.completionTokens);
  const totalTokens = Number(usage.total_tokens ?? usage.totalTokens);
  if (![promptTokens, completionTokens, totalTokens].every(Number.isFinite)) return null;
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

function toArray(value) {
  if (Array.isArray(value)) return value;
  if (value === null || value === undefined || value === "") return [];
  return [value];
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
  const answer = toArray(parsed.answer ?? parsed.answers)
    .map((item) => (typeof item === "string" ? item.trim() : toText(item?.value || item?.option || item)))
    .filter(Boolean);
  const answerText = toText(parsed.answer_text ?? parsed.answerText ?? parsed.explanation_answer);
  let status = normalizeStatus(parsed.status);
  if (!status) status = answer.length || answerText ? "answered" : "needs_confirmation";
  if (status === "answered" && !answer.length && !answerText) status = "needs_confirmation";
  let confidence = Number(parsed.confidence ?? parsed.confidence_score ?? 0);
  if (!Number.isFinite(confidence)) confidence = 0;
  if (confidence > 1 && confidence <= 100) confidence = confidence / 100;
  confidence = Math.max(0, Math.min(1, confidence));

  const imageObservations = toArray(parsed.image_observations ?? parsed.imageObservations).map((item, index) => ({
    imageIndex: Number(item?.image_index ?? item?.imageIndex ?? index + 1) || index + 1,
    observation: toText(item?.observation ?? item?.text),
    uncertainty: toText(item?.uncertainty ?? item?.uncertain)
  })).filter((item) => item.observation || item.uncertainty);

  const optionAnalyses = toArray(parsed.option_analyses ?? parsed.optionAnalyses).map((item, index) => ({
    option: toText(item?.option ?? item?.key ?? String.fromCharCode(65 + index)).toUpperCase(),
    verdict: toText(item?.verdict ?? item?.status ?? "不确定"),
    reason: toText(item?.reason ?? item?.detail)
  })).filter((item) => item.option || item.reason);

  const reasoningSteps = toArray(parsed.reasoning_steps ?? parsed.reasoningSteps).map((item, index) => ({
    title: toText(item?.title ?? item?.step ?? `步骤 ${index + 1}`),
    detail: toText(item?.detail ?? item?.description ?? item)
  })).filter((item) => item.title || item.detail);

  const warnings = toArray(parsed.warnings ?? parsed.warning).map(toText).filter(Boolean);
  if (status === "needs_confirmation") warnings.unshift("当前信息不足以给出确定答案，请补充题干或清晰图片后重试。");
  if (status === "out_of_scope") warnings.unshift("这道题看起来不属于医学课程范围，系统不会给出医学解析。");
  if (confidence > 0 && confidence < 0.55 && status === "answered") warnings.push("模型把握程度较低，请以教材和课堂标准答案核对。");

  return {
    status,
    questionType: normalizeType(parsed.question_type ?? parsed.questionType ?? parsed.type, selectedType || "auto"),
    recognizedQuestion: toText(parsed.recognized_question ?? parsed.recognizedQuestion ?? parsed.question),
    answer,
    answerText,
    imageObservations,
    optionAnalyses,
    reasoningSteps,
    keyPoints: toArray(parsed.key_points ?? parsed.keyPoints).map(toText).filter(Boolean),
    pitfalls: toArray(parsed.pitfalls).map(toText).filter(Boolean),
    confidence,
    warnings: [...new Set(warnings)],
    usage: {
      promptTokens: Number(parsed?.usage?.prompt_tokens ?? parsed?.usage?.promptTokens ?? 0) || 0,
      completionTokens: Number(parsed?.usage?.completion_tokens ?? parsed?.usage?.completionTokens ?? 0) || 0,
      totalTokens: Number(parsed?.usage?.total_tokens ?? parsed?.usage?.totalTokens ?? 0) || 0
    }
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

export async function analyzeQuestion(settings, draft) {
  if (settings.demoMode) return makeDemoAnalysis(draft);
  if (!settings.apiKey) throw new ApiError("尚未配置 API Key，请先到设置页填写。", 0);
  const content = [{ type: "text", text: buildPromptText(draft) }];
  for (const image of draft.images || []) {
    content.push({ type: "image_url", image_url: { url: image.dataUrl } });
  }
  const payload = {
    model: settings.model || "deepseek-flash",
    messages: [
      { role: "system", content: SYSTEM_PROMPT },
      { role: "user", content }
    ],
    temperature: 0.15,
    max_tokens: 4096
  };
  const response = await proxyRequest("/api/deepseek/chat", payload, settings);
  const result = parseAnalysis(extractContent(response), draft.type);
  if (response?.usage) {
    result.usage = {
      promptTokens: Number(response.usage.prompt_tokens) || 0,
      completionTokens: Number(response.usage.completion_tokens) || 0,
      totalTokens: Number(response.usage.total_tokens) || 0
    };
  }
  return result;
}

function questionContext(question) {
  const result = question.result || {};
  const answer = result.answer?.length ? result.answer.join("、") : result.answerText || "（暂无确定答案）";
  const steps = (result.reasoningSteps || []).map((step) => `${step.title}：${step.detail}`).join("\n");
  return [
    `【原题】${question.questionText || "（图片题）"}`,
    `【识别题干】${result.recognizedQuestion || "未识别"}`,
    `【当前答案】${answer}`,
    result.keyPoints?.length ? `【关键点】${result.keyPoints.join("；")}` : "",
    steps ? `【已有解析】\n${steps}` : ""
  ].filter(Boolean).join("\n");
}

export async function askFollowUp(settings, question, userText, images, history) {
  if (settings.demoMode) {
    return {
      text: makeDemoFollowUp(question, userText),
      usage: { promptTokens: 320, completionTokens: 180, totalTokens: 500, simulated: true },
      demo: true
    };
  }
  if (!settings.apiKey) throw new ApiError("尚未配置 API Key，请先到设置页填写。", 0);
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
  const payload = {
    model: settings.model || "deepseek-flash",
    messages: [
      { role: "system", content: FOLLOW_UP_PROMPT },
      { role: "user", content }
    ],
    temperature: 0.2,
    max_tokens: 2200
  };
  const response = await proxyRequest("/api/deepseek/chat", payload, settings);
  return { text: extractContent(response).trim(), usage: usageFromResponse(response), demo: false };
}

export async function testConnection(settings) {
  if (settings.demoMode) return { demo: true, message: "当前处于演示模式，不需要连接真实接口。" };
  if (!settings.apiKey) throw new ApiError("请先填写 API Key。", 0);
  await proxyRequest("/api/deepseek/models", null, settings, 30000);
  return { demo: false, message: "连接成功，API Key 可用。" };
}

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
  const result = {
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
    usage: { promptTokens: 1280, completionTokens: 760, totalTokens: 2040 }
  };
  return result;
}

function makeDemoFollowUp(question, prompt) {
  const focused = prompt.trim() || "这道题的关键判断思路";
  return `这是演示模式下的追问回答。\n\n针对「${focused}」，建议先回到题干中的核心线索：${question.result?.recognizedQuestion || question.questionText || "题目信息"}。\n\n复习时可以按三步检查：\n1. 先确认题目问的是诊断、机制还是处理；\n2. 再对照教材中的定义和鉴别点逐项排除；\n3. 最后把答案还原到题干条件中验证是否自洽。\n\n真实测试时关闭演示模式并配置 API Key，就能得到模型针对这道题的完整回答。`;
}
