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
  storageStats,
  modelSupportsImages,
  getModelCapability,
  SETTINGS_VERSION,
  DEFAULT_SETTINGS
} from "./store.js";
import {
  analyzeQuestionStream,
  askFollowUpStream,
  testConnection,
  verifyModel,
  MODEL_PRESETS,
  ANSWER_MODES,
  DEFAULT_ANSWER_MODE,
  getModelPreset,
  normalizeAnswerModeValue,
  formatUsageLabel,
  ApiError
} from "./api.js";
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
const SAMPLE_TEXT = "患者，男，58岁，突发胸骨后压榨性疼痛2小时，伴大汗。心电图示Ⅱ、Ⅲ、aVF导联ST段抬高。最可能的诊断是：\nA. 急性下壁心肌梗死\nB. 不稳定型心绞痛\nC. 急性心包炎\nD. 主动脉夹层";
const MAX_IMAGES = 9;
const MAX_TOTAL_BYTES = 18 * 1024 * 1024;
const WEB_BUILD = "2026.10.29-web.1";
const STAGE_LABELS = {
  waiting: "正在连接模型",
  thinking: "模型正在分析题目",
  receiving: "正在接收解析",
  preview: "答案已生成，正在补全解析",
  validating: "正在整理与校验答案"
};
const QUESTION_STATUS = {
  completed: { label: "已完成", cls: "tag-teal" },
  analyzing: { label: "解析中", cls: "tag-blue" },
  needs_confirmation: { label: "待确认", cls: "tag-amber" },
  out_of_scope: { label: "超出范围", cls: "tag-gray" },
  failed: { label: "解析失败", cls: "tag-red" },
  cancelled: { label: "已取消", cls: "tag-gray" },
  interrupted: { label: "已中断", cls: "tag-amber" }
};

const state = {
  route: { name: "compose" },
  questions: [],
  settings: { ...DEFAULT_SETTINGS },
  draft: newDraft(),
  editId: null,
  historyQuery: "",
  historyFilter: "all",
  resultPrimaryOpen: false,
  resultSecondaryOpen: true,
  analysisError: "",
  analysis: null,
  followUpDraft: "",
  followUpImages: [],
  followUpIncludeImages: false,
  followUpBusy: false,
  followUpError: "",
  followUpController: null,
  followUpAbortReason: "",
  followUpStage: "",
  importingImages: false,
  connection: null,
  verify: null,
  saveStatus: "",
  showKey: false,
  historyDayLimit: 7,
  historyShowAll: false,
  busy: false,
  startingAnalysis: false,
  confirmAction: null,
  crop: null
};

function newDraft() {
  return { type: "auto", text: "", images: [], privacy: false };
}

function draftSubmitIssue(draft = state.draft, imagesAllowed = true) {
  if (state.busy) return "正在解析中，请稍候…";
  if (state.startingAnalysis) return "正在创建解析记录，请稍候…";
  if (state.importingImages) return "图片正在导入，请稍候…";
  if (!state.settings.demoMode && !state.settings.apiKey) return "先在设置中配置 API Key，或开启演示模式";
  if (!draft.text.trim() && !draft.images.length) return "先拍一张题目图片，或粘贴题干文字";
  if (draft.images.length && !imagesAllowed) return "当前模型不支持图片，请切换到 DeepSeek Flash，或在设置中允许自定义模型图片输入";
  if (draft.images.length && !draft.privacy) return "请先确认图片不含未遮挡的个人信息";
  return "";
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

function currentModel() {
  return state.settings.model || "deepseek-flash";
}

function currentModelPreset() {
  return getModelPreset(currentModel());
}

function currentMode() {
  return normalizeAnswerModeValue(state.settings.answerMode);
}

function currentModeMeta() {
  return ANSWER_MODES.find((mode) => mode.id === currentMode()) || ANSWER_MODES[1];
}

function resolvedTheme(theme = state.settings.theme) {
  if (theme === "dark" || theme === "light") return theme;
  return globalThis.matchMedia?.("(prefers-color-scheme: dark)")?.matches ? "dark" : "light";
}

function applyTheme() {
  const theme = resolvedTheme();
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
}

function questionStatus(question) {
  const key = question?.status || "completed";
  if (QUESTION_STATUS[key]) {
    if (key === "completed" && question?.result?.status === "needs_confirmation") return "needs_confirmation";
    if (key === "completed" && question?.result?.status === "out_of_scope") return "out_of_scope";
    return key;
  }
  return "completed";
}

function resultDisplayStatus(question, view = {}) {
  const stored = questionStatus(question);
  if (["failed", "cancelled", "interrupted", "analyzing"].includes(stored)) return stored;
  if (view.status === "answered") return "completed";
  if (view.status === "needs_confirmation") return "needs_confirmation";
  if (view.status === "out_of_scope") return "out_of_scope";
  return stored;
}

function confidencePercent(value) {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "string") {
    const word = value.trim().toLowerCase();
    if (["high", "高", "较高"].includes(word)) return 90;
    if (["medium", "mid", "中", "中等"].includes(word)) return 65;
    if (["low", "低", "较低"].includes(word)) return 35;
    if (word.endsWith("%")) {
      const n = Number(word.slice(0, -1));
      return Number.isFinite(n) ? Math.max(0, Math.min(100, Math.round(n))) : null;
    }
  }
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  if (n > 1 && n <= 100) return Math.max(0, Math.min(100, Math.round(n)));
  if (n >= 0 && n <= 1) return Math.round(n * 100);
  return null;
}

function statusChip(questionOrStatus, extraClass = "") {
  const status = typeof questionOrStatus === "string" ? questionOrStatus : questionStatus(questionOrStatus);
  const meta = QUESTION_STATUS[status] || QUESTION_STATUS.completed;
  return `<span class="tag ${meta.cls} ${extraClass}">${meta.label}</span>`;
}

function answerModeChips(compact = false) {
  return `
    <div class="mode-row ${compact ? "compact" : ""}" role="group" aria-label="做题模式">
      ${ANSWER_MODES.map((mode) => `
        <button class="mode-chip ${currentMode() === mode.id ? "active" : ""}" data-action="set-answer-mode" data-mode="${mode.id}" aria-pressed="${currentMode() === mode.id}">
          <span class="mode-chip-top"><strong>${mode.label}</strong>${mode.id === DEFAULT_ANSWER_MODE ? `<em>默认</em>` : ""}</span>
          <span class="mode-chip-desc">${mode.description}</span>
          <span class="mode-chip-meta">${Math.round(mode.timeoutMs / 1000)} 秒预算</span>
        </button>`).join("")}
    </div>`;
}

function modelCapabilityNotice() {
  const preset = currentModelPreset();
  const capability = getModelCapability(currentModel(), state.settings);
  if (capability.known) return "";
  const label = preset
    ? `当前接口使用「${preset.label}」模型名，但接口地址不是 DeepSeek 官方地址`
    : `当前使用自定义模型「${escapeHtml(currentModel())}」`;
  return `<div class="model-notice">${icon("info")}<span>${label}，能力未经验证：${capability.supportsImages ? "已手动开启图片输入" : "默认不发送图片，也不发送 thinking / JSON 参数"}。如遇问题请切换到 DeepSeek Flash。</span></div>`;
}

function formatDuration(ms) {
  const value = Number(ms);
  if (!Number.isFinite(value) || value < 0) return "";
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)} 秒`;
}

function stageLabel(stage) {
  return STAGE_LABELS[stage] || STAGE_LABELS.waiting;
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
          <p><span class="side-foot-dot"></span>图片与历史保存在此浏览器；API Key 随请求经本地代理发往你配置的接口。</p>
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
        <div class="page-content ${route.name === "compose" ? "page-compose" : ""}">${content}</div>
      </main>
    </div>`;
}

function renderComposePage() {
  const draft = state.draft;
  const editing = Boolean(state.editId);
  const actions = editing
    ? `<button class="btn btn-secondary" data-action="cancel-edit">${icon("x")}退出编辑</button>`
    : "";
  const imagesAllowed = modelSupportsImages(currentModel(), state.settings);
  const submitIssue = draftSubmitIssue(draft, imagesAllowed);
  const detailsOpen = editing || Boolean(draft.text.trim()) || draft.images.length > 0;
  return `
    ${pageHead({
      eyebrow: editing ? "EDIT & RE-ANALYZE" : "NEW QUESTION",
      title: editing ? "修改这道题" : "拍下题目，先看答案再看依据",
      description: editing
        ? "题干、题型和图片都可以修改；保存后会重新生成一份完整解析。"
        : "拍照或从相册选题，确认隐私后开始解析。文字、题型和做题模式都放在下面，需要时再展开。",
      actions
    })}
    ${apiBanner()}
    <div class="compose-grid compose-home">
      <section class="card composer-card home-card">
        <div class="capture-block">
          <div class="capture-topline">
            <div class="capture-kicker"><span class="capture-dot"></span>第一步 · 拍下题目</div>
            <span class="capture-count">${draft.images.length} / ${MAX_IMAGES} 张 · ${formatBytes(totalImageBytes(draft.images))}</span>
          </div>
          <div class="capture-actions">
            <button class="capture-primary" data-action="add-camera" ${state.importingImages ? "disabled" : ""}>
              <span class="capture-icon">${icon("camera")}</span>
              <span class="capture-copy"><strong>拍照 / 相机上传</strong><small>对准题目，尽量拍正、拍全、避开反光</small></span>
              <span class="capture-arrow">${icon("arrowRight")}</span>
            </button>
            <button class="capture-secondary" data-action="add-images" ${state.importingImages ? "disabled" : ""}>
              <span class="capture-icon">${icon("image")}</span>
              <span class="capture-copy"><strong>从相册选择</strong><small>支持 JPG / PNG / WEBP · 可多选</small></span>
            </button>
          </div>
          ${state.importingImages ? `<div class="importing-note"><span class="mini-spinner"></span>正在压缩并读取图片，请稍候…</div>` : ""}
          <input class="file-input" id="image-input" type="file" accept="image/*" multiple>
          <input class="file-input" id="camera-input" type="file" accept="image/*" capture="environment">

          ${draft.images.length ? `
            <div class="image-grid">
              ${draft.images.map((image, index) => `
                <div class="image-card" data-image-index="${index}">
                  <button class="image-thumb-wrap" data-action="edit-image" data-index="${index}" title="编辑这张图片" aria-label="编辑第 ${index + 1} 张图片">
                    <img class="image-thumb" src="${escapeAttr(image.dataUrl)}" alt="题目图片 ${index + 1}">
                    <span class="image-index">${index + 1}</span>
                    <span class="image-edit-hint">${icon("edit")}编辑</span>
                    ${image.warnings?.length ? `<span class="image-warning">${escapeHtml(image.warnings[0])}</span>` : ""}
                  </button>
                  <div class="image-name">${escapeHtml(image.name || `图片 ${index + 1}`)} · ${formatBytes(image.sizeBytes)}</div>
                </div>`).join("")}
            </div>` : ""}

          <div class="upload-zone compact" data-action="add-images" data-dropzone="true">
            <span class="upload-icon">${icon("image")}</span>
            <strong>也可以把图片拖到这里</strong>
            <span>最多 ${MAX_IMAGES} 张，单次解析图片总量不超过 18 MB</span>
          </div>
          ${!imagesAllowed && draft.images.length ? `<div class="model-block">${icon("alert")}<span>${escapeHtml(currentModel())} 不支持图片。请删除图片，或在设置中切换回 DeepSeek Flash 后再解析。</span><a class="btn btn-sm btn-secondary" href="#/settings">切换模型</a></div>` : ""}
        </div>

        <details class="compose-details" ${detailsOpen ? "open" : ""}>
          <summary>
            <span><strong>补充文字、选择题型</strong><small>有文字题干更准确，没有也可以只发图片</small></span>
            <span class="summary-toggle">${icon("chevronRight")}</span>
          </summary>
          <div class="compose-details-body">
            <div class="composer-section">
              <div class="section-topline">
                <div class="section-title"><span class="num">01</span>题干文字</div>
                <span class="section-hint">可直接粘贴题目 · 选填</span>
              </div>
              <div class="question-area">
                <textarea id="question-text" maxlength="6000" placeholder="把题干、选项或需要解释的内容粘贴到这里…">${escapeHtml(draft.text)}</textarea>
                <span class="textarea-meta"><span id="text-count">${draft.text.length}</span> / 6000</span>
              </div>
            </div>

            <div class="composer-section">
              <div class="section-topline">
                <div class="section-title"><span class="num">02</span>选择题型</div>
                <span class="section-hint">不确定就选自动识别</span>
              </div>
              <div class="type-row">
                ${Object.entries(TYPE_LABELS).map(([key, label]) => `
                  <button class="type-chip ${draft.type === key ? "active" : ""}" data-action="set-type" data-type="${key}">${label}</button>`).join("")}
              </div>
            </div>
          </div>
        </details>

        <div class="mode-block">
          <div class="section-topline">
            <div class="section-title"><span class="num">03</span>做题模式</div>
            <span class="section-hint">当前模型：${escapeHtml(currentModel())}</span>
          </div>
          ${answerModeChips()}
          ${modelCapabilityNotice()}
        </div>

        <div class="privacy-block">
          <div class="privacy-row">
            <label class="checkbox">
              <input id="privacy-check" type="checkbox" ${draft.privacy ? "checked" : ""}>
              <span>${icon("check")}</span>
            </label>
            <label class="privacy-copy" for="privacy-check">
              <strong>我确认图片不含未遮挡的姓名、病历号等个人信息</strong>
            </label>
          </div>
          <details class="privacy-details">
            <summary>图片会发送到哪里？</summary>
            <p>题目图片和文字会随解析请求发送到你配置的 API；请先遮挡姓名、病历号、联系方式等敏感信息。解析结果仅供学习参考。</p>
          </details>
          ${state.analysisError ? `<div class="error-banner">${icon("alert")}<span>${escapeHtml(state.analysisError)}</span></div>` : ""}
        </div>
      </section>

      <aside class="compose-aside">
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
            <div class="mini-label">拍摄建议</div>
            <p>页面第一屏只保留拍题入口；题干文字和题型收在下方，减少干扰。</p>
          </div>
        </div>
        ${editing ? "" : `
        <div class="sample-card">
          <div class="sample-label">没有题目？先试试示例</div>
          <p>一道心血管系统的单选题，包含 4 个选项。可以直接加载后点击「开始解析」。</p>
          <button data-action="load-sample">载入示例题 ${icon("arrowRight")}</button>
        </div>`}
      </aside>
    </div>
    <div class="submit-dock">
      <div class="submit-dock-inner">
        <div class="submit-note" id="submit-reason">${icon("shield")}<span>${escapeHtml(submitIssue || "解析结果仅供学习参考，请以教材和老师讲解为准")}</span></div>
        <button class="btn btn-primary btn-lg" data-action="start-analysis" ${submitIssue ? "disabled" : ""} aria-describedby="submit-reason">
          ${icon("sparkle")}${editing ? "重新解析" : "开始解析"}
        </button>
      </div>
    </div>`;
}

function resultAnswerText(result) {
  const answer = Array.isArray(result.answer) ? result.answer.filter(Boolean) : (result.answer ? [result.answer] : []);
  if (result.answerText) return String(result.answerText);
  if (answer.length) return answer.join("、");
  return "";
}

function analysisElapsedSeconds(question) {
  const started = Number(question.analysisStartedAt || 0);
  return started ? Math.max(0, Math.floor((Date.now() - started) / 1000)) : 0;
}

function analysisLiveHtml(question) {
  const started = question.analysisStartedAt;
  const elapsed = analysisElapsedSeconds(question);
  const stage = state.analysis?.questionId === question.id ? state.analysis.stage : (question.stage || "waiting");
  const count = state.analysis?.questionId === question.id ? state.analysis.contentLength : (question.receivedChars || 0);
  return `
    <div class="analysis-live" data-analysis-live data-question-id="${escapeAttr(question.id)}" data-started-at="${started || ""}" data-stage="${escapeAttr(stage)}">
      <div class="analysis-stage-row">
        <span class="live-spinner"></span>
        <strong data-analysis-stage>${escapeHtml(stageLabel(stage))}</strong>
        <span class="analysis-elapsed" data-analysis-elapsed>已等待 ${elapsed} 秒</span>
      </div>
      <div class="analysis-progress"><span data-analysis-bar style="width:${stage === "waiting" ? 8 : stage === "thinking" ? 34 : stage === "receiving" ? 68 : 90}%"></span></div>
      <div class="analysis-subline">
        <span data-analysis-count>${count ? `已接收 ${count.toLocaleString()} 字` : "正在等待首字节…"}</span>
        ${Array.isArray(question.images) && question.images.length ? `<span data-analysis-images>已发送 ${question.images.length} 张图片</span>` : ""}
        <span class="analysis-hint">${icon("info")}可随时停止，已生成的内容会保留</span>
      </div>
      <p class="analysis-waiting" data-analysis-waiting hidden>模型响应较慢，已等待超过 20 秒。可以继续等待，或停止后切换快速模式重试。</p>
      <button class="btn btn-sm btn-secondary analysis-cancel" data-action="cancel-analysis" data-id="${escapeAttr(question.id)}">${icon("x")}停止解析</button>
    </div>`;
}

function renderResultPage() {
  const question = currentQuestion();
  if (!question) {
    return `${pageHead({ eyebrow: "RESULT", title: "找不到这道题" })}
      <div class="card empty-state">${emptyState("history", "这道题可能已经被删除", "回到历史记录看看其他题目，或重新发起一次解析。", "返回历史", "go-history")}</div>`;
  }
  const status = questionStatus(question);
  const analyzing = status === "analyzing";
  const failed = status === "failed";
  const cancelled = status === "cancelled";
  const interrupted = status === "interrupted";
  const preview = question.preview || null;
  const result = question.result || {};
  const view = status !== "completed" && preview ? { ...result, ...preview } : result;
  const displayStatus = resultDisplayStatus(question, view);
  const outOfScope = view.status === "out_of_scope";
  const needsConfirmation = view.status === "needs_confirmation";
  const answerText = resultAnswerText(view);
  const answers = Array.isArray(view.answer) ? view.answer.filter(Boolean) : (view.answer ? [view.answer] : []);
  const answerChars = answerText.length;
  const confidence = confidencePercent(view.confidence);
  const mode = question.answerMode || currentMode();
  const modeMeta = ANSWER_MODES.find((item) => item.id === mode) || currentModeMeta();
  const hasAnswer = Boolean(answerText) && !outOfScope && !needsConfirmation;
  const isChoice = ["single", "multiple"].includes(view.questionType || question.questionType);

  let headline = answers.length ? answers.join("、") : (answerText || "正在解析这道题…");
  let subline = "";
  if (outOfScope) {
    headline = "这似乎不是一道医学题";
    subline = "系统没有生成医学解析，以免把无关内容当成题目作答。";
  } else if (failed) {
    headline = "这次解析没有完成";
    subline = question.errorMessage || "可以检查网络或模型设置后重新解析。";
  } else if (cancelled) {
    headline = "解析已取消";
    subline = "已停止本次模型请求。修改题干或直接重新解析都可以。";
  } else if (interrupted) {
    headline = "上次解析被中断";
    subline = "页面刷新或关闭时任务仍在进行。可以重新解析，历史记录不会丢失。";
  } else if (analyzing) {
    subline = hasAnswer ? "答案已生成，解析仍在补全；可以先看答案，也可继续等待。" : "正在读取题目与图片，答案出来后会立即显示。";
  } else if (needsConfirmation) {
    headline = "需要补充信息后重试";
    subline = "模型没有给出确定答案，请补充题干或上传更清晰的图片后重新解析。";
  } else if (hasAnswer) {
    subline = confidence !== null && confidence > 0 ? `已解析 · 模型置信度 ${confidence}% · 仅供参考` : "已解析 · 仅供学习参考";
  }

  const warningList = Array.isArray(view.warnings) ? view.warnings.filter(Boolean) : [];
  const warnings = warningList.length
    ? `<section class="card result-section warning-section"><h3>${icon("alert")}需要留意</h3><div class="warning-list">${warningList.map((item) => `<div class="warning-item">${icon("alert")}<span>${escapeHtml(item)}</span></div>`).join("")}</div></section>`
    : "";

  const recognized = view.recognizedQuestion
    ? `<div class="detail-block"><h4>${icon("eye")}识别到的题干</h4><p class="recognized-text">${nl2br(view.recognizedQuestion)}</p></div>`
    : "";
  const liveRecognized = analyzing && view.recognizedQuestion
    ? `<p class="live-recognized">${icon("eye")}<span>已识别：${escapeHtml(truncate(view.recognizedQuestion, 180))}</span></p>`
    : "";

  const observations = view.imageObservations?.length
    ? `<div class="detail-block"><h4>${icon("image")}图片观察</h4>
        ${view.imageObservations.map((item) => `
          <div class="observation-row">
            <span class="observation-index">${item.imageIndex || "?"}</span>
            <div class="observation-body"><p>${nl2br(item.observation || "暂无观察")}</p>${item.uncertainty ? `<small>不确定：${escapeHtml(item.uncertainty)}</small>` : ""}</div>
          </div>`).join("")}
      </div>` : "";

  const options = view.optionAnalyses?.length
    ? `<div class="detail-block"><h4>${icon("target")}选项分析</h4><div class="option-table">
        ${view.optionAnalyses.map((item) => {
          const verdict = item.verdict || "不确定";
          const cls = verdict.includes("正确") ? "verdict-correct" : verdict.includes("错误") ? "verdict-wrong" : "verdict-unknown";
          return `<div class="option-line"><span class="option-key">${escapeHtml(item.option || "?")}</span><span class="option-verdict ${cls}">${escapeHtml(verdict)}</span><span class="option-reason">${escapeHtml(item.reason || "")}</span></div>`;
        }).join("")}
      </div></div>` : "";

  const reasoning = view.reasoningSteps?.length
    ? `<div class="detail-block"><h4>${icon("lightbulb")}解题思路</h4><ol class="reasoning-list">
        ${view.reasoningSteps.map((step, index) => `<li class="reasoning-step"><span class="reason-num">${index + 1}</span><div><strong>${escapeHtml(step.title || `步骤 ${index + 1}`)}</strong><p>${nl2br(step.detail || "")}</p></div></li>`).join("")}
      </ol></div>` : "";

  const points = (view.keyPoints?.length || view.pitfalls?.length)
    ? `<div class="detail-block"><h4>${icon("book")}巩固与易错</h4><div class="point-grid">
        <div class="point-box"><div class="point-title">${icon("checkCircle")}关键知识点</div><ul>${(view.keyPoints || []).map((item) => `<li>${escapeHtml(item)}</li>`).join("") || "<li>暂无</li>"}</ul></div>
        <div class="point-box amber"><div class="point-title">${icon("alert")}易错点</div><ul>${(view.pitfalls || []).map((item) => `<li>${escapeHtml(item)}</li>`).join("") || "<li>暂无</li>"}</ul></div>
      </div></div>` : "";

  const originalImages = question.images?.length
    ? `<div class="detail-block"><h4>${icon("image")}原始题目图片</h4><div class="result-image-grid">${question.images.map((image, index) => `<button class="result-image" data-action="preview-result-image" data-index="${index}" data-id="${escapeAttr(question.id)}" title="查看原图"><img src="${escapeAttr(image.dataUrl)}" alt="题目图片 ${index + 1}"><span>${index + 1}</span></button>`).join("")}</div></div>`
    : "";

  const usageLabel = formatUsageLabel(view.usage || result.usage);
  const metaRows = `
    <div class="side-stat"><span>状态</span><strong>${QUESTION_STATUS[displayStatus].label}</strong></div>
    <div class="side-stat"><span>题型</span><strong>${typeLabel(view.questionType || question.questionType)}</strong></div>
    <div class="side-stat"><span>模式</span><strong>${modeMeta.label}</strong></div>
    <div class="side-stat"><span>模型</span><strong>${escapeHtml(question.model || currentModel())}</strong></div>
    <div class="side-stat"><span>创建时间</span><strong>${formatDate(question.createdAt)}</strong></div>
    ${question.durationMs ? `<div class="side-stat"><span>耗时</span><strong>${formatDuration(question.durationMs)}</strong></div>` : ""}
    ${result.usage?.totalTokens ? `<div class="side-stat"><span>Token</span><strong>${Number(result.usage.totalTokens).toLocaleString()} total</strong></div>` : ""}`;

  const primaryDetails = `
    <details class="card result-section result-details" data-details="primary" ${state.resultPrimaryOpen ? "open" : ""}>
      <summary>
        <span class="result-details-title"><strong>题目与原始图片</strong><small>识别题干、图片观察与原始题目图片</small></span>
        <span class="summary-toggle">${icon("chevronRight")}</span>
      </summary>
      <div class="result-details-body">
        ${recognized || `<p class="muted-line">模型尚未返回识别题干。</p>`}
        ${observations}
        ${originalImages}
        ${usageLabel ? `<div class="detail-usage">${icon("database")}${escapeHtml(usageLabel)}</div>` : ""}
      </div>
    </details>`;

  const secondaryDetails = `
    <details class="card result-section result-details reasoning-details" data-details="secondary" ${state.resultSecondaryOpen ? "open" : ""}>
      <summary>
        <span class="result-details-title"><strong>解题过程与知识点</strong><small>${isChoice ? "选项判断默认展开" : "关键步骤与巩固要点"}</small></span>
        <span class="summary-toggle">${icon("chevronRight")}</span>
      </summary>
      <div class="result-details-body">
        ${options}
        ${reasoning}
        ${points}
      </div>
    </details>`;

  return `
    <div class="result-head">
      <button class="result-back" data-action="go-history">${icon("arrowLeft")}返回历史</button>
      <div class="result-head-actions">
        <button class="btn btn-sm btn-secondary" data-action="toggle-favorite" data-id="${escapeAttr(question.id)}">${icon(question.favorite ? "starFilled" : "star")}<span>${question.favorite ? "已收藏" : "收藏"}</span></button>
        <button class="btn btn-sm btn-secondary" data-action="edit-question" data-id="${escapeAttr(question.id)}">${icon("edit")}<span>修改再解析</span></button>
        ${hasAnswer && status === "completed" ? `<button class="btn btn-sm btn-secondary" data-action="share-answer" data-id="${escapeAttr(question.id)}">${icon("share")}<span>分享</span></button>` : ""}
      </div>
    </div>
    <div class="result-layout">
      <div class="result-main">
        <section class="result-hero answer-card ${analyzing ? "is-streaming" : failed || cancelled || interrupted ? "is-incomplete" : ""}">
          <div class="result-meta">
            ${statusChip(displayStatus)}
            <span class="tag tag-blue">${typeLabel(view.questionType || question.questionType)}</span>
            <span class="tag tag-gray">${modeMeta.label}</span>
            ${question.favorite ? `<span class="tag tag-amber">${icon("starFilled")}已收藏</span>` : ""}
          </div>
          <h2 class="answer-main ${answerChars > 60 ? "answer-long" : ""}">${escapeHtml(headline)}</h2>
          ${subline ? `<p class="answer-sub">${escapeHtml(subline)}</p>` : ""}
          ${hasAnswer && !outOfScope ? `<div class="answer-options">${answers.map((item) => `<span class="answer-option">${escapeHtml(item)}</span>`).join("")}</div>${answers.length && answerText ? `<p class="answer-detail">${nl2br(answerText)}</p>` : ""}` : ""}
          ${analyzing && !hasAnswer && !view.recognizedQuestion ? `<div class="answer-skeleton"><span></span><span></span><span></span></div>` : ""}
          ${liveRecognized}
          ${analyzing ? analysisLiveHtml(question) : ""}
          ${failed || cancelled || interrupted ? `
            <div class="answer-recovery">
              ${question.errorMessage ? `<div class="error-banner">${icon("alert")}<span>${escapeHtml(question.errorMessage)}</span></div>` : ""}
              <button class="btn btn-primary" data-action="retry-analysis" data-id="${escapeAttr(question.id)}">${icon("sparkle")}重新解析</button>
            </div>` : ""}
          ${hasAnswer && !outOfScope && status === "completed" ? `
            <div class="answer-actions">
              <button class="btn btn-sm btn-secondary" data-action="copy-answer" data-id="${escapeAttr(question.id)}">${icon("copy")}复制答案</button>
              <button class="btn btn-sm btn-ghost" data-action="edit-question" data-id="${escapeAttr(question.id)}">${icon("edit")}核对题干后重新解析</button>
            </div>` : ""}
        </section>
        ${warnings}
        ${outOfScope ? "" : renderFollowUp(question)}
        ${primaryDetails}
        ${secondaryDetails}
      </div>
      <aside class="result-side">
        <div class="card result-side-card">
          ${metaRows}
        </div>
        <div class="card result-side-card side-actions">
          <button class="btn btn-sm btn-secondary" data-action="copy-answer" data-id="${escapeAttr(question.id)}" ${hasAnswer ? "" : "disabled"}>${icon("copy")}复制答案</button>
          <button class="btn btn-sm btn-secondary" data-action="delete-question" data-id="${escapeAttr(question.id)}">${icon("trash")}删除这道题</button>
        </div>
        <p class="side-note">本题解析由模型生成，仅用于课程学习；临床决策请以最新指南与带教老师意见为准。</p>
      </aside>
    </div>`;
}

function renderFollowUp(question) {
  const messages = question.followUps || [];
  const busy = state.followUpBusy;
  const canIncludeOriginal = modelSupportsImages(currentModel(), state.settings) && Boolean(question.images?.length);
  const quickQuestions = ["为什么排除其他选项？", "这道题的诊断标准是什么？", "帮我总结易错点"];
  return `
    <section class="card followup-card">
      <div class="followup-title"><h3>${icon("send")}继续追问</h3><span>围绕这道题继续问</span></div>
      <div class="follow-list follow-scroll">
        ${messages.length ? messages.map((message) => {
          const usageLabel = message.role === "assistant" ? formatUsageLabel(message.usage) : "";
          return `
          <div class="follow-msg ${message.role === "user" ? "user" : ""}">
            <span class="follow-avatar">${message.role === "user" ? "我" : "AI"}</span>
            <div class="follow-bubble">
              <span ${message.streaming ? `data-followup-live="${escapeAttr(message.createdAt)}"` : ""}>${nl2br(message.text || "")}</span>
              ${(message.images || []).map((image) => `<img src="${escapeAttr(image.dataUrl)}" alt="追问图片">`).join("")}
              ${message.streaming ? `<div class="follow-stream-state"><span class="mini-spinner"></span><span data-followup-stage>${escapeHtml(stageLabel(state.followUpStage || "waiting"))}</span></div>` : ""}
              ${message.cancelled ? `<div class="follow-stopped">${icon("x")}已停止生成，保留已生成内容</div>` : ""}
              ${message.interrupted ? `<div class="follow-stopped">${icon("alert")}页面切换，追问已中断，保留已生成内容</div>` : ""}
              ${message.failed ? `<div class="follow-stopped follow-failed">${icon("alert")}生成中断，以上是已生成内容，可重试</div>` : ""}
              ${usageLabel ? `<div class="follow-usage">${icon("database")}${escapeHtml(usageLabel)}</div>` : ""}
            </div>
          </div>`;
        }).join("") : `<div class="follow-empty">还有哪里没看懂？可以问「为什么排除 C」或「这道题的诊断标准是什么」。</div>`}
        ${busy && !messages.some((message) => message.streaming) ? `<div class="follow-busy"><span class="mini-spinner"></span>正在结合原题思考…</div>` : ""}
      </div>
      ${state.followUpError ? `<div class="error-banner">${icon("alert")}<span>${escapeHtml(state.followUpError)}</span></div>` : ""}
      ${state.followUpImages.length ? `<div class="follow-attachment">${state.followUpImages.map((image, index) => `<img class="thumb" src="${escapeAttr(image.dataUrl)}" alt="追问图 ${index + 1}"><button data-action="remove-followup-image" data-index="${index}">移除</button>`).join("")}</div>` : ""}
      <div class="follow-dock">
        <div class="quick-follow-row" aria-label="快捷问题">
          ${quickQuestions.map((questionText) => `<button class="quick-follow" data-action="quick-followup" data-text="${escapeAttr(questionText)}" ${busy ? "disabled" : ""}>${escapeHtml(questionText)}</button>`).join("")}
        </div>
        <div class="follow-input-wrap">
          <textarea id="followup-text" rows="1" placeholder="继续问这道题…">${escapeHtml(state.followUpDraft)}</textarea>
          <div class="follow-tools">
            <button class="btn btn-icon btn-ghost" data-action="followup-image" title="附加图片" ${busy ? "disabled" : ""}>${icon("image")}</button>
            ${busy
              ? `<button class="btn btn-secondary follow-send stop" data-action="stop-followup" title="停止生成">${icon("x")}停止</button>`
              : `<button class="btn btn-primary follow-send" data-action="send-followup" title="发送">${icon("send")}</button>`}
          </div>
        </div>
        <label class="follow-include-toggle" title="${canIncludeOriginal ? "" : "当前模型未声明支持图片，或本题没有原图"}">
          <input id="follow-include-images" type="checkbox" ${state.followUpIncludeImages ? "checked" : ""} ${canIncludeOriginal ? "" : "disabled"}>
          <span>本次追问附带原题图片（默认关闭，可减少重复发送${canIncludeOriginal ? "" : "，当前不可用"}）</span>
        </label>
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
    const preview = question.preview || {};
    return [
      question.questionText,
      result.recognizedQuestion,
      preview.recognizedQuestion,
      result.answerText,
      preview.answerText,
      Array.isArray(result.answer) ? result.answer.join(" ") : String(result.answer || ""),
      Array.isArray(preview.answer) ? preview.answer.join(" ") : String(preview.answer || ""),
      (result.keyPoints || []).join(" "),
      (result.reasoningSteps || []).map((step) => `${step.title} ${step.detail}`).join(" "),
      question.errorMessage
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
  const status = questionStatus(question);
  if (status === "analyzing") {
    if (question.preview?.answerText) return question.preview.answerText;
    if (question.preview?.recognizedQuestion) return `正在解析：${question.preview.recognizedQuestion}`;
    return "正在连接模型，答案生成后会立即显示";
  }
  if (status === "failed") return question.errorMessage ? `解析失败：${question.errorMessage}` : "解析失败，可重新解析";
  if (status === "cancelled") return "解析已取消，可重新解析";
  if (status === "interrupted") return "上次解析被中断，可重新解析";
  if (status === "needs_confirmation") return "需要补充题干或更清晰的图片后重试";
  if (result.status === "out_of_scope") return "非医学题目，未生成解析";
  const summaryAnswers = Array.isArray(result.answer) ? result.answer.filter(Boolean) : (result.answer ? [result.answer] : []);
  if (summaryAnswers.length) return `答案：${summaryAnswers.join("、")}`;
  if (result.answerText) return result.answerText;
  return result.recognizedQuestion || "等待补充信息";
}

function historyDayKey(timestamp) {
  const date = new Date(timestamp || Date.now());
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function historyDayLabel(timestamp) {
  const date = new Date(timestamp || Date.now());
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (historyDayKey(date) === historyDayKey(today)) return "今天";
  if (historyDayKey(date) === historyDayKey(yesterday)) return "昨天";
  return new Intl.DateTimeFormat("zh-CN", {
    year: date.getFullYear() === today.getFullYear() ? undefined : "numeric",
    month: "long",
    day: "numeric",
    weekday: "short"
  }).format(date);
}

function historyListHtml() {
  const allItems = filteredHistory();
  if (!allItems.length) {
    return emptyState(
      "history",
      state.historyQuery ? "没有匹配的题目" : "还没有解析记录",
      state.historyQuery ? "换一个关键词试试，或清空搜索条件。" : "去新建一次解析，结果会自动保存在这里。",
      state.historyQuery ? "清空搜索" : "开始解析",
      state.historyQuery ? "clear-search" : "go-compose"
    );
  }
  const items = allItems;
  const hiddenCount = 0;
  if (!items.length) {
    return emptyState("history", "还没有解析记录", "去新建一次解析，结果会自动保存在这里。", "开始解析", "go-compose");
  }
  let lastDay = "";
  const grouped = items.map((question) => {
    const day = historyDayKey(question.createdAt);
    const header = day !== lastDay ? `<div class="history-day"><span>${escapeHtml(historyDayLabel(question.createdAt))}</span><i></i></div>` : "";
    lastDay = day;
    const status = questionStatus(question);
    const result = question.result || {};
    return `${header}
    <article class="card history-card" data-action="open-question" data-id="${escapeAttr(question.id)}" tabindex="0">
      <div class="history-type">${icon(typeIcon(result.questionType || question.questionType))}</div>
      <div class="history-body">
        <h3>${escapeHtml(truncate(question.questionText || question.preview?.recognizedQuestion || result.recognizedQuestion || "图片题", 74))}</h3>
        <p>${escapeHtml(truncate(questionSummary(question), 96))}</p>
        <div class="history-tags">
          ${statusChip(status)}
          <span class="tag tag-blue">${typeLabel(result.questionType || question.questionType)}</span>
          ${result.status === "needs_confirmation" ? `<span class="tag tag-amber">需核对</span>` : ""}
          ${question.favorite ? `<span class="tag tag-amber">${icon("starFilled")}收藏</span>` : ""}
        </div>
      </div>
      <div class="history-side">
        <span class="history-date">${formatDate(question.createdAt)}</span>
        <button class="btn btn-icon btn-ghost history-fav" data-action="toggle-favorite" data-id="${escapeAttr(question.id)}" title="${question.favorite ? "取消收藏" : "收藏"}">${icon(question.favorite ? "starFilled" : "star")}</button>
        <button class="btn btn-icon btn-ghost history-actions" data-action="delete-question" data-id="${escapeAttr(question.id)}" title="删除">${icon("trash")}</button>
      </div>
    </article>`;
  }).join("");
  return `${grouped}${hiddenCount ? `<button class="btn btn-secondary history-more" data-action="show-all-history">显示更早记录（${hiddenCount} 道）</button>` : ""}`;
}

function emptyState(iconName, title, text, actionLabel, action) {
  return `<div class="empty-state"><div class="empty-icon">${icon(iconName)}</div><h2>${escapeHtml(title)}</h2><p>${escapeHtml(text)}</p><button class="btn btn-primary" data-action="${action}">${escapeHtml(actionLabel)}</button></div>`;
}

function renderSettingsPage() {
  const settings = state.settings;
  const connection = state.connection;
  const activePreset = getModelPreset(settings.model);
  const customModel = activePreset ? "" : settings.model || "";
  const capability = getModelCapability(settings.model, settings);
  const saveText = state.saveStatus === "saved"
    ? "接口设置已保存，尚未测试连接。"
    : state.saveStatus === "error"
      ? "接口设置保存失败，请检查浏览器存储。"
      : "";
  return `
    ${pageHead({ eyebrow: "PREFERENCES", title: "设置", description: "密钥与连接、做题模式、数据与隐私、高级接口分四组管理。设置保存在当前浏览器。" })}
    ${settings.migrationNotice ? `<div class="migration-banner">${icon("sparkle")}<div><strong>旧设置已升级到 v${SETTINGS_VERSION}</strong><span>官方接口下的旧模型名已自动映射到 deepseek-flash，并按原使用意图保留「快速 / 详细」模式；自定义接口的模型名保持不变。默认做题模式为「标准」。</span></div></div>` : ""}
    <div class="settings-grid settings-four">
      <div class="settings-stack">
        <section class="card settings-card" id="settings-connection-section">
          <div class="section-topline"><div class="section-title"><span class="num">01</span>${icon("key")}密钥与连接</div><span class="section-hint">只保存在当前浏览器</span></div>
          <p>API Key 和接口地址由本地 Node 代理转发；关闭“记住 API Key”后只在当前标签页会话中保留。</p>
          <div class="field">
            <label for="settings-key">API Key <span>sk-…</span></label>
            <div class="input-with-action">
              <input id="settings-key" type="${state.showKey ? "text" : "password"}" value="${escapeAttr(settings.apiKey || "")}" placeholder="sk-…" autocomplete="off" spellcheck="false">
              <button class="btn btn-icon btn-ghost" data-action="toggle-key" title="${state.showKey ? "隐藏" : "显示"}">${icon(state.showKey ? "eyeOff" : "eye")}</button>
            </div>
            <p class="field-note">Key 会经本地 Node 代理转发到你填写的接口；浏览器存储不是加密存储。</p>
          </div>
          <div class="field">
            <label for="settings-base-url">接口地址 <span>Base URL</span></label>
            <input id="settings-base-url" value="${escapeAttr(settings.baseUrl || "https://api.deepseek.com")}" placeholder="https://api.deepseek.com" autocomplete="off">
            <p class="field-note">默认 https://api.deepseek.com。自定义网关通常兼容 OpenAI Chat Completions；http:// 传输不具备加密保护。</p>
          </div>
          <div class="toggle-row">
            <div class="toggle-copy"><strong>记住 API Key</strong><span>关闭后仅保留在当前标签页会话；关闭标签页后清除。</span></div>
            <label class="switch"><input type="checkbox" data-toggle="rememberKey" ${settings.rememberKey ? "checked" : ""}><span></span></label>
          </div>
          <div class="settings-actions">
            <button class="btn btn-primary" data-action="save-api">${icon("check")}保存接口设置</button>
            <button class="btn btn-secondary" data-action="test-connection" ${connection === "testing" ? "disabled" : ""}>${connection === "testing" ? "正在测试…" : `${icon("sparkle")}测试连接`}</button>
          </div>
          ${saveText ? `<div class="security-note">${icon(state.saveStatus === "error" ? "alert" : "info")}<span>${escapeHtml(saveText)}</span></div>` : ""}
          ${connection && connection !== "testing" ? `<div class="security-note">${icon(connection.type === "error" ? "alert" : "checkCircle")}<span>${escapeHtml(connection.text)}</span></div>` : ""}
          <div class="security-note">${icon("shield")}<span>浏览器将 Key 发给本地 Node 代理（默认仅监听 127.0.0.1）；代理进程可接触 Key 和请求内容，再转发到上方接口地址。请勿把代理端口暴露到公网。</span></div>
        </section>

        <section class="card settings-card">
          <div class="section-topline"><div class="section-title"><span class="num">02</span>${icon("lightbulb")}做题模式</div><span class="section-hint">默认${(ANSWER_MODES.find((mode) => mode.id === DEFAULT_ANSWER_MODE) || ANSWER_MODES[1]).label}</span></div>
          <p>快速模式关闭思考并限制输出预算；标准与详细模式开启思考，分别使用低、高推理强度。模式随每次解析请求发送，模型与思考强度彼此独立。</p>
          ${answerModeChips()}
        </section>
      </div>

      <div class="settings-stack">
        <section class="card settings-card">
          <div class="section-topline"><div class="section-title"><span class="num">03</span>${icon("database")}数据与隐私</div></div>
          <div class="toggle-row">
            <div class="toggle-copy"><strong>保存解析历史</strong><span>关闭后新解析不会进入历史列表；收藏题仍会保留。</span></div>
            <label class="switch"><input type="checkbox" data-toggle="saveHistory" ${settings.saveHistory ? "checked" : ""}><span></span></label>
          </div>
          <div class="toggle-row">
            <div class="toggle-copy"><strong>演示模式</strong><span>不调用 API，用本地示例数据体验完整流程。</span></div>
            <label class="switch"><input type="checkbox" data-toggle="demoMode" ${settings.demoMode ? "checked" : ""}><span></span></label>
          </div>
          <div class="field">
            <label for="settings-theme">外观主题</label>
            <select id="settings-theme" data-setting="theme">
              <option value="auto" ${settings.theme === "auto" ? "selected" : ""}>跟随系统</option>
              <option value="light" ${settings.theme === "light" ? "selected" : ""}>浅色</option>
              <option value="dark" ${settings.theme === "dark" ? "selected" : ""}>深色</option>
            </select>
          </div>
          <div class="security-note">${icon("shield")}<span>题目文字与所选图片仅在点击解析或追问时，经本地代理发往你配置的接口。请先遮挡姓名、病历号、联系方式等个人信息。</span></div>
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

        <section class="card settings-card">
          <div class="section-topline"><div class="section-title"><span class="num">04</span>${icon("sparkle")}高级接口</div><span class="section-hint">模型能力与验证</span></div>
          <p>官方预设的能力由应用声明；自定义模型能力未知，默认按“不支持图片、不支持 thinking / JSON”降级发送。只有你手动确认后才发送图片。</p>
          <div class="preset-grid model-options">
            ${MODEL_PRESETS.map((model) => `
              <button class="preset-card model-option ${settings.model === model.id ? "active" : ""}" data-action="set-model" data-model="${model.id}">
                <span class="preset-title model-option-top"><strong>${model.label}</strong><em>${model.supportsImages ? model.badge : "Pro"}</em></span>
                <span class="preset-desc model-option-desc">${model.description}</span>
                <span class="preset-badges"><span class="preset-badge ${model.supportsImages ? "" : "warn"}">${model.supportsImages ? "支持图片 + 文字" : "不支持图片"}</span><span class="preset-badge">${model.id}</span></span>
              </button>`).join("")}
          </div>
          <div class="field">
            <label for="settings-model">自定义模型名称 <span>高级</span></label>
            <input id="settings-model" list="model-presets" value="${escapeAttr(customModel || settings.model || "deepseek-flash")}" placeholder="deepseek-flash" autocomplete="off">
            <datalist id="model-presets">${MODEL_PRESETS.map((model) => `<option value="${model.id}"></option>`).join("")}</datalist>
            <p class="field-note">自定义模型默认不发送图片、thinking 或 response_format；如网关确认支持图片输入，可在下方手动开启。</p>
          </div>
          <div class="toggle-row">
            <div class="toggle-copy"><strong>自定义模型支持图片</strong><span>仅在你确认第三方网关接受 image_url 时开启；默认关闭。</span></div>
            <label class="switch"><input type="checkbox" data-toggle="customSupportsImages" ${settings.customSupportsImages ? "checked" : ""}><span></span></label>
          </div>
          <div class="settings-actions">
            <button class="btn btn-primary" data-action="save-model">${icon("check")}保存模型设置</button>
            <button class="btn btn-secondary" data-action="verify-model" ${state.verify === "testing" ? "disabled" : ""}>${state.verify === "testing" ? "正在验证…" : `${icon("target")}验证模型可用性`}</button>
          </div>
          ${state.verify && state.verify !== "testing" ? `<div class="security-note">${icon(state.verify.type === "error" ? "alert" : "checkCircle")}<span>${escapeHtml(state.verify.text)}</span></div>` : ""}
          ${capability.known ? "" : `<div class="security-note">${icon("info")}<span>当前是自定义模型：图片 ${capability.supportsImages ? "已手动允许" : "默认不发送"}，thinking / JSON 参数默认不发送。</span></div>`}
        </section>

        <section class="card about-card">
          <div class="about-brand"><span class="brand-mark">${icon("logo")}</span><div><strong>医学拍题助手 · Web</strong><span>给医学生的本地解析工作台</span></div></div>
          <p>题库解析、图片观察、选项判断与追问都围绕课程学习设计。结果不是临床诊疗建议。</p>
          <div class="about-meta"><span>v1.1.0</span><span>设置 v${SETTINGS_VERSION}</span><span>构建 ${WEB_BUILD}</span><span>Vanilla JS</span></div>
        </section>
      </div>
    </div>`;
}

function afterRender(route) {
  if (route.name === "compose") bindCompose();
  if (route.name === "settings") updateStats();
  if (route.name === "result") {
    const question = currentQuestion();
    if (question) updateAnalysisLive(question);
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

function imageFingerprint(file) {
  return `${file.name || "image"}|${file.size || 0}|${file.lastModified || 0}`;
}

function dataUrlFingerprint(dataUrl) {
  let hash = 2166136261;
  const text = String(dataUrl || "");
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `${text.length}:${hash >>> 0}`;
}

async function addFiles(fileList) {
  const files = Array.from(fileList || []).filter((file) => file.type.startsWith("image/"));
  if (!files.length) return;
  if (state.importingImages) return;
  state.importingImages = true;
  state.analysisError = "";
  render();
  let failed = 0;
  let duplicated = 0;
  try {
    const room = MAX_IMAGES - state.draft.images.length;
    if (room <= 0) {
      toast(`最多上传 ${MAX_IMAGES} 张图片。`, "error");
      return;
    }
    const seen = new Set();
    for (const image of state.draft.images) {
      if (image.dataUrl) seen.add(dataUrlFingerprint(image.dataUrl));
    }
    let added = 0;
    for (const file of files) {
      if (added >= room) break;
      const sourceKey = imageFingerprint(file);
      try {
        const image = await compressFile(file);
        const contentKey = dataUrlFingerprint(image.dataUrl);
        if (seen.has(contentKey)) {
          duplicated += 1;
          continue;
        }
        if (totalImageBytes(state.draft.images) + image.sizeBytes > MAX_TOTAL_BYTES) {
          toast("图片总量超过 18 MB，请删除部分图片或降低分辨率。", "error");
          break;
        }
        image.sourceKey = sourceKey;
        state.draft.images.push(image);
        seen.add(contentKey);
        added += 1;
      } catch (error) {
        failed += 1;
        console.warn(error);
      }
    }
    if (files.length > room) toast(`已达到 ${MAX_IMAGES} 张上限，多余的图片未加入。`);
    if (duplicated) toast(`${duplicated} 张重复图片已跳过。`, "info");
    if (failed) toast(`${failed} 张图片读取失败，请换一张重试。`, "error");
    if (added) toast(`已加入 ${added} 张题目图片。`);
  } finally {
    state.importingImages = false;
    render();
  }
}

function updateSubmitDock() {
  const issue = draftSubmitIssue(state.draft, modelSupportsImages(currentModel(), state.settings));
  const note = document.querySelector("#submit-reason span");
  if (note) note.textContent = issue || "解析结果仅供学习参考，请以教材和老师讲解为准";
  const button = document.querySelector("[data-action='start-analysis']");
  if (button) button.disabled = Boolean(issue);
}

function setDraftText(value) {
  state.draft.text = value;
  const count = document.querySelector("#text-count");
  if (count) count.textContent = String(value.length);
  updateSubmitDock();
}

function setDraftPrivacy(value) {
  state.draft.privacy = Boolean(value);
  updateSubmitDock();
}

function validateDraft() {
  const imagesAllowed = modelSupportsImages(currentModel(), state.settings);
  return draftSubmitIssue(state.draft, imagesAllowed);
}

function analysisElapsedText(question) {
  const seconds = analysisElapsedSeconds(question);
  return seconds ? `已等待 ${seconds} 秒` : "刚刚开始";
}

function updateAnalysisLive(question = currentQuestion()) {
  if (!question || !state.analysis || state.analysis.questionId !== question.id) return;
  const live = document.querySelector("[data-analysis-live]");
  if (!live) return;
  const stageNode = live.querySelector("[data-analysis-stage]");
  const elapsedNode = live.querySelector("[data-analysis-elapsed]");
  const countNode = live.querySelector("[data-analysis-count]");
  const bar = live.querySelector("[data-analysis-bar]");
  const waiting = live.querySelector("[data-analysis-waiting]");
  const stage = state.analysis.stage || "waiting";
  if (stageNode) stageNode.textContent = stageLabel(stage);
  if (elapsedNode) elapsedNode.textContent = analysisElapsedText(question);
  if (countNode) {
    countNode.textContent = state.analysis.contentLength
      ? `已接收 ${state.analysis.contentLength.toLocaleString()} 字`
      : "正在等待首字节…";
  }
  if (bar) {
    const widths = { waiting: 8, thinking: 34, receiving: 68, preview: 88, validating: 94 };
    bar.style.width = `${widths[stage] || 8}%`;
  }
  const waited = Date.now() - Number(question.analysisStartedAt || Date.now());
  if (waiting) waiting.hidden = waited < 20000 || stage === "validating";
}

function startAnalysisTicker(question) {
  if (state.analysis?.timer) window.clearInterval(state.analysis.timer);
  if (state.analysis) {
    state.analysis.timer = window.setInterval(() => updateAnalysisLive(question), 1000);
  }
}

function stopAnalysisRuntime() {
  if (state.analysis?.timer) window.clearInterval(state.analysis.timer);
  state.analysis = null;
}

function setAnalysisStage(stage) {
  if (!state.analysis) return;
  state.analysis.stage = stage;
  const live = document.querySelector("[data-analysis-live]");
  if (live) live.dataset.stage = stage;
  updateAnalysisLive();
}

async function startAnalysis() {
  if (state.analysis || state.startingAnalysis) {
    toast("已有解析正在进行，请先停止或等待完成。", "error");
    return;
  }
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
  const previousQuestions = state.questions;
  const previousDraft = { ...state.draft, images: state.draft.images.map((image) => ({ ...image })) };
  const previousEditId = state.editId;
  const editingId = state.editId;
  const editingQuestion = editingId ? state.questions.find((question) => question.id === editingId) : null;
  const draft = {
    type: state.draft.type,
    text: state.draft.text.trim(),
    images: state.draft.images.map((image) => ({ ...image })),
    privacy: true
  };
  const now = Date.now();
  const question = {
    id: editingQuestion ? uid("question-temp") : uid("question"),
    previousQuestionId: editingId || undefined,
    createdAt: editingQuestion?.createdAt || now,
    updatedAt: now,
    questionType: draft.type,
    questionText: draft.text,
    images: draft.images.map(({ dataUrl, name, width, height, sizeBytes, warnings }) => ({ dataUrl, name, width, height, sizeBytes, warnings })),
    status: "analyzing",
    stage: "waiting",
    result: {},
    preview: null,
    model: currentModel(),
    answerMode: currentMode(),
    errorMessage: "",
    analysisStartedAt: now,
    receivedChars: 0,
    durationMs: 0,
    favorite: editingQuestion?.favorite || false,
    followUps: editingQuestion?.followUps || [],
    isEphemeral: !state.settings.saveHistory && !editingQuestion?.favorite
  };
  const controller = new AbortController();
  state.startingAnalysis = true;
  try {
    await saveQuestion(question);
  } catch (saveError) {
    state.startingAnalysis = false;
    try { await deleteQuestionRecord(question.id); } catch { /* 写入失败时清理可能留下的半条记录 */ }
    state.questions = previousQuestions;
    state.draft = previousDraft;
    state.editId = previousEditId;
    state.analysisError = "无法写入本地数据库，解析未开始；原有记录和草稿均未改变。请检查浏览器存储空间。";
    render();
    toast(state.analysisError, "error");
    return;
  }
  state.startingAnalysis = false;
  state.analysis = {
    questionId: question.id,
    controller,
    startedAt: now,
    stage: "waiting",
    contentLength: 0,
    timer: null
  };
  state.questions = [question, ...state.questions.filter((item) => item.id !== question.id && item.id !== editingId)];
  state.draft = newDraft();
  state.editId = null;
  navigate(`/result/${encodeURIComponent(question.id)}`);
  startAnalysisTicker(question);
  try {
    const result = await analyzeQuestionStream(state.settings, draft, {
      signal: controller.signal,
      onStage: (stage) => setAnalysisStage(stage),
      onPreview: (preview) => {
        if (!preview || preview.validated !== true) return;
        if (!state.analysis || state.analysis.controller !== controller) return;
        question.preview = preview;
        question.updatedAt = Date.now();
        setAnalysisStage(preview.answer?.length || preview.answerText ? "preview" : "receiving");
        if (state.route.name === "result" && state.route.id === question.id) render();
      },
      onDelta: (fullText) => {
        if (!state.analysis || state.analysis.controller !== controller) return;
        const length = String(fullText || "").length;
        state.analysis.contentLength = length;
        question.receivedChars = length;
        updateAnalysisLive(question);
      },
      onReasoningDelta: (reasoningSoFar) => {
        if (state.analysis && state.analysis.controller === controller && reasoningSoFar.length > state.analysis.contentLength) {
          state.analysis.contentLength = reasoningSoFar.length;
          updateAnalysisLive(question);
        }
      }
    });
    if (!state.analysis || state.analysis.controller !== controller) return;
    const finishedAt = Date.now();
    const completed = {
      ...question,
      status: result.status === "answered" || !result.status ? "completed" : result.status,
      stage: "done",
      preview: null,
      result,
      receivedChars: 0,
      durationMs: finishedAt - Number(question.analysisStartedAt || finishedAt),
      updatedAt: finishedAt,
      errorMessage: ""
    };
    await saveQuestion(completed);
    if (editingId) {
      try {
        await deleteQuestionRecord(editingId);
      } catch (cleanupError) {
        console.warn("旧解析记录清理失败", cleanupError);
      }
    }
    await refreshQuestions();
    stopAnalysisRuntime();
    render();
    if (result.status === "out_of_scope") toast("这道题不在医学课程范围内，未生成解析。");
    else toast(state.settings.saveHistory || completed.favorite ? "解析完成，已保存到历史记录。" : "解析完成。当前未开启历史保存，本题为临时结果。");
  } catch (caught) {
    if (!state.analysis || state.analysis.controller !== controller) return;
    const cancelled = caught?.code === "cancelled" || caught?.name === "AbortError";
    const message = caught instanceof ApiError ? caught.message : (caught?.message || "解析失败，请稍后重试。");
    if (editingQuestion) {
      let cleanupError = "";
      try {
        await deleteQuestionRecord(question.id);
      } catch (deleteError) {
        cleanupError = "重新解析失败，且临时记录未能从本地数据库清理；原记录仍然保留。";
        console.warn(deleteError);
      }
      try {
        if (cleanupError) {
          state.questions = previousQuestions;
        } else {
          await refreshQuestions();
        }
      } catch (refreshError) {
        state.questions = previousQuestions;
        console.warn(refreshError);
      }
      stopAnalysisRuntime();
      navigate(`/result/${encodeURIComponent(editingQuestion.id)}`);
      render();
      if (cleanupError) toast(cleanupError, "error");
      else if (cancelled) toast("已停止解析，原记录保持不变。");
      else toast(message || "重新解析失败，原记录已保留。", "error");
      return;
    }
    if (cancelled) {
      const cancelledQuestion = {
        ...question,
        status: "cancelled",
        stage: "done",
        updatedAt: Date.now(),
        durationMs: Date.now() - Number(question.analysisStartedAt || Date.now()),
        errorMessage: ""
      };
      try {
        await saveQuestion(cancelledQuestion);
        await refreshQuestions();
      } catch {}
      stopAnalysisRuntime();
      render();
      toast("已停止解析，已生成的内容会保留。");
      return;
    }
    let cleanupError = "";
    try {
      await deleteQuestionRecord(question.id);
      await refreshQuestions();
    } catch (deleteError) {
      cleanupError = "本次失败记录未能从本地数据库清理，请刷新后检查历史记录。";
      console.warn(deleteError);
    }
    state.questions = previousQuestions;
    state.draft = previousDraft;
    state.editId = previousEditId;
    stopAnalysisRuntime();
    state.analysisError = cleanupError || message || "解析失败，请稍后重试。";
    navigate("/compose");
    render();
    toast(state.analysisError, "error");
  }
}

function updateFollowLiveStage(stage) {
  const node = document.querySelector("[data-followup-stage]");
  if (node) node.textContent = stageLabel(stage);
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
  state.followUpAbortReason = "";
  state.followUpStage = "waiting";
  const prior = (question.followUps || []).map((message) => ({ role: message.role, text: message.text || "" }));
  const userMessage = { id: uid("follow"), role: "user", text, images, createdAt: Date.now() };
  const assistantMessage = { id: uid("follow"), role: "assistant", text: "", usage: null, streaming: true, createdAt: Date.now() + 1 };
  question.followUps = [...(question.followUps || []), userMessage, assistantMessage];
  const draftOnFailure = text;
  const imagesOnFailure = images;
  const originalImages = state.followUpIncludeImages && modelSupportsImages(currentModel(), state.settings)
    ? (question.images || []).map((image) => ({ ...image, context: true }))
    : [];
  const requestImages = [...images, ...originalImages];
  const controller = new AbortController();
  state.followUpController = controller;
  state.followUpDraft = "";
  state.followUpImages = [];
  render();
  try {
    const answer = await askFollowUpStream(state.settings, question, text, requestImages, prior, {
      signal: controller.signal,
      onStage: (stage) => {
        state.followUpStage = stage;
        updateFollowLiveStage(stage);
      },
      onDelta: (fullText) => {
        assistantMessage.text = fullText;
        const live = document.querySelector("[data-followup-live]");
        if (live) live.innerHTML = nl2br(fullText);
        const stageNode = document.querySelector("[data-followup-stage]");
        if (stageNode && ["waiting", "thinking"].includes(state.followUpStage)) {
          state.followUpStage = "receiving";
          stageNode.textContent = stageLabel("receiving");
        }
      }
    });
    assistantMessage.text = answer.text;
    assistantMessage.usage = answer.usage || null;
    assistantMessage.demo = answer.demo === true;
    assistantMessage.streaming = false;
    assistantMessage.status = "completed";
    assistantMessage.failed = false;
    assistantMessage.cancelled = false;
    question.updatedAt = Date.now();
    await saveQuestion(question);
    await refreshQuestions();
    state.followUpBusy = false;
    state.followUpController = null;
    state.followUpAbortReason = "";
    state.followUpStage = "";
    render();
  } catch (error) {
    console.error(error);
    const aborted = error?.code === "cancelled" || error?.name === "AbortError";
    const interrupted = aborted && state.followUpAbortReason === "interrupted";
    const cancelled = aborted && !interrupted;
    const message = error instanceof ApiError ? error.message : (error?.message || "追问失败，请稍后重试。");
    state.followUpBusy = false;
    state.followUpController = null;
    state.followUpStage = "";
    if (assistantMessage.text.trim()) {
      assistantMessage.streaming = false;
      assistantMessage.cancelled = cancelled;
      assistantMessage.failed = !aborted;
      assistantMessage.interrupted = interrupted;
      assistantMessage.status = interrupted ? "interrupted" : cancelled ? "cancelled" : "failed";
      question.updatedAt = Date.now();
      try {
        await saveQuestion(question);
        await refreshQuestions();
      } catch {}
      state.followUpDraft = aborted ? "" : draftOnFailure;
      state.followUpImages = aborted ? [] : imagesOnFailure;
      state.followUpError = aborted ? "" : message;
      render();
      toast(interrupted ? "页面切换，追问已中断并保留当前内容。" : (cancelled ? "已停止生成，保留当前内容。" : "追问中断，已保留生成到的内容。"), aborted ? "info" : "error");
      return;
    }
    state.followUpError = aborted ? "" : message;
    state.followUpDraft = draftOnFailure;
    state.followUpImages = imagesOnFailure;
    question.followUps = (question.followUps || []).filter((item) => item !== userMessage && item !== assistantMessage);
    render();
    if (!aborted) toast(state.followUpError || "追问已停止。", "error");
    else toast(interrupted ? "页面切换，已中断本次追问。" : "已停止生成。");
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
  if (questionStatus(question) === "analyzing" || state.analysis?.questionId === id) {
    toast("这道题正在解析，请先停止或等待完成后再编辑。", "info");
    return;
  }
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
    if (state.analysis?.questionId === id) {
      state.analysis.cancelRequested = true;
      state.analysis.controller?.abort();
      stopAnalysisRuntime(id);
    }
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
  const answers = Array.isArray(result.answer) ? result.answer.filter(Boolean) : (result.answer ? [result.answer] : []);
  const hasAnswer = Boolean(answers.length || result.answerText);
  const shareableStatus = result.status === "answered" || (!result.status && question.status === "completed");
  if (questionStatus(question) !== "completed" || result.status === "needs_confirmation" || !shareableStatus || !hasAnswer) {
    throw new Error("只有已完成且有答案的解析可以分享。");
  }
  const answer = answers.length ? answers.join("、") : result.answerText;
  const points = (result.keyPoints || []).map((item) => `· ${item}`).join("\n");
  return `${question.questionText || result.recognizedQuestion || "医学题目"}\n\n答案：${answer}${points ? `\n\n知识点：\n${points}` : ""}\n\n（由医学拍题助手生成，仅供学习参考）`;
}

async function shareQuestion(id) {
  const question = state.questions.find((item) => item.id === id);
  if (!question) return;
  let text;
  try {
    text = answerForShare(question);
  } catch (error) {
    toast(error.message, "error");
    return;
  }
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
  openImagePreview(state.draft.images, index);
}

function openImageEditor(index) {
  const image = state.draft.images[index];
  if (!image) return;
  openModal(`
    <div class="image-editor-head"><div><h2>编辑题目图片</h2><p>第 ${index + 1} 张 · ${escapeHtml(image.name || "图片")} · ${formatBytes(image.sizeBytes)}</p></div><button class="btn btn-icon btn-ghost" data-action="close-modal" title="关闭">${icon("x")}</button></div>
    <div class="image-editor-preview"><img src="${escapeAttr(image.dataUrl)}" alt="题目图片 ${index + 1}"></div>
    <div class="image-editor-actions">
      <button class="btn btn-secondary" data-action="rotate-image" data-index="${index}">${icon("rotateRight")}旋转 90°</button>
      <button class="btn btn-secondary" data-action="crop-image" data-index="${index}">${icon("crop")}裁剪</button>
      <button class="btn btn-secondary" data-action="move-image" data-index="${index}" data-dir="-1" ${index === 0 ? "disabled" : ""}>${icon("arrowLeft")}前移</button>
      <button class="btn btn-secondary" data-action="move-image" data-index="${index}" data-dir="1" ${index === state.draft.images.length - 1 ? "disabled" : ""}>${icon("arrowRight")}后移</button>
      <button class="btn btn-danger" data-action="remove-image" data-index="${index}">${icon("trash")}删除这张</button>
    </div>
    <p class="field-note">裁剪会打开框选面板；旋转与排序会立即更新缩略图。最多 ${MAX_IMAGES} 张，总量不超过 18 MB。</p>`,
  "image-editor-modal");
}

function openCrop(index) {
  const image = state.draft.images[index];
  if (!image) return;
  openModal(`
    <h2>裁剪图片</h2>
    <p>在图片上拖动，框选需要保留的区域。默认保留当前完整图，并在其后新增一张裁剪图。</p>
    <div class="crop-stage" id="crop-stage"><img id="crop-image" src="${escapeAttr(image.dataUrl)}" alt="裁剪预览"><div class="crop-box" id="crop-box"></div></div>
    <div class="crop-tools"><span class="crop-tip">提示：框选区域会自动吸附在图片范围内</span><button class="btn btn-secondary" data-action="close-modal">取消</button><button class="btn btn-ghost" data-action="apply-crop" data-index="${index}" data-mode="replace">替换当前图</button><button class="btn btn-primary" data-action="apply-crop" data-index="${index}" data-mode="keep">${icon("crop")}保留原图并添加</button></div>`,
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

async function applyCrop(index, mode = "keep") {
  const image = state.draft.images[index];
  const crop = state.crop;
  if (!image || !crop?.imageRect) return;
  const keepOriginal = mode !== "replace";
  if (keepOriginal && state.draft.images.length >= MAX_IMAGES) {
    toast(`最多上传 ${MAX_IMAGES} 张图片，请先删除一张再裁剪。`, "error");
    return;
  }
  const rect = crop.imageRect;
  const box = crop.box;
  if (box.width < 8 || box.height < 8) {
    toast("框选区域太小，请重新拖动。", "error");
    return;
  }
  const scaleX = image.width / rect.width;
  const scaleY = image.height / rect.height;
  showBusy("正在裁剪", keepOriginal ? "保留完整图并生成局部图。" : "替换当前图片。");
  try {
    const result = await cropDataUrl(image.dataUrl, {
      x: (box.x - rect.x) * scaleX,
      y: (box.y - rect.y) * scaleY,
      width: box.width * scaleX,
      height: box.height * scaleY
    });
    if (keepOriginal) {
      if (state.draft.images.length >= MAX_IMAGES) {
        hideBusy();
        toast(`最多上传 ${MAX_IMAGES} 张图片，请先删除一张再裁剪。`, "error");
        return;
      }
      const current = state.draft.images[index];
      if (!current) {
        hideBusy();
        return;
      }
      const croppedImage = {
        id: uid("image"),
        dataUrl: result.dataUrl,
        width: result.width,
        height: result.height,
        sizeBytes: result.sizeBytes,
        name: `${current.name || "图片"}（裁剪图）`
      };
      state.draft.images.splice(index + 1, 0, croppedImage);
    } else {
      const target = state.draft.images[index];
      target.dataUrl = result.dataUrl;
      target.width = result.width;
      target.height = result.height;
      target.sizeBytes = result.sizeBytes;
      target.name = `${target.name || "图片"}（已裁剪）`;
    }
    closeModal();
    hideBusy();
    render();
    toast(keepOriginal ? "已保留原图并添加裁剪图。" : "已替换当前图片。");
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
  const previousBase = String(state.settings.baseUrl || "").replace(/\/+$/, "");
  const previousModel = String(state.settings.model || "");
  const settings = {
    ...state.settings,
    apiKey: document.querySelector("#settings-key")?.value.trim() ?? state.settings.apiKey,
    baseUrl: (document.querySelector("#settings-base-url")?.value.trim() || "https://api.deepseek.com").replace(/\/+$/, ""),
    model: document.querySelector("#settings-model")?.value.trim() || "deepseek-flash",
    answerMode: currentMode()
  };
  delete settings.migrationNotice;
  const previousSettings = state.settings;
  try {
    saveSettings(settings);
  } catch (error) {
    state.settings = previousSettings;
    throw error;
  }
  if (settings.baseUrl !== previousBase || settings.model !== previousModel) {
    state.connection = null;
    state.verify = null;
  }
  state.settings = settings;
  return settings;
}

function saveInterfaceSettings() {
  try {
    const settings = saveFormSettings();
    state.saveStatus = "saved";
    state.connection = null;
    state.verify = null;
    return settings;
  } catch (error) {
    state.saveStatus = "error";
    toast(error?.message || "设置保存失败，请检查浏览器存储。", "error");
    return null;
  }
}

async function testSavedConnection() {
  if (state.connection === "testing") return;
  try {
    const settings = saveFormSettings();
    state.connection = "testing";
    render();
    const result = await testConnection(settings);
    state.connection = { type: "ok", text: result.message || "连接成功。" };
    toast("接口连接正常。");
  } catch (error) {
    state.connection = { type: "error", text: error instanceof ApiError ? error.message : (error?.message || "连接失败。") };
    toast(state.connection.text, "error");
  }
  render();
}

async function verifySavedModel() {
  if (state.verify === "testing") return;
  try {
    const settings = saveFormSettings();
    state.verify = "testing";
    render();
    const result = await verifyModel(settings);
    state.verify = { type: "ok", text: result.message || "模型验证通过。" };
    toast("模型可用。");
  } catch (error) {
    state.verify = { type: "error", text: error instanceof ApiError ? error.message : (error?.message || "模型验证失败。") };
    toast(state.verify.text, "error");
  }
  render();
}

function cancelAnalysis() {
  if (!state.analysis?.controller) return;
  state.analysis.cancelRequested = true;
  state.analysis.controller.abort();
  updateAnalysisLive();
  toast("正在停止解析，已生成的内容会保留。", "info");
}

function openImagePreview(images, index) {
  const image = images?.[index];
  if (!image) return;
  openModal(`<button class="btn btn-icon btn-secondary preview-close" data-action="close-modal">${icon("x")}</button><img src="${escapeAttr(image.dataUrl)}" alt="题目图片预览">`, "preview-modal");
}

function openResultImage(id, index) {
  const question = state.questions.find((item) => item.id === id) || (state.route.id === id ? currentQuestion() : null);
  if (question) openImagePreview(question.images || [], index);
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
    case "edit-image":
      openImageEditor(Number(element.dataset.index));
      break;
    case "remove-image":
      closeModal();
      state.draft.images.splice(Number(element.dataset.index), 1);
      render();
      break;
    case "move-image": {
      const index = Number(element.dataset.index);
      const dir = Number(element.dataset.dir);
      closeModal();
      moveImage(index, dir);
      openImageEditor(index + dir);
      break;
    }
    case "rotate-image": {
      const index = Number(element.dataset.index);
      closeModal();
      rotateImage(index).then(() => openImageEditor(index));
      break;
    }
    case "crop-image":
      openCrop(Number(element.dataset.index));
      break;
    case "apply-crop":
      applyCrop(Number(element.dataset.index), element.dataset.mode || "keep");
      break;
    case "start-analysis":
      startAnalysis();
      break;
    case "cancel-analysis":
      cancelAnalysis();
      break;
    case "retry-analysis":
      editQuestion(element.dataset.id);
      break;
    case "preview-result-image":
      openResultImage(element.dataset.id, Number(element.dataset.index));
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
      const question = state.questions.find((item) => item.id === element.dataset.id) || currentQuestion();
      if (question && element.dataset.id === question.id) copyText(answerForShare(question)).then(() => toast("答案已复制。"));
      break;
    }
    case "share-answer":
      shareQuestion(element.dataset.id);
      break;
    case "send-followup":
      sendFollowUp();
      break;
    case "stop-followup":
      if (state.followUpController) {
        state.followUpAbortReason = "cancelled";
        state.followUpController.abort();
        toast("正在停止追问生成…", "info");
      }
      break;
    case "set-answer-mode": {
      const mode = normalizeAnswerModeValue(element.dataset.mode);
      state.settings = { ...state.settings, answerMode: mode };
      saveSettings(state.settings);
      render();
      toast(`已切换到「${currentModeMeta().label}」模式。`);
      break;
    }
    case "followup-image":
      document.querySelector("#followup-input")?.click();
      break;
    case "remove-followup-image":
      state.followUpImages.splice(Number(element.dataset.index), 1);
      render();
      break;
    case "history-filter":
      state.historyFilter = element.dataset.filter;
      state.historyShowAll = false;
      render();
      break;
    case "show-all-history":
      state.historyShowAll = true;
      render();
      break;
    case "clear-history":
      clearAllData();
      break;
    case "quick-followup":
      if (state.followUpBusy) break;
      state.followUpDraft = element.dataset.text || "";
      sendFollowUp();
      break;
    case "save-api": {
      if (saveInterfaceSettings()) toast("接口设置已保存，可继续测试连接。");
      render();
      break;
    }
    case "test-connection":
      testSavedConnection();
      break;
    case "verify-model":
      verifySavedModel();
      break;
    case "toggle-key":
      state.showKey = !state.showKey;
      render();
      break;
    case "save-model": {
      if (saveInterfaceSettings()) toast("模型设置已保存，可继续验证模型。");
      render();
      break;
    }
    case "set-model": {
      const input = document.querySelector("#settings-model");
      if (input) input.value = element.dataset.model;
      if (saveInterfaceSettings()) toast("已切换模型，请验证可用性。");
      render();
      break;
    }
    case "set-theme": {
      const theme = ["auto", "light", "dark"].includes(element.value) ? element.value : "auto";
      state.settings = { ...state.settings, theme };
      saveSettings(state.settings);
      applyTheme();
      render();
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
  if (target.id === "follow-include-images") {
    state.followUpIncludeImages = Boolean(target.checked);
    return;
  }
  const settingKey = target.dataset?.setting;
  if (settingKey === "theme") {
    state.settings = { ...state.settings, theme: ["auto", "light", "dark"].includes(target.value) ? target.value : "auto" };
    saveSettings(state.settings);
    applyTheme();
    toast("外观主题已保存。");
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
  if (state.followUpController) {
    state.followUpAbortReason = "interrupted";
    state.followUpController.abort();
    state.followUpController = null;
  }
  state.followUpDraft = "";
  state.followUpImages = [];
  state.followUpIncludeImages = false;
  state.followUpError = "";
  state.followUpBusy = false;
  state.followUpStage = "";
  state.analysisError = "";
  state.connection = null;
  state.verify = null;
  state.resultPrimaryOpen = false;
  state.resultSecondaryOpen = true;
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

function bindViewportHeight() {
  const root = document.documentElement;
  const update = () => {
    const viewport = globalThis.visualViewport;
    const height = viewport?.height || window.innerHeight;
    const inset = viewport ? Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop) : 0;
    root.style.setProperty("--app-vvh", `${Math.round(height)}px`);
    root.style.setProperty("--keyboard-inset", `${Math.round(inset)}px`);
  };
  update();
  globalThis.visualViewport?.addEventListener("resize", update);
  globalThis.visualViewport?.addEventListener("scroll", update);
  window.addEventListener("resize", update);
}

async function init() {
  try {
    state.settings = loadSettings();
    applyTheme();
    bindViewportHeight();
    const themeMedia = globalThis.matchMedia?.("(prefers-color-scheme: dark)");
    themeMedia?.addEventListener?.("change", () => {
      if (state.settings.theme === "auto") applyTheme();
    });
    await refreshQuestions();
    const orphanTemps = state.questions.filter((question) => question.status === "analyzing" && question.previousQuestionId);
    if (orphanTemps.length) {
      await Promise.all(orphanTemps.map((question) => deleteQuestionRecord(question.id).catch(() => {})));
      await refreshQuestions();
    }
    const interrupted = state.questions.filter((question) => question.status === "analyzing" && !question.isEphemeral);
    if (interrupted.length) {
      await Promise.all(interrupted.map((question) => saveQuestion({
        ...question,
        status: "interrupted",
        errorMessage: question.errorMessage || "页面在解析过程中被关闭或刷新，可在题干页重新解析。",
        updatedAt: Date.now()
      })));
      await refreshQuestions();
    }
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
