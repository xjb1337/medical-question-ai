const MAX_DIMENSION = 1800;
const JPEG_QUALITY = 0.86;

function makeId() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  return `img-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function dataUrlBytes(dataUrl) {
  const base64 = String(dataUrl).split(",")[1] || "";
  return Math.round(base64.length * 0.75);
}

async function decodeFile(file) {
  if ("createImageBitmap" in window) {
    try {
      return await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
      // 回退到 Image 解码，兼容不支持 imageOrientation 的浏览器
    }
  }
  const objectUrl = URL.createObjectURL(file);
  try {
    return await loadImage(objectUrl);
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

export function loadImage(src) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("图片读取失败，请换一张图片重试。"));
    image.src = src;
  });
}

function canvasToDataUrl(canvas) {
  return canvas.toDataURL("image/jpeg", JPEG_QUALITY);
}

export async function compressFile(file) {
  if (!file || !file.type.startsWith("image/")) throw new Error("仅支持图片文件。");
  const bitmap = await decodeFile(file);
  const width = bitmap.width || bitmap.naturalWidth;
  const height = bitmap.height || bitmap.naturalHeight;
  if (!width || !height) throw new Error("图片尺寸读取失败。");
  const scale = Math.min(1, MAX_DIMENSION / Math.max(width, height));
  const targetWidth = Math.max(1, Math.round(width * scale));
  const targetHeight = Math.max(1, Math.round(height * scale));
  const canvas = document.createElement("canvas");
  canvas.width = targetWidth;
  canvas.height = targetHeight;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, targetWidth, targetHeight);
  ctx.drawImage(bitmap, 0, 0, targetWidth, targetHeight);
  const dataUrl = canvasToDataUrl(canvas);
  const warnings = [];
  if (Math.max(width, height) < 900) warnings.push("分辨率较低");
  if (Math.max(width, height) > 3200) warnings.push("已压缩大图");
  if (bitmap.close) bitmap.close();
  return {
    id: makeId(),
    name: file.name || "粘贴图片",
    dataUrl,
    width: targetWidth,
    height: targetHeight,
    originalWidth: width,
    originalHeight: height,
    sizeBytes: dataUrlBytes(dataUrl),
    warnings
  };
}

export async function rotateDataUrl(dataUrl, degrees = 90) {
  const image = await loadImage(dataUrl);
  const angle = ((degrees % 360) + 360) % 360;
  const swap = angle === 90 || angle === 270;
  const canvas = document.createElement("canvas");
  canvas.width = swap ? image.naturalHeight : image.naturalWidth;
  canvas.height = swap ? image.naturalWidth : image.naturalHeight;
  const ctx = canvas.getContext("2d");
  ctx.translate(canvas.width / 2, canvas.height / 2);
  ctx.rotate((angle * Math.PI) / 180);
  ctx.drawImage(image, -image.naturalWidth / 2, -image.naturalHeight / 2);
  return {
    dataUrl: canvasToDataUrl(canvas),
    width: canvas.width,
    height: canvas.height,
    sizeBytes: dataUrlBytes(canvas.toDataURL("image/jpeg", JPEG_QUALITY))
  };
}

export async function cropDataUrl(dataUrl, crop) {
  const image = await loadImage(dataUrl);
  const x = Math.max(0, Math.min(image.naturalWidth - 1, Math.round(crop.x)));
  const y = Math.max(0, Math.min(image.naturalHeight - 1, Math.round(crop.y)));
  const width = Math.max(1, Math.min(image.naturalWidth - x, Math.round(crop.width)));
  const height = Math.max(1, Math.min(image.naturalHeight - y, Math.round(crop.height)));
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  canvas.getContext("2d").drawImage(image, x, y, width, height, 0, 0, width, height);
  const output = canvasToDataUrl(canvas);
  return { dataUrl: output, width, height, sizeBytes: dataUrlBytes(output) };
}

export function totalImageBytes(images) {
  return (images || []).reduce((sum, image) => sum + (image.sizeBytes || 0), 0);
}

export function formatBytes(bytes) {
  if (!bytes) return "0 KB";
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export async function imageFromClipboardItem(item) {
  const file = item.getAsFile();
  if (!file) return null;
  return compressFile(file);
}
