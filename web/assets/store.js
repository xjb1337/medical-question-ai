const DB_NAME = "medical-question-ai-web";
const DB_VERSION = 1;
const STORE_NAME = "questions";
const SETTINGS_KEY = "medical-question-ai.settings.v1";
const SESSION_KEY = "medical-question-ai.session-key";

export const SETTINGS_VERSION = 2;
export const SETTINGS_STORAGE_KEY = SETTINGS_KEY;
export const ANSWER_MODE_IDS = ["fast", "standard", "detailed"];

export const DEFAULT_SETTINGS = {
  settingsVersion: SETTINGS_VERSION,
  apiKey: "",
  model: "deepseek-flash",
  baseUrl: "https://api.deepseek.com",
  answerMode: "standard",
  saveHistory: true,
  demoMode: false,
  rememberKey: true,
  customSupportsImages: false,
  theme: "auto"
};

const OFFICIAL_HOST = "api.deepseek.com";
const LEGACY_MODEL_MIGRATIONS = {
  "deepseek-chat": { model: "deepseek-flash", answerMode: "fast" },
  "deepseek-reasoner": { model: "deepseek-flash", answerMode: "detailed" },
  "deepseek-v4-flash": { model: "deepseek-flash", answerMode: null },
  "deepseek-v4-flash-vision-exp": { model: "deepseek-flash", answerMode: null }
};

let dbPromise = null;

function normalizeAnswerMode(value, fallback = "standard") {
  const mode = String(value || "").trim().toLowerCase();
  return ANSWER_MODE_IDS.includes(mode) ? mode : fallback;
}

/**
 * 仅当地址为 DeepSeek 官方 HTTPS 主机，且路径为根路径或 /v1 时返回 true。
 * 使用 URL 解析主机名，避免 api.deepseek.com.evil.example 之类的 contains 误判。
 */
export function isOfficialDeepSeekBaseUrl(baseUrl) {
  const value = String(baseUrl || DEFAULT_SETTINGS.baseUrl).trim();
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    return false;
  }
  if (parsed.protocol !== "https:") return false;
  if (parsed.hostname.toLowerCase() !== OFFICIAL_HOST) return false;
  const path = parsed.pathname.replace(/\/+$/, "") || "/";
  return path === "/" || path === "/v1";
}

/**
 * 纯函数：把任意已持久化设置（可能来自 v1）迁移到当前 v2 结构。
 * 不读写 localStorage，便于 node:test 直接覆盖。
 */
export function migrateSettings(stored = {}) {
  const input = stored && typeof stored === "object" && !Array.isArray(stored) ? stored : {};
  const originalModel = typeof input.model === "string" ? input.model.trim() : "";
  const baseUrl = typeof input.baseUrl === "string" && input.baseUrl.trim()
    ? input.baseUrl.trim().replace(/\/+$/, "")
    : DEFAULT_SETTINGS.baseUrl;
  const official = isOfficialDeepSeekBaseUrl(baseUrl);
  const storedVersion = Number(input.settingsVersion ?? input.settings_version ?? 0) || 0;
  const hasStoredSettings = Object.keys(input).length > 0;
  const hadAnswerMode = ANSWER_MODE_IDS.includes(String(input.answerMode || "").trim().toLowerCase());

  let model = originalModel || DEFAULT_SETTINGS.model;
  let answerMode = normalizeAnswerMode(input.answerMode);
  let migrationKind = "";
  let note = "";

  if (official && LEGACY_MODEL_MIGRATIONS[model]) {
    const legacy = LEGACY_MODEL_MIGRATIONS[model];
    const previousModel = model;
    model = legacy.model;
    if (legacy.answerMode) answerMode = legacy.answerMode;
    migrationKind = previousModel === "deepseek-reasoner" ? "reasoner" : previousModel === "deepseek-chat" ? "chat" : "alias";
    if (migrationKind === "chat") {
      note = "已按官方最新型号，把 deepseek-chat 迁移为 deepseek-flash，并使用快速模式；可在设置中切换标准或详细。";
    } else if (migrationKind === "reasoner") {
      note = "已按官方最新型号，把 deepseek-reasoner 迁移为 deepseek-flash，并使用详细模式以保留原有推理偏好。";
    } else {
      note = `已规范旧模型别名 ${previousModel} 为 deepseek-flash，原有做题模式保持不变。`;
    }
  } else if (hasStoredSettings && storedVersion < SETTINGS_VERSION && !input.answerMode) {
    note = "设置已升级到 v2：默认做题模式为「标准」，可在设置或首页切换快速 / 标准 / 详细。";
  }

  const settings = {
    ...DEFAULT_SETTINGS,
    ...input,
    model,
    baseUrl,
    answerMode,
    settingsVersion: SETTINGS_VERSION
  };

  const changed = Boolean(
    storedVersion !== SETTINGS_VERSION ||
    model !== originalModel ||
    !hadAnswerMode ||
    input.settingsVersion === undefined
  );

  return {
    settings,
    changed,
    note,
    fromVersion: storedVersion,
    toVersion: SETTINGS_VERSION,
    previousModel: originalModel,
    migratedModel: model,
    migrationKind
  };
}

/**
 * 返回模型能力。官方预设是唯一可信来源；未知自定义模型默认全部降级，
 * 只有用户在高级设置里显式开启“图片输入”时，才允许把图片发给它。
 */
export function getModelCapability(model, settings = {}) {
  const id = String(model || "").trim().toLowerCase();
  const officialBase = !settings?.baseUrl || isOfficialDeepSeekBaseUrl(settings.baseUrl);
  if (officialBase && id === "deepseek-flash") {
    return { supportsImages: true, supportsThinking: true, supportsJson: true, verified: true, known: true };
  }
  if (officialBase && id === "deepseek-v4-pro") {
    return { supportsImages: false, supportsThinking: true, supportsJson: true, verified: true, known: true };
  }
  return {
    supportsImages: settings?.customSupportsImages === true,
    supportsThinking: false,
    supportsJson: false,
    verified: false,
    known: false
  };
}

export function modelSupportsImages(model, settings = {}) {
  return getModelCapability(model, settings).supportsImages;
}

function openDatabase() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: "id" });
        store.createIndex("createdAt", "createdAt");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("本地数据库打开失败"));
  });
  return dbPromise;
}

async function transaction(mode, operation) {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, mode);
    const store = tx.objectStore(STORE_NAME);
    let result;
    try {
      result = operation(store);
    } catch (error) {
      reject(error);
      return;
    }
    tx.oncomplete = () => resolve(result);
    tx.onerror = () => reject(tx.error || new Error("本地数据库操作失败"));
    tx.onabort = () => reject(tx.error || new Error("本地数据库操作取消"));
  });
}

export async function listQuestions() {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, "readonly");
    const request = tx.objectStore(STORE_NAME).getAll();
    request.onsuccess = () => {
      const items = Array.isArray(request.result) ? request.result : [];
      resolve(items.sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || ""))));
    };
    request.onerror = () => reject(request.error || new Error("读取历史失败"));
  });
}

export async function getQuestion(id) {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, "readonly");
    const request = tx.objectStore(STORE_NAME).get(id);
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(request.error || new Error("读取题目失败"));
  });
}

export async function saveQuestion(question) {
  await transaction("readwrite", (store) => store.put(question));
  return question;
}

export async function deleteQuestion(id) {
  await transaction("readwrite", (store) => store.delete(id));
}

export async function clearQuestions() {
  await transaction("readwrite", (store) => store.clear());
}

export function loadSettings() {
  let stored = {};
  try {
    stored = JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}") || {};
  } catch {
    stored = {};
  }
  const migrated = migrateSettings(stored);
  const settings = { ...migrated.settings };
  if (!settings.rememberKey) {
    settings.apiKey = sessionStorage.getItem(SESSION_KEY) || "";
  }
  if (migrated.changed) {
    saveSettings(settings);
  }
  if (migrated.changed && migrated.note) {
    settings.migrationNotice = migrated.note;
  }
  return settings;
}

export function saveSettings(settings) {
  const { migrationNotice, ...rest } = settings || {};
  const copy = { ...DEFAULT_SETTINGS, ...rest, settingsVersion: SETTINGS_VERSION };
  copy.answerMode = normalizeAnswerMode(copy.answerMode);
  if (copy.rememberKey) {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(copy));
    sessionStorage.removeItem(SESSION_KEY);
  } else {
    sessionStorage.setItem(SESSION_KEY, copy.apiKey || "");
    const persisted = { ...copy, apiKey: "" };
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(persisted));
  }
  return copy;
}

export function clearStoredSettings() {
  localStorage.removeItem(SETTINGS_KEY);
  sessionStorage.removeItem(SESSION_KEY);
}

export function maskKey(key) {
  if (!key) return "未配置";
  if (key.length <= 8) return "••••••••";
  return `${key.slice(0, 3)}••••••••${key.slice(-4)}`;
}

export async function storageStats() {
  let usage = 0;
  let quota = 0;
  if (navigator.storage && navigator.storage.estimate) {
    try {
      const estimate = await navigator.storage.estimate();
      usage = estimate.usage || 0;
      quota = estimate.quota || 0;
    } catch {
      // 浏览器不支持时按 0 处理
    }
  }
  return { usage, quota };
}
