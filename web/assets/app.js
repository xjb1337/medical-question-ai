import { icon } from "./icons.js";
import {
  listQuestions,
  getQuestion,
  saveQuestion,
  deleteQuestion as deleteQuestionRecord,
  clearQuestions,
  loadSettings,
  saveSettings,
  clearStoredSettings,
  maskKey,
  storageStats
} from "./store.js";
import { analyzeQuestion, askFollowUp, testConnection, ApiError } from "./api.js";
import {
  compressFile,
  rotateDataUrl,
  cropDataUrl,
  formatBytes,
  totalImageBytes
} from "./images.js";

const app = document.querySelector("#app");
const toastRoot = document.querySelector("#toast-root");
const modalRoot = document.querySelector("#modal-root");
const busyRoot = document.querySelector("#busy-root");

const TYPE_LABELS = {
  auto: "自动识别",
  single: "单选题",
  multiple: "多选题",
  fill: "填空题",
  short: "简答题"
};
const TYPE_ICONS = {
  auto: "sparkle",
  single: "target",
  multiple: "checkCircle",
  fill: "edit",
  short: "book"
};
const MODEL_PRESETS = ["deepseek-chat", "deepseek-reasoner", "deepseek-flash"];
const SAMPLE_TEXT = "患者，男，58岁，突发胸骨后压榨性疼痛2小时，伴大汗。心电图示Ⅱ、Ⅲ、aVF导联ST段抬高。最可能的诊断是：\nA. 急性下壁心肌梗死\nB. 不稳定型心绞痛\nC. 急性心包炎\nD. 主动脉夹层";
const MAX_IMAGES = 9;
const MAX_TOTAL_BYTES = 18 * 1024 * 1024;

const state = {
  route: { name: "compose" },
  questions: [],
  settings: loadSettings(),
  draft: newDraft(),
  editId: null,
  historyQuery: "",
  historyFilter: "all",
  analysisError: "",
  connection: null,
  showKey: false,
  followUpDraft: "",
  followUpImages: [],
  followUpBusy: false,
  followUpError: "",
  busy: false,
  confirmAction: null,
  crop: null
};

function newDraft() {
  return { type: "auto", text: "", images: [], privacy: false };
}

function uid(prefix = "q") {
  if (globalThis.crypto?.randomUUID) return `${prefix}-${crypto.randomUUID()}`;
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeAttr(value) {
  return escapeHtml(value).replaceAll("\n", " ");
}

function nl2br(value) {
  return escapeHtml(value).replace(/\n/g, "<br>");
}

function truncate(value, max = 90) {
  const text = String(value || "").replace(/\s+/g, " ").trim();
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

function formatDate(timestamp) {
  if (!timestamp) return "—";
  const date = new Date(timestamp);
  const now = new Date();
  const sameYear = date.getFullYear() === now.getFullYear();
  return new Intl.DateTimeFormat("zh-CN", {
    ...(sameYear ? {} : { year: "numeric" }),
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}

function formatClock(timestamp) {
  if (!timestamp) return "";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(timestamp));
}

function parseRoute() {
  const raw = location.hash.replace(/^#\/?/, "");
  const parts = raw.split("/").filter(Boolean).map((part) => decodeURIComponent(part));
  if (parts[0] === "result" && parts[1]) return { name: "result", id: parts[1] };
  if (["compose", "history", "settings"].includes(parts[0])) return { name: parts[0] };
  return { name: "compose" };
}

function navigate(path) {
  const hash = `#${path.startsWith("/") ? path : `/${path}`}`;
  if (location.hash === hash) {
    render();
    return;
  }
  location.hash = hash;
}

function visibleQuestions() {
  return state.questions.filter((question) => !question.isEphemeral);
}

function currentQuestion() {
  if (state.route.name !== "result") return null;
  return state.questions.find((question) => question.id === state.route.id) || null;
}

function typeLabel(type) {
  return TYPE_LABELS[type] || TYPE_LABELS.auto;
}

function typeIcon(type) {
  return TYPE_ICONS[type] || TYPE_ICONS.auto;
}

async function refreshQuestions() {
  state.questions = await listQuestions();
}

function toast(message, type = "info") {
  const node = document.createElement("div");
  node.className = `toast ${type === "error" ? "error" : ""}`;
  node.innerHTML = `${icon(type === "error" ? "alert" : "checkCircle")}<span>${escapeHtml(message)}</span>`;
  toastRoot.appendChild(node);
  window.setTimeout(() => {
    node.style.opacity = "0";
    node.style.transform = "translateY(6px)";
    window.setTimeout(() => node.remove(), 220);
  }, 3600);
}

function showBusy(title, detail) {
  state.busy = true;
  busyRoot.innerHTML = `
    <div class="busy-backdrop" role="status" aria-live="polite">
      <div class="busy-card">
        <div class="busy-orbit">${icon("sparkle")}</div>
        <h3>${escapeHtml(title)}</h3>
        <p>${escapeHtml(detail)}</p>
        <div class="busy-progress"><span></span></div>
      </div>
    </div>`;
}

function hideBusy() {
  state.busy = false;
  busyRoot.innerHTML = "";
}

function openModal(content, className = "") {
  modalRoot.innerHTML = `
    <div class="modal-backdrop" data-modal-backdrop="true">
      <div class="modal ${className}" role="dialog" aria-modal="true">${content}</div>
    </div>`;
}

function closeModal() {
  modalRoot.innerHTML = "";
  state.crop = null;
}

function openConfirm(title, message, confirmText, callback) {
  state.confirmAction = callback;
  openModal(`
    <h2>${escapeHtml(title)}</h2>
    <p>${escapeHtml(message)}</p>
    <div class="modal-actions">
      <button class="btn btn-secondary" data-action="close-modal">取消</button>
      <button class="btn btn-danger" data-action="confirm-action">${escapeHtml(confirmText)}</button>
    </div>`);
}

function pageHead({ eyebrow, title, description = "", actions = "" }) {
  return `
    <div class="page-head">
      <div>
        <div class="eyebrow">${escapeHtml(eyebrow)}</div>
        <h1>${escapeHtml(title)}</h1>
        ${description ? `<p>${escapeHtml(description)}</p>` : ""}
      </div>
      ${actions ? `<div class="page-head-actions">${actions}</div>` : ""}
    </div>`;
}

function apiBanner() {
  if (state.settings.demoMode) {
    return `<div class="api-banner">${icon("info")}<span><strong>演示模式已开启。</strong>解析结果由本地示例生成，不会调用真实模型；可在设置页关闭。</span><a class="btn btn-sm btn-secondary" href="#/settings">设置</a></div>`;
  }
  if (!state.settings.apiKey) {
    return `<div class="api-banner">${icon("key")}<span>还没有配置 DeepSeek API Key。配置后才能调用真实模型；也可以先在设置页开启演示模式体验流程。</span><a class="btn btn-sm btn-secondary" href="#/settings">去配置</a></div>`;
  }
  return "";
}

function render() {
  const route = parseRoute();
  state.route = route;
  const content = route.name === "result"
    ? renderResultPage()
    : route.name === "history"
      ? renderHistoryPage()
      : route.name === "settings"
        ? renderSettingsPage()
        : renderComposePage();
  app.innerHTML = shell(route, content);
  document.title = `${routeTitle(route)} · 医学拍题助手`;
  afterRender(route);
}

function routeTitle(route) {
  if (route.name === "history") return "历史记录";
  if (route.name === "settings") return "设置";
  if (route.name === "result") return "解析结果";
  return "新建解析";
}

function shell(route, content) {
  const active = route.name === "result" ? "history" : route.name;
  const count = visibleQuestions().length;
  const nav = [
    ["compose", "plus", "新建解析"],
    ["history", "history", "历史记录"],
    ["settings", "settings", "设置"]
  ];
  const navHtml = nav.map(([key, name, label]) => `
    <a class="side-link ${active === key ? "active" : ""}" href="#/${key}">
      ${icon(name)}<span>${label}</span>
      ${key === "history" && count ? `<span class="side-count">${count}</span>` : ""}
    </a>`).join("");

  return `
    <div class="app-shell">
      <aside class="sidebar">
        <a class="brand" href="#/compose">
          <span class="brand-mark">${icon("logo")}</span>
          <span class="brand-word"><strong>医学拍题助手</strong><span>MED STUDY DESK</span></span>
        </a>
        <div class="side-label">工作台</div>
        <nav class="side-nav">${navHtml}</nav>
        <div class="sidebar-spacer"></div>
        <div class="side-footer">
          <div class="side-footer-title">${icon("shield")}本地优先 · 隐私可控</div>
          <p><span class="side-foot-dot"></span>图片与历史仅保存在此浏览器；API Key 只随请求发往你配置的接口。</p>
        </div>
      </aside>
      <main class="main">
        <header class="topbar">
          <div class="topbar-left">
            <a class="mobile-brand" href="#/compose"><span class="brand-mark">${icon("logo")}</span><span>医学拍题助手</span></a>
            <div class="breadcrumb"><span>学习工作台</span><span class="sep">/</span><strong>${routeTitle(route)}</strong></div>
          </div>
          <div class="topbar-right">
            ${state.settings.demoMode ? `<span class="tag tag-amber">演示模式</span>` : ""}
            <span class="local-pill"><span class="dot"></span>本地模式</span>
            <div class="mobile-top-actions"><a class="btn btn-icon btn-primary" href="#/compose" aria-label="新建解析">${icon("plus")}</a></div>
            <span class="avatar">AI</span>
          </div>
        </header>
        <div class="page-content">${content}</div>
      </main>
    </div>`;
}

function renderComposePage() {
  const draft = state.draft;
  const editing = Boolean(state.editId);
  const actions = editing
    ? `<button class="btn btn-secondary" data-action="cancel-edit">${icon("x")}退出编辑</button>`
    : "";
  return `
    ${pageHead({
      eyebrow: editing ? "EDIT & RE-ANALYZE" : "NEW QUESTION",
      title: editing ? "修改这道题" : "把题目交给 AI，一起拆开来看",
      description: editing
        ? "题干、题型和补充图片都可以修改；保存后会生成一份新的解析。"
        : "支持题干文字与多张图片。先选题型，再补充信息，剩下的交给 DeepSeek 拆解。",
      actions
    })}
    ${apiBanner()}
    <div class="compose-grid">
      <section class="card composer-card">
        <div class="composer-section">
          <div class="section-topline">
            <div class="section-title"><span class="num">01</span>选择题型</div>
            <span class="section-hint">不确定就选自动识别</span>
          </div>
          <div class="type-row">
            ${Object.entries(TYPE_LABELS).map(([key, label]) => `
              <button class="type-chip ${draft.type === key ? "active" : ""}" data-action="set-type" data-type="${key}">${label}</button>`).join("")}
          </div>
        </div>

        <div class="composer-section">
          <div class="section-topline">
            <div class="section-title"><span class="num">02</span>题干文字</div>
            <span class="section-hint">可直接粘贴题目</span>
          </div>
          <div class="question-area">
            <textarea id="question-text" maxlength="6000" placeholder="把题干、选项或需要解释的内容粘贴到这里…">${escapeHtml(draft.text)}</textarea>
            <span class="textarea-meta"><span id="text-count">${draft.text.length}</span> / 6000</span>
          </div>
        </div>

        <div class="composer-section">
          <div class="section-topline">
            <div class="section-title"><span class="num">03</span>题目图片</div>
            <span class="section-hint">${draft.images.length} / ${MAX_IMAGES} 张 · ${formatBytes(totalImageBytes(draft.images))}</span>
          </div>
          <div class="image-actions">
            <button class="btn btn-sm btn-secondary" data-action="add-images">${icon("image")}从相册选择</button>
            <button class="btn btn-sm btn-secondary" data-action="add-camera">${icon("camera")}拍照 / 相机</button>
          </div>
          <input class="file-input" id="image-input" type="file" accept="image/*" multiple>
          <input class="file-input" id="camera-input" type="file" accept="image/*" capture="environment">

          ${draft.images.length ? `
            <div class="image-grid">
              ${draft.images.map((image, index) => `
                <div class="image-card" draggable="true" data-image-index="${index}">
                  <div class="image-thumb-wrap" data-action="preview-image" data-index="${index}" title="查看大图">
                    <img class="image-thumb" src="${escapeAttr(image.dataUrl)}" alt="题目图片 ${index + 1}">
                    <span class="image-index">${index + 1}</span>
                    ${image.warnings?.length ? `<span class="image-warning">${escapeHtml(image.warnings[0])}</span>` : ""}
                  </div>
                  <div class="image-tools">
                    <button class="btn btn-icon move" data-action="move-image" data-index="${index}" data-dir="-1" title="左移" ${index === 0 ? "disabled" : ""}>${icon("chevronLeft")}</button>
                    <button class="btn btn-icon" data-action="rotate-image" data-index="${index}" title="旋转">${icon("rotateRight")}</button>
                    <button class="btn btn-icon" data-action="crop-image" data-index="${index}" title="裁剪">${icon("crop")}</button>
                    <button class="btn btn-icon remove" data-action="remove-image" data-index="${index}" title="删除">${icon("trash")}</button>
                    <button class="btn btn-icon move" data-action="move-image" data-index="${index}" data-dir="1" title="右移" ${index === draft.images.length - 1 ? "disabled" : ""}>${icon("chevronRight")}</button>
                  </div>
                  <div class="image-name">${escapeHtml(image.name || `图片 ${index + 1}`)} · ${formatBytes(image.sizeBytes)}</div>
                </div>`).join("")}
            </div>` : ""}

          <div class="upload-zone" data-action="add-images" data-dropzone="true">
            <span class="upload-icon">${icon("image")}</span>
            <strong>拖拽图片到这里，或点击选择</strong>
            <span>支持 JPG / PNG / WEBP · 最多 ${MAX_IMAGES} 张</span>
          </div>
        </div>

        <div class="composer-section">
          <div class="privacy-row">
            <label class="checkbox">
              <input id="privacy-check" type="checkbox" ${draft.privacy ? "checked" : ""}>
              <span>${icon("check")}</span>
            </label>
            <label class="privacy-copy" for="privacy-check">
              <strong>我确认这是我有权使用的学习材料</strong>
              图片中可能包含姓名、病历号等信息，请先遮挡。题目图片会随解析请求发送到所配置的 API。
            </label>
          </div>
          ${state.analysisError ? `<div class="error-banner">${icon("alert")}<span>${escapeHtml(state.analysisError)}</span></div>` : ""}
          <div class="submit-row">
            <div class="submit-note">${icon("shield")}解析结果仅供学习参考，请以教材和老师讲解为准</div>
            <button class="btn btn-primary btn-lg" data-action="start-analysis" ${state.busy ? "disabled" : ""}>
              ${icon("sparkle")}${editing ? "重新解析" : "开始解析"}
            </button>
          </div>
        </div>
      </section>

      <aside>
        <div class="insight-card">
          <div class="insight-kicker">${icon("sparkle")}AI STUDY FLOW</div>
          <h3>${editing ? "改一处条件，答案可能就变了。" : "先看清楚题，再开始作答。"}</h3>
          <p>拍题助手会先识别题干和图片，再给出答案、选项判断与推理过程。信息不足时会明确提醒，而不是猜一个答案。</p>
          <div class="how-list">
            <div class="how-item"><span class="how-number">1</span><div><strong>识别题目</strong><span>题干、选项、图片中的关键信息</span></div></div>
            <div class="how-item"><span class="how-number">2</span><div><strong>逐步推理</strong><span>把诊断依据与排除过程讲清楚</span></div></div>
            <div class="how-item"><span class="how-number">3</span><div><strong>回到教材</strong><span>标出易错点和需要核对的地方</span></div></div>
          </div>
          <div class="insight-rule">
            <div class="mini-label">使用建议</div>
            <p>图片尽量拍正、拍全；选择题保留选项字母，解析会更准确。</p>
          </div>
        </div>
        ${editing ? "" : `
        <div class="sample-card">
          <div class="sample-label">没有题目？先试试示例</div>
          <p>一道心血管系统的单选题，包含 4 个选项。可以直接加载后点击「开始解析」。</p>
          <button data-action="load-sample">载入示例题 ${icon("arrowRight")}</button>
        </div>`}
      </aside>
    </div>`;
}

function renderResultPage() {
  const question = currentQuestion();
  if (!question) {
    return `${pageHead({ eyebrow: "RESULT", title: "找不到这道题" })}
      <div class="card empty-state">${emptyState("history", "这道题可能已经被删除", "回到历史记录看看其他题目，或重新发起一次解析。", "返回历史", "go-history")}</div>`;
  }
  const result = question.result || {};
  const outOfScope = result.status === "out_of_scope";
  const needsConfirmation = result.status === "needs_confirmation";
  const answerText = result.answer?.length
    ? result.answer.join("、")
    : result.answerText || (needsConfirmation ? "需要补充信息" : "暂无确定答案");
  const confidence = Math.round((result.confidence || 0) * 100);

  const hero = `
    <section class="result-hero">
      <div class="result-meta">
        <span class="tag ${outOfScope ? "tag-gray" : needsConfirmation ? "tag-amber" : "tag-teal"}">${outOfScope ? "范围外" : needsConfirmation ? "需核对" : "已解析"}</span>
        <span class="tag tag-blue">${typeLabel(result.questionType || question.questionType)}</span>
        ${question.favorite ? `<span class="tag tag-amber">${icon("starFilled")}已收藏</span>` : ""}
      </div>
      ${outOfScope
        ? `<h2>这似乎不是一道医学题</h2><p class="answer-sub">系统没有生成医学解析，以免把无关内容当成题目作答。</p>`
        : `<h2>${escapeHtml(answerText)}</h2>
           <p class="answer-sub">${needsConfirmation ? "先补充题干或上传更清晰的图片，再重新解析会更可靠。" : `模型置信度 ${confidence}% · 仅供参考`}</p>
           ${!needsConfirmation ? `<div class="confidence-line"><div class="confidence-bar"><span style="width:${confidence}%"></span></div><span class="confidence-text">${confidence >= 75 ? "较有把握" : confidence >= 50 ? "建议核对" : "把握较低"}</span></div>` : ""}`}
      ${!outOfScope && result.answer?.length ? `<div class="answer-options">${result.answer.map((item) => `<span class="answer-option">${escapeHtml(item)}</span>`).join("")}</div>` : ""}
    </section>`;

  const recognized = result.recognizedQuestion
    ? `<section class="card result-section"><h3>${icon("eye")}识别到的题干</h3><p class="recognized-text">${nl2br(result.recognizedQuestion)}</p></section>`
    : "";

  const observations = result.imageObservations?.length
    ? `<section class="card result-section"><h3>${icon("image")}图片观察</h3>
        ${result.imageObservations.map((item) => `
          <div class="observation-row">
            <span class="observation-index">${item.imageIndex || "?"}</span>
            <div class="observation-body"><p>${nl2br(item.observation || "暂无观察")}</p>${item.uncertainty ? `<small>不确定：${escapeHtml(item.uncertainty)}</small>` : ""}</div>
          </div>`).join("")}
      </section>` : "";

  const options = result.optionAnalyses?.length
    ? `<section class="card result-section"><h3>${icon("target")}选项分析</h3><div class="option-table">
        ${result.optionAnalyses.map((item) => {
          const verdict = item.verdict || "不确定";
          const cls = verdict.includes("正确") ? "verdict-correct" : verdict.includes("错误") ? "verdict-wrong" : "verdict-unknown";
          return `<div class="option-line"><span class="option-key">${escapeHtml(item.option || "?")}</span><span class="option-verdict ${cls}">${escapeHtml(verdict)}</span><span class="option-reason">${escapeHtml(item.reason || "")}</span></div>`;
        }).join("")}
      </div></section>` : "";

  const reasoning = result.reasoningSteps?.length
    ? `<section class="card result-section"><h3>${icon("lightbulb")}解题思路</h3><ol class="reasoning-list">
        ${result.reasoningSteps.map((step, index) => `<li class="reasoning-step"><span class="reason-num">${index + 1}</span><div><strong>${escapeHtml(step.title || `步骤 ${index + 1}`)}</strong><p>${nl2br(step.detail || "")}</p></div></li>`).join("")}
      </ol></section>` : "";

  const points = (result.keyPoints?.length || result.pitfalls?.length)
    ? `<section class="card result-section"><h3>${icon("book")}巩固与易错</h3><div class="point-grid">
        <div class="point-box"><div class="point-title">${icon("checkCircle")}关键知识点</div><ul>${(result.keyPoints || []).map((item) => `<li>${escapeHtml(item)}</li>`).join("") || "<li>暂无</li>"}</ul></div>
        <div class="point-box amber"><div class="point-title">${icon("alert")}易错点</div><ul>${(result.pitfalls || []).map((item) => `<li>${escapeHtml(item)}</li>`).join("") || "<li>暂无</li>"}</ul></div>
      </div></section>` : "";

  const warnings = result.warnings?.length
    ? `<section class="card result-section"><h3>${icon("alert")}需要留意</h3><div class="warning-list">${result.warnings.map((item) => `<div class="warning-item">${icon("alert")}<span>${escapeHtml(item)}</span></div>`).join("")}</div></section>`
    : "";

  const usage = result.usage?.totalTokens
    ? `<div class="side-stat"><span>Token</span><strong>${result.usage.totalTokens.toLocaleString()} total</strong></div>`
    : "";

  return `
    <div class="result-head">
      <button class="result-back" data-action="go-history">${icon("arrowLeft")}返回历史</button>
      <div class="result-head-actions">
        <button class="btn btn-sm btn-secondary" data-action="toggle-favorite" data-id="${escapeAttr(question.id)}">${icon(question.favorite ? "starFilled" : "star")}<span>${question.favorite ? "已收藏" : "收藏"}</span></button>
        <button class="btn btn-sm btn-secondary" data-action="edit-question" data-id="${escapeAttr(question.id)}">${icon("edit")}<span>修改再解析</span></button>
        <button class="btn btn-sm btn-secondary" data-action="share-answer" data-id="${escapeAttr(question.id)}">${icon("share")}<span>分享</span></button>
      </div>
    </div>
    <div class="result-layout">
      <div class="result-main">
        ${hero}
        ${recognized}
        ${observations}
        ${options}
        ${reasoning}
        ${points}
        ${warnings}
        ${outOfScope ? "" : renderFollowUp(question)}
      </div>
      <aside class="result-side">
        <div class="card result-side-card">
          <div class="side-stat"><span>题型</span><strong>${typeLabel(result.questionType || question.questionType)}</strong></div>
          <div class="side-stat"><span>创建时间</span><strong>${formatDate(question.createdAt)}</strong></div>
          <div class="side-stat"><span>模型</span><strong>${escapeHtml(question.model || state.settings.model)}</strong></div>
          ${usage}
        </div>
        <div class="card result-side-card side-actions">
          <button class="btn btn-sm btn-secondary" data-action="copy-answer" data-id="${escapeAttr(question.id)}">${icon("copy")}复制答案</button>
          <button class="btn btn-sm btn-secondary" data-action="delete-question" data-id="${escapeAttr(question.id)}">${icon("trash")}删除这道题</button>
        </div>
        <p class="side-note">本题解析由模型生成，仅用于课程学习；临床决策请以最新指南与带教老师意见为准。</p>
      </aside>
    </div>`;
}

function renderFollowUp(question) {
  const messages = question.followUps || [];
  return `
    <section class="card followup-card">
      <div class="followup-title"><h3>${icon("send")}继续追问</h3><span>围绕这道题继续问</span></div>
      <div class="follow-list">
        ${messages.length ? messages.map((message) => `
          <div class="follow-msg ${message.role === "user" ? "user" : ""}">
            <span class="follow-avatar">${message.role === "user" ? "我" : "AI"}</span>
            <div class="follow-bubble">${nl2br(message.text || "")}${(message.images || []).map((image) => `<img src="${escapeAttr(image.dataUrl)}" alt="追问图片">`).join("")}</div>
          </div>`).join("") : `<div class="follow-empty">还有哪里没看懂？可以问「为什么排除 C」或「这道题的诊断标准是什么」。</div>`}
        ${state.followUpBusy ? `<div class="follow-busy"><span class="mini-spinner"></span>正在结合原题思考…</div>` : ""}
      </div>
      ${state.followUpError ? `<div class="error-banner">${icon("alert")}<span>${escapeHtml(state.followUpError)}</span></div>` : ""}
      ${state.followUpImages.length ? `<div class="follow-attachment">${state.followUpImages.map((image, index) => `<img class="thumb" src="${escapeAttr(image.dataUrl)}" alt="追问图 ${index + 1}"><button data-action="remove-followup-image" data-index="${index}">移除</button>`).join("")}</div>` : ""}
      <div class="follow-input-wrap">
        <textarea id="followup-text" rows="1" placeholder="继续问这道题…">${escapeHtml(state.followUpDraft)}</textarea>
        <div class="follow-tools">
          <button class="btn btn-icon btn-ghost" data-action="followup-image" title="附加图片">${icon("image")}</button>
          <button class="btn btn-primary follow-send" data-action="send-followup" title="发送" ${state.followUpBusy ? "disabled" : ""}>${icon("send")}</button>
        </div>
      </div>
      <input class="file-input" id="followup-input" type="file" accept="image/*" multiple>
    </section>`;
}

function renderHistoryPage() {
  const count = visibleQuestions().length;
  const actions = count
    ? `<button class="btn btn-sm btn-secondary" data-action="clear-history">${icon("trash")}清空全部</button>`
    : "";
  return `
    ${pageHead({ eyebrow: "LIBRARY", title: "历史记录", description: "所有解析过的题目都留在这里。搜索题干、答案或知识点，快速找回。", actions })}
    <div class="history-tools">
      <label class="search-field">${icon("search")}<input id="history-search" value="${escapeAttr(state.historyQuery)}" placeholder="搜索题干、答案或知识点…" autocomplete="off"></label>
      <button class="filter-chip ${state.historyFilter === "all" ? "active" : ""}" data-action="history-filter" data-filter="all">全部</button>
      <button class="filter-chip ${state.historyFilter === "favorites" ? "active" : ""}" data-action="history-filter" data-filter="favorites">只看收藏</button>
      <span class="history-summary" id="history-summary">${historySummary()}</span>
    </div>
    <div class="history-list" id="history-list">${historyListHtml()}</div>`;
}

function filteredHistory() {
  const query = state.historyQuery.trim().toLowerCase();
  return visibleQuestions().filter((question) => {
    if (state.historyFilter === "favorites" && !question.favorite) return false;
    if (!query) return true;
    const result = question.result || {};
    return [
      question.questionText,
      result.recognizedQuestion,
      result.answerText,
      (result.answer || []).join(" "),
      (result.keyPoints || []).join(" "),
      (result.reasoningSteps || []).map((step) => `${step.title} ${step.detail}`).join(" ")
    ].some((value) => String(value || "").toLowerCase().includes(query));
  });
}

function historySummary() {
  const total = visibleQuestions().length;
  const shown = filteredHistory().length;
  if (!total) return "还没有记录";
  return state.historyQuery || state.historyFilter !== "all" ? `找到 ${shown} / ${total} 道` : `共 ${total} 道题`;
}

function questionSummary(question) {
  const result = question.result || {};
  if (result.status === "out_of_scope") return "非医学题目，未生成解析";
  if (result.answer?.length) return `答案：${result.answer.join("、")}`;
  if (result.answerText) return result.answerText;
  return result.recognizedQuestion || "等待补充信息";
}

function historyListHtml() {
  const items = filteredHistory();
  if (!items.length) {
    return emptyState(
      "history",
      state.historyQuery ? "没有匹配的题目" : "还没有解析记录",
      state.historyQuery ? "换一个关键词试试，或清空搜索条件。" : "去新建一次解析，结果会自动保存在这里。",
      state.historyQuery ? "清空搜索" : "开始解析",
      state.historyQuery ? "clear-search" : "go-compose"
    );
  }
  return items.map((question) => `
    <article class="card history-card" data-action="open-question" data-id="${escapeAttr(question.id)}" tabindex="0">
      <div class="history-type">${icon(typeIcon(question.result?.questionType || question.questionType))}</div>
      <div class="history-body">
        <h3>${escapeHtml(truncate(question.questionText || question.result?.recognizedQuestion || "图片题", 74))}</h3>
        <p>${escapeHtml(truncate(questionSummary(question), 96))}</p>
        <div class="history-tags">
          <span class="tag ${question.result?.status === "out_of_scope" ? "tag-gray" : "tag-teal"}">${typeLabel(question.result?.questionType || question.questionType)}</span>
          ${question.result?.status === "needs_confirmation" ? `<span class="tag tag-amber">需核对</span>` : ""}
          ${question.favorite ? `<span class="tag tag-amber">${icon("starFilled")}收藏</span>` : ""}
        </div>
      </div>
      <div class="history-side">
        <span class="history-date">${formatDate(question.createdAt)}</span>
        <button class="btn btn-icon btn-ghost history-fav" data-action="toggle-favorite" data-id="${escapeAttr(question.id)}" title="${question.favorite ? "取消收藏" : "收藏"}">${icon(question.favorite ? "starFilled" : "star")}</button>
        <button class="btn btn-icon btn-ghost history-actions" data-action="delete-question" data-id="${escapeAttr(question.id)}" title="删除">${icon("trash")}</button>
      </div>
    </article>`).join("");
}

function emptyState(iconName, title, text, actionLabel, action) {
  return `<div class="empty-state"><div class="empty-icon">${icon(iconName)}</div><h2>${escapeHtml(title)}</h2><p>${escapeHtml(text)}</p><button class="btn btn-primary" data-action="${action}">${escapeHtml(actionLabel)}</button></div>`;
}

function renderSettingsPage() {
  const settings = state.settings;
  const connection = state.connection;
  return `
    ${pageHead({ eyebrow: "PREFERENCES", title: "设置", description: "配置模型接口与本地数据偏好。API Key 不会写入浏览器历史或日志。" })}
    <div class="settings-grid">
      <div class="settings-stack">
        <section class="card settings-card">
          <h2>${icon("key")}DeepSeek API</h2>
          <p>填写你自己的 API Key。Key 默认保存在此浏览器的 localStorage；关闭「记住 Key」后只保留在本次标签页的 sessionStorage。</p>
          <div class="field">
            <label for="settings-key">API Key <span>sk-…</span></label>
            <div class="input-with-action">
              <input id="settings-key" type="${state.showKey ? "text" : "password"}" value="${escapeAttr(settings.apiKey || "")}" placeholder="sk-…" autocomplete="off" spellcheck="false">
              <button class="btn btn-icon btn-ghost" data-action="toggle-key" title="${state.showKey ? "隐藏" : "显示"}">${icon(state.showKey ? "eyeOff" : "eye")}</button>
            </div>
            <p class="field-note">浏览器只把 Key 发给你在下面配置的接口地址；若部署在公网，请使用 HTTPS。</p>
          </div>
          <div class="field">
            <label for="settings-base-url">接口地址 <span>Base URL</span></label>
            <input id="settings-base-url" value="${escapeAttr(settings.baseUrl || "https://api.deepseek.com")}" placeholder="https://api.deepseek.com" autocomplete="off">
            <p class="field-note">默认 https://api.deepseek.com，兼容 OpenAI Chat Completions 协议。</p>
          </div>
          <div class="toggle-row">
            <div class="toggle-copy"><strong>记住 API Key</strong><span>关闭后刷新页面需要重新输入。</span></div>
            <label class="switch"><input type="checkbox" data-toggle="rememberKey" ${settings.rememberKey ? "checked" : ""}><span></span></label>
          </div>
          <div class="settings-actions">
            <button class="btn btn-primary" data-action="save-api">${icon("check")}保存密钥</button>
            <button class="btn btn-secondary" data-action="test-connection" ${state.connection === "testing" ? "disabled" : ""}>${state.connection === "testing" ? "正在测试…" : `${icon("sparkle")}测试连接`}</button>
          </div>
          ${connection && connection !== "testing" ? `<div class="security-note">${icon(connection.type === "error" ? "alert" : "checkCircle")}<span>${escapeHtml(connection.text)}</span></div>` : ""}
          <div class="security-note">${icon("shield")}<span>Key 只保存在本机；解析请求经由本地 Node 服务转发，不会写入服务端日志。</span></div>
        </section>

        <section class="card settings-card">
          <h2>${icon("sparkle")}模型</h2>
          <p>选择题型模型名称。若你的网关使用自定义名称，直接输入即可。</p>
          <div class="field">
            <label for="settings-model">模型名称</label>
            <input id="settings-model" list="model-presets" value="${escapeAttr(settings.model || "deepseek-flash")}" placeholder="deepseek-flash" autocomplete="off">
            <datalist id="model-presets">${MODEL_PRESETS.map((model) => `<option value="${model}"></option>`).join("")}</datalist>
          </div>
          <div class="settings-actions">
            ${MODEL_PRESETS.map((model) => `<button class="btn btn-sm ${settings.model === model ? "btn-primary" : "btn-secondary"}" data-action="set-model" data-model="${model}">${model}</button>`).join("")}
            <button class="btn btn-sm btn-secondary" data-action="save-model">${icon("check")}保存模型</button>
          </div>
        </section>
      </div>

      <div class="settings-stack">
        <section class="card settings-card">
          <h2>${icon("settings")}行为偏好</h2>
          <div class="toggle-row">
            <div class="toggle-copy"><strong>保存解析历史</strong><span>关闭后新解析不会进入历史列表；收藏题仍会保留。</span></div>
            <label class="switch"><input type="checkbox" data-toggle="saveHistory" ${settings.saveHistory ? "checked" : ""}><span></span></label>
          </div>
          <div class="toggle-row">
            <div class="toggle-copy"><strong>演示模式</strong><span>不调用 API，用本地示例数据体验完整流程。</span></div>
            <label class="switch"><input type="checkbox" data-toggle="demoMode" ${settings.demoMode ? "checked" : ""}><span></span></label>
          </div>
          <div class="security-note">${icon("info")}<span>首次测试建议先开启演示模式，确认界面流程后再配置真实 Key。</span></div>
        </section>

        <section class="card settings-card">
          <h2>${icon("database")}本地数据</h2>
          <p>题目与图片保存在此浏览器的 IndexedDB，不会上传到除解析接口以外的服务器。</p>
          <div class="data-stats">
            <div class="data-stat"><strong id="stat-total">${visibleQuestions().length}</strong><span>历史题目</span></div>
            <div class="data-stat"><strong id="stat-size">—</strong><span>存储占用</span></div>
          </div>
          <div class="settings-actions">
            <button class="btn btn-sm btn-secondary" data-action="export-data">${icon("download")}导出 JSON</button>
            <button class="btn btn-sm btn-danger" data-action="clear-history">${icon("trash")}清空题目</button>
            <button class="btn btn-sm btn-ghost" data-action="clear-key">${icon("key")}清除 Key</button>
          </div>
        </section>

        <section class="card about-card">
          <div class="about-brand"><span class="brand-mark">${icon("logo")}</span><div><strong>医学拍题助手 · Web</strong><span>给医学生的本地解析工作台</span></div></div>
          <p>题库解析、图片观察、选项判断与追问都围绕课程学习设计。结果不是临床诊疗建议。</p>
          <div class="about-meta"><span>v1.0.0</span><span>Vanilla JS</span><span>IndexedDB</span><span>DeepSeek</span></div>
        </section>
      </div>
    </div>`;
}

function afterRender(route) {
  if (route.name === "compose") bindCompose();
  if (route.name === "settings") updateStats();
  if (route.name === "result") {
    const textarea = document.querySelector("#followup-text");
    if (textarea) {
      textarea.style.height = "auto";
      textarea.style.height = `${Math.min(textarea.scrollHeight, 110)}px`;
    }
  }
}

function updateStats() {
  const total = document.querySelector("#stat-total");
  if (total) total.textContent = String(visibleQuestions().length);
  const size = document.querySelector("#stat-size");
  if (!size) return;
  storageStats().then((stats) => {
    const bytes = Number(stats?.bytes || stats?.usage || 0);
    size.textContent = bytes ? formatBytes(bytes) : "—";
  }).catch(() => { size.textContent = "—"; });
}

function bindCompose() {
  const zone = document.querySelector("[data-dropzone]");
  if (!zone) return;
  ["dragenter", "dragover"].forEach((name) => {
    zone.addEventListener(name, (event) => {
      event.preventDefault();
      zone.classList.add("dragover");
    });
  });
  ["dragleave", "drop"].forEach((name) => {
    zone.addEventListener(name, (event) => {
      event.preventDefault();
      zone.classList.remove("dragover");
    });
  });
  zone.addEventListener("drop", (event) => {
    if (event.dataTransfer?.files?.length) addFiles(event.dataTransfer.files);
  });
}

async function addFiles(fileList) {
  const files = Array.from(fileList || []).filter((file) => file.type.startsWith("image/"));
  if (!files.length) return;
  const room = MAX_IMAGES - state.draft.images.length;
  if (room <= 0) {
    toast(`最多上传 ${MAX_IMAGES} 张图片。`, "error");
    return;
  }
  let failed = 0;
  for (const file of files.slice(0, room)) {
    try {
      const image = await compressFile(file);
      if (totalImageBytes(state.draft.images) + image.sizeBytes > MAX_TOTAL_BYTES) {
        toast("图片总量超过 18 MB，请删除部分图片或降低分辨率。", "error");
        break;
      }
      state.draft.images.push(image);
    } catch (error) {
      failed += 1;
      console.warn(error);
    }
  }
  if (files.length > room) toast(`已达到 ${MAX_IMAGES} 张上限，多余的图片未加入。`);
  if (failed) toast(`${failed} 张图片读取失败，请换一张重试。`, "error");
  render();
}

function setDraftText(value) {
  state.draft.text = value;
  const count = document.querySelector("#text-count");
  if (count) count.textContent = String(value.length);
}

function setDraftPrivacy(value) {
  state.draft.privacy = Boolean(value);
}

function validateDraft() {
  if (!state.draft.text.trim() && !state.draft.images.length) return "请输入题干，或至少上传一张题目图片。";
  if (state.draft.privacy !== true) return "请先勾选材料使用确认。";
  if (totalImageBytes(state.draft.images) > MAX_TOTAL_BYTES) return "图片总量超过 18 MB，请删除部分图片或降低分辨率。";
  return "";
}

async function startAnalysis() {
  if (state.busy) return;
  const error = validateDraft();
  if (error) {
    state.analysisError = error;
    toast(error, "error");
    render();
    return;
  }
  if (!state.settings.demoMode && !state.settings.apiKey) {
    state.analysisError = "还没有配置 API Key。请先去设置页填写，或开启演示模式。";
    toast(state.analysisError, "error");
    render();
    return;
  }
  state.analysisError = "";
  const editingId = state.editId;
  const editingQuestion = editingId ? state.questions.find((question) => question.id === editingId) : null;
  const draft = {
    type: state.draft.type,
    text: state.draft.text.trim(),
    images: state.draft.images.map((image) => ({ ...image }))
  };
  showBusy(
    state.settings.demoMode ? "正在生成演示解析" : "正在读题与图片",
    state.settings.demoMode ? "演示模式不会调用真实模型，马上就好。" : "正在识别题干、选项和图片细节，通常需要 10–60 秒。"
  );
  try {
    const result = await analyzeQuestion(state.settings, draft);
    const now = Date.now();
    const question = {
      id: editingQuestion?.id || uid("question"),
      createdAt: editingQuestion?.createdAt || now,
      updatedAt: now,
      questionType: draft.type,
      questionText: draft.text,
      images: draft.images.map(({ dataUrl, name, width, height, sizeBytes, warnings }) => ({ dataUrl, name, width, height, sizeBytes, warnings })),
      status: "completed",
      result,
      model: state.settings.model,
      errorMessage: "",
      favorite: editingQuestion?.favorite || false,
      followUps: [],
      isEphemeral: !state.settings.saveHistory && !editingQuestion?.favorite
    };
    await saveQuestion(question);
    await refreshQuestions();
    state.draft = newDraft();
    state.editId = null;
    hideBusy();
    navigate(`/result/${encodeURIComponent(question.id)}`);
    toast(state.settings.saveHistory || question.favorite ? "解析完成，已保存到历史记录。" : "解析完成。当前未开启历史保存，本题为临时结果。");
  } catch (error) {
    console.error(error);
    state.analysisError = error instanceof ApiError ? error.message : (error?.message || "解析失败，请稍后重试。");
    hideBusy();
    render();
    toast(state.analysisError, "error");
  }
}

async function sendFollowUp() {
  const question = currentQuestion();
  if (!question || state.followUpBusy) return;
  const text = state.followUpDraft.trim();
  const images = state.followUpImages.map((image) => ({ ...image }));
  if (!text && !images.length) {
    state.followUpError = "请输入追问内容，或附加一张图片。";
    render();
    return;
  }
  if (!state.settings.demoMode && !state.settings.apiKey) {
    state.followUpError = "尚未配置 API Key，无法追问。请先去设置页配置，或开启演示模式。";
    render();
    return;
  }
  state.followUpError = "";
  state.followUpBusy = true;
  const prior = (question.followUps || []).map((message) => ({ role: message.role, text: message.text || "" }));
  const userMessage = { role: "user", text, images, createdAt: Date.now() };
  question.followUps = [...(question.followUps || []), userMessage];
  state.followUpDraft = "";
  state.followUpImages = [];
  render();
  try {
    const answer = await askFollowUp(state.settings, question, text, images, prior);
    question.followUps = [...(question.followUps || []), { role: "assistant", text: answer, createdAt: Date.now() }];
    question.updatedAt = Date.now();
    await saveQuestion(question);
    await refreshQuestions();
    state.followUpBusy = false;
    render();
  } catch (error) {
    console.error(error);
    state.followUpBusy = false;
    state.followUpError = error instanceof ApiError ? error.message : (error?.message || "追问失败，请稍后重试。");
    render();
    toast(state.followUpError, "error");
  }
}

async function toggleFavorite(id) {
  const question = state.questions.find((item) => item.id === id);
  if (!question) return;
  question.favorite = !question.favorite;
  question.updatedAt = Date.now();
  if (question.favorite) question.isEphemeral = false;
  await saveQuestion(question);
  await refreshQuestions();
  render();
  toast(question.favorite ? "已收藏。收藏题始终保留在历史记录。" : "已取消收藏。");
}

function editQuestion(id) {
  const question = state.questions.find((item) => item.id === id);
  if (!question) return;
  state.draft = {
    type: question.questionType || question.result?.questionType || "auto",
    text: question.questionText || "",
    images: (question.images || []).map((image, index) => ({
      id: image.id || uid(`image-${index}`),
      name: image.name || `图片 ${index + 1}`,
      dataUrl: image.dataUrl,
      width: image.width,
      height: image.height,
      sizeBytes: image.sizeBytes,
      warnings: image.warnings || []
    })),
    privacy: true
  };
  state.editId = id;
  navigate("/compose");
}

async function deleteQuestion(id) {
  const question = state.questions.find((item) => item.id === id);
  if (!question) return;
  openConfirm("删除这道题？", "题目、图片与追问记录会从此浏览器移除，操作无法撤销。", "删除", async () => {
    await deleteQuestionRecord(id);
    await refreshQuestions();
    if (state.route.name === "result" && state.route.id === id) navigate("/history");
    else render();
    toast("题目已删除。");
  });
}

function copyText(text) {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text);
  const area = document.createElement("textarea");
  area.value = text;
  area.style.position = "fixed";
  area.style.opacity = "0";
  document.body.appendChild(area);
  area.select();
  document.execCommand("copy");
  area.remove();
  return Promise.resolve();
}

function answerForShare(question) {
  const result = question.result || {};
  const answer = result.answer?.length ? result.answer.join("、") : result.answerText || "暂无确定答案";
  const points = (result.keyPoints || []).map((item) => `· ${item}`).join("\n");
  return `${question.questionText || result.recognizedQuestion || "医学题目"}\n\n答案：${answer}${points ? `\n\n知识点：\n${points}` : ""}\n\n（由医学拍题助手生成，仅供学习参考）`;
}

async function shareQuestion(id) {
  const question = state.questions.find((item) => item.id === id);
  if (!question) return;
  const text = answerForShare(question);
  if (navigator.share) {
    try {
      await navigator.share({ title: "医学拍题解析", text });
      return;
    } catch (error) {
      if (error?.name === "AbortError") return;
    }
  }
  await copyText(text);
  toast("分享内容已复制到剪贴板。");
}

async function handleFiles(fileList) {
  await addFiles(fileList);
}

function openPreview(index) {
  const image = state.draft.images[index];
  if (!image) return;
  openModal(`<button class="btn btn-icon btn-secondary preview-close" data-action="close-modal">${icon("x")}</button><img src="${escapeAttr(image.dataUrl)}" alt="题目图片预览">`, "preview-modal");
}

function openCrop(index) {
  const image = state.draft.images[index];
  if (!image) return;
  openModal(`
    <h2>裁剪图片</h2>
    <p>在图片上拖动，框选需要保留的区域。裁剪只影响本次解析的副本。</p>
    <div class="crop-stage" id="crop-stage"><img id="crop-image" src="${escapeAttr(image.dataUrl)}" alt="裁剪预览"><div class="crop-box" id="crop-box"></div></div>
    <div class="crop-tools"><span class="crop-tip">提示：框选区域会自动吸附在图片范围内</span><button class="btn btn-secondary" data-action="close-modal">取消</button><button class="btn btn-primary" data-action="apply-crop" data-index="${index}">${icon("crop")}应用裁剪</button></div>`,
  "crop-modal");
  bindCrop(index);
}

function bindCrop(index) {
  const stage = document.querySelector("#crop-stage");
  const image = document.querySelector("#crop-image");
  const box = document.querySelector("#crop-box");
  if (!stage || !image || !box) return;
  state.crop = { index, imageRect: null, box: { x: 0, y: 0, width: 0, height: 0 }, drawing: false, startX: 0, startY: 0 };
  const positionBox = () => {
    const rect = state.crop.box;
    box.style.left = `${rect.x}px`;
    box.style.top = `${rect.y}px`;
    box.style.width = `${Math.max(1, rect.width)}px`;
    box.style.height = `${Math.max(1, rect.height)}px`;
  };
  const setDefault = () => {
    const stageRect = stage.getBoundingClientRect();
    const imageRect = image.getBoundingClientRect();
    const rect = {
      x: imageRect.left - stageRect.left,
      y: imageRect.top - stageRect.top,
      width: imageRect.width,
      height: imageRect.height
    };
    state.crop.imageRect = rect;
    const pad = Math.min(rect.width, rect.height) * 0.08;
    state.crop.box = { x: rect.x + pad, y: rect.y + pad, width: rect.width - pad * 2, height: rect.height - pad * 2 };
    positionBox();
  };
  if (image.complete) setDefault();
  else image.addEventListener("load", setDefault, { once: true });

  const point = (event) => {
    const rect = stage.getBoundingClientRect();
    const imageRect = state.crop.imageRect || { x: 0, y: 0, width: rect.width, height: rect.height };
    return {
      x: Math.max(imageRect.x, Math.min(imageRect.x + imageRect.width, event.clientX - rect.left)),
      y: Math.max(imageRect.y, Math.min(imageRect.y + imageRect.height, event.clientY - rect.top))
    };
  };
  const update = (event) => {
    if (!state.crop?.drawing) return;
    const p = point(event);
    const x = Math.min(state.crop.startX, p.x);
    const y = Math.min(state.crop.startY, p.y);
    state.crop.box = { x, y, width: Math.abs(p.x - state.crop.startX), height: Math.abs(p.y - state.crop.startY) };
    positionBox();
  };
  stage.addEventListener("pointerdown", (event) => {
    if (!state.crop?.imageRect || event.button !== 0) return;
    const p = point(event);
    state.crop.drawing = true;
    state.crop.startX = p.x;
    state.crop.startY = p.y;
    stage.setPointerCapture?.(event.pointerId);
    update(event);
  });
  stage.addEventListener("pointermove", update);
  ["pointerup", "pointercancel"].forEach((name) => stage.addEventListener(name, () => {
    if (state.crop) state.crop.drawing = false;
  }));
}

async function applyCrop(index) {
  const image = state.draft.images[index];
  const crop = state.crop;
  if (!image || !crop?.imageRect) return;
  const rect = crop.imageRect;
  const box = crop.box;
  if (box.width < 8 || box.height < 8) {
    toast("框选区域太小，请重新拖动。", "error");
    return;
  }
  const scaleX = image.width / rect.width;
  const scaleY = image.height / rect.height;
  showBusy("正在裁剪", "保留你框选的区域。");
  try {
    const result = await cropDataUrl(image.dataUrl, {
      x: (box.x - rect.x) * scaleX,
      y: (box.y - rect.y) * scaleY,
      width: box.width * scaleX,
      height: box.height * scaleY
    });
    const target = state.draft.images[index];
    target.dataUrl = result.dataUrl;
    target.width = result.width;
    target.height = result.height;
    target.sizeBytes = result.sizeBytes;
    target.name = `${target.name || "图片"}（已裁剪）`;
    closeModal();
    hideBusy();
    render();
    toast("裁剪完成。");
  } catch (error) {
    hideBusy();
    toast(error?.message || "裁剪失败，请重试。", "error");
  }
}

async function rotateImage(index) {
  const image = state.draft.images[index];
  if (!image) return;
  try {
    const result = await rotateDataUrl(image.dataUrl, 90);
    const target = state.draft.images[index];
    target.dataUrl = result.dataUrl;
    target.width = result.width;
    target.height = result.height;
    target.sizeBytes = result.sizeBytes;
    render();
  } catch (error) {
    toast(error?.message || "旋转失败，请重试。", "error");
  }
}

function moveImage(index, direction) {
  const target = index + direction;
  if (target < 0 || target >= state.draft.images.length) return;
  const [image] = state.draft.images.splice(index, 1);
  state.draft.images.splice(target, 0, image);
  render();
}

async function addFollowUpImages(fileList) {
  const files = Array.from(fileList || []).filter((file) => file.type.startsWith("image/"));
  if (!files.length) return;
  const room = 4 - state.followUpImages.length;
  for (const file of files.slice(0, Math.max(0, room))) {
    try {
      state.followUpImages.push(await compressFile(file));
    } catch (error) {
      toast(error?.message || "图片读取失败。", "error");
    }
  }
  render();
}

function saveFormSettings() {
  const settings = {
    ...state.settings,
    apiKey: document.querySelector("#settings-key")?.value.trim() ?? state.settings.apiKey,
    baseUrl: (document.querySelector("#settings-base-url")?.value.trim() || "https://api.deepseek.com").replace(/\/+$/, ""),
    model: document.querySelector("#settings-model")?.value.trim() || "deepseek-flash"
  };
  state.settings = settings;
  saveSettings(settings);
  return settings;
}

async function testSavedConnection() {
  if (state.connection === "testing") return;
  state.connection = "testing";
  render();
  try {
    const settings = saveFormSettings();
    const result = await testConnection(settings);
    state.connection = { type: "ok", text: result.message || "连接成功。" };
    toast("接口连接正常。");
  } catch (error) {
    state.connection = { type: "error", text: error instanceof ApiError ? error.message : (error?.message || "连接失败。") };
    toast(state.connection.text, "error");
  }
  render();
}

function exportData() {
  const payload = {
    app: "medical-question-ai-web",
    version: 1,
    exportedAt: new Date().toISOString(),
    settings: { ...state.settings, apiKey: "" },
    questions: state.questions.filter((question) => !question.isEphemeral)
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `medical-question-history-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(link.href);
  toast("已导出历史 JSON（不含 API Key）。");
}

async function clearAllData() {
  openConfirm("清空全部题目？", "所有历史、收藏和本地图片都会被删除，设置会保留。此操作无法撤销。", "全部清空", async () => {
    await clearQuestions();
    await refreshQuestions();
    render();
    toast("全部题目已清空。");
  });
}

function handleAction(action, element) {
  switch (action) {
    case "set-type":
      state.draft.type = element.dataset.type;
      render();
      break;
    case "load-sample":
      state.draft.text = SAMPLE_TEXT;
      state.draft.type = "single";
      render();
      toast("示例题已载入，可以直接开始解析。");
      break;
    case "cancel-edit":
      state.editId = null;
      state.draft = newDraft();
      navigate("/compose");
      break;
    case "add-images":
      document.querySelector("#image-input")?.click();
      break;
    case "add-camera":
      document.querySelector("#camera-input")?.click();
      break;
    case "preview-image":
      openPreview(Number(element.dataset.index));
      break;
    case "remove-image":
      state.draft.images.splice(Number(element.dataset.index), 1);
      render();
      break;
    case "move-image":
      moveImage(Number(element.dataset.index), Number(element.dataset.dir));
      break;
    case "rotate-image":
      rotateImage(Number(element.dataset.index));
      break;
    case "crop-image":
      openCrop(Number(element.dataset.index));
      break;
    case "apply-crop":
      applyCrop(Number(element.dataset.index));
      break;
    case "start-analysis":
      startAnalysis();
      break;
    case "go-history":
      navigate("/history");
      break;
    case "go-compose":
      state.draft = newDraft();
      state.editId = null;
      navigate("/compose");
      break;
    case "clear-search":
      state.historyQuery = "";
      render();
      break;
    case "open-question":
      navigate(`/result/${encodeURIComponent(element.dataset.id)}`);
      break;
    case "toggle-favorite":
      toggleFavorite(element.dataset.id);
      break;
    case "edit-question":
      editQuestion(element.dataset.id);
      break;
    case "delete-question":
      deleteQuestion(element.dataset.id);
      break;
    case "copy-answer": {
      const question = state.questions.find((item) => item.id === element.dataset.id);
      if (question) copyText(answerForShare(question)).then(() => toast("答案已复制。"));
      break;
    }
    case "share-answer":
      shareQuestion(element.dataset.id);
      break;
    case "send-followup":
      sendFollowUp();
      break;
    case "followup-image":
      document.querySelector("#followup-input")?.click();
      break;
    case "remove-followup-image":
      state.followUpImages.splice(Number(element.dataset.index), 1);
      render();
      break;
    case "history-filter":
      state.historyFilter = element.dataset.filter;
      render();
      break;
    case "clear-history":
      clearAllData();
      break;
    case "save-api": {
      saveFormSettings();
      state.connection = { type: "ok", text: "API Key 已保存在本机。" };
      render();
      toast("设置已保存。");
      break;
    }
    case "test-connection":
      testSavedConnection();
      break;
    case "toggle-key":
      state.showKey = !state.showKey;
      render();
      break;
    case "save-model": {
      saveFormSettings();
      render();
      toast("模型设置已保存。");
      break;
    }
    case "set-model": {
      const input = document.querySelector("#settings-model");
      if (input) input.value = element.dataset.model;
      saveFormSettings();
      render();
      toast("模型设置已保存。");
      break;
    }
    case "export-data":
      exportData();
      break;
    case "clear-key":
      openConfirm("清除 API Key？", "Key 会从本机存储中移除，下次解析前需要重新填写。", "清除", async () => {
        state.settings = { ...state.settings, apiKey: "" };
        clearStoredSettings();
        saveSettings(state.settings);
        render();
        toast("API Key 已清除。");
      });
      break;
    case "close-modal":
      closeModal();
      break;
    case "confirm-action": {
      const callback = state.confirmAction;
      state.confirmAction = null;
      closeModal();
      if (callback) Promise.resolve(callback()).catch((error) => toast(error?.message || "操作失败。", "error"));
      break;
    }
    default:
      break;
  }
}

document.addEventListener("click", (event) => {
  if (event.target.matches?.("[data-modal-backdrop]")) {
    closeModal();
    return;
  }
  const actionElement = event.target.closest?.("[data-action]");
  if (!actionElement) {
    if (event.target.matches?.("#history-search")) return;
    return;
  }
  const action = actionElement.dataset.action;
  if (["toggle-favorite", "delete-question"].includes(action)) event.stopPropagation();
  handleAction(action, actionElement);
});

document.addEventListener("input", (event) => {
  const target = event.target;
  if (target.id === "question-text") {
    setDraftText(target.value);
    return;
  }
  if (target.id === "history-search") {
    state.historyQuery = target.value;
    const list = document.querySelector("#history-list");
    const summary = document.querySelector("#history-summary");
    if (list) list.innerHTML = historyListHtml();
    if (summary) summary.textContent = historySummary();
    return;
  }
  if (target.id === "followup-text") {
    state.followUpDraft = target.value;
    target.style.height = "auto";
    target.style.height = `${Math.min(target.scrollHeight, 110)}px`;
  }
});

document.addEventListener("change", async (event) => {
  const target = event.target;
  if (target.id === "image-input" || target.id === "camera-input") {
    await handleFiles(target.files);
    target.value = "";
    return;
  }
  if (target.id === "followup-input") {
    await addFollowUpImages(target.files);
    target.value = "";
    return;
  }
  if (target.id === "privacy-check") {
    setDraftPrivacy(target.checked);
    return;
  }
  const toggle = target.dataset?.toggle;
  if (toggle) {
    state.settings = { ...state.settings, [toggle]: target.checked };
    saveSettings(state.settings);
    render();
    toast("设置已保存。");
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && (event.metaKey || event.ctrlKey) && event.target?.id === "followup-text") {
    event.preventDefault();
    sendFollowUp();
  }
  if (event.key === "Escape" && modalRoot.innerHTML) closeModal();
});

document.addEventListener("paste", async (event) => {
  if (state.route.name !== "compose") return;
  const files = Array.from(event.clipboardData?.files || []).filter((file) => file.type.startsWith("image/"));
  if (files.length) {
    event.preventDefault();
    await addFiles(files);
  }
});

window.addEventListener("hashchange", () => {
  closeModal();
  state.followUpDraft = "";
  state.followUpImages = [];
  state.followUpError = "";
  state.followUpBusy = false;
  state.analysisError = "";
  state.connection = null;
  render();
  window.scrollTo({ top: 0, behavior: "instant" });
});

app.addEventListener("dragstart", (event) => {
  const card = event.target.closest?.(".image-card");
  if (card) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", card.dataset.imageIndex);
    card.classList.add("dragging");
  }
});
app.addEventListener("dragend", (event) => {
  event.target.closest?.(".image-card")?.classList.remove("dragging");
});
app.addEventListener("dragover", (event) => {
  if (event.target.closest?.(".image-grid")) event.preventDefault();
});
app.addEventListener("drop", (event) => {
  const card = event.target.closest?.(".image-card");
  const source = Number(event.dataTransfer?.getData("text/plain"));
  if (!card || Number.isNaN(source)) return;
  const target = Number(card.dataset.imageIndex);
  if (source === target || !state.draft.images[source]) return;
  event.preventDefault();
  const [image] = state.draft.images.splice(source, 1);
  state.draft.images.splice(target, 0, image);
  render();
});

async function init() {
  try {
    state.settings = loadSettings();
    await refreshQuestions();
    if (!location.hash) history.replaceState(null, "", "#/compose");
    render();
  } catch (error) {
    console.error(error);
    app.innerHTML = `<div class="card empty-state" style="margin:40px auto;max-width:560px;">${emptyState("alert", "载入失败", error?.message || "浏览器存储不可用，请检查隐私模式设置。", "重试", "reload")}</div>`;
    document.addEventListener("click", (event) => {
      if (event.target.closest?.('[data-action="reload"]')) location.reload();
    }, { once: true });
  }
}

init();
