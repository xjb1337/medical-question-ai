const DB_NAME = "medical-question-ai-web";
const DB_VERSION = 1;
const STORE_NAME = "questions";
const SETTINGS_KEY = "medical-question-ai.settings.v1";
const SESSION_KEY = "medical-question-ai.session-key";

const DEFAULT_SETTINGS = {
  apiKey: "",
  model: "deepseek-flash",
  baseUrl: "https://api.deepseek.com",
  saveHistory: true,
  demoMode: false,
  rememberKey: true
};

let dbPromise = null;

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
  const settings = { ...DEFAULT_SETTINGS, ...stored };
  if (!settings.rememberKey) {
    settings.apiKey = sessionStorage.getItem(SESSION_KEY) || "";
  }
  return settings;
}

export function saveSettings(settings) {
  const copy = { ...DEFAULT_SETTINGS, ...settings };
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
