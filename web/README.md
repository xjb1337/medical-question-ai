# 医学拍题助手 · 网页版

一个零构建、零依赖的浏览器版本，用于快速试玩和验收医学题目解析流程。界面与 Android 版保持相同的产品逻辑：文字/图片题目、题型选择、结构化解析、选项分析、追问、历史搜索与收藏。

## 启动

需要 Node.js 18+（使用内置 `fetch`）。无需安装依赖。

```bash
cd web
npm start
```

打开：

```text
http://127.0.0.1:4173
```

也可以直接运行：

```bash
node server.mjs
```

如需换端口：

```bash
PORT=8080 node server.mjs
```

Windows PowerShell：

```powershell
$env:PORT="8080"; node server.mjs
```

## 两种测试模式

### 演示模式（无需 API Key）

1. 打开「设置」。
2. 打开「演示模式」。
3. 回到「新建解析」，点击「载入示例题」或输入任意题目。
4. 勾选学习材料确认，点击「开始解析」。

演示模式完全在本地生成模拟结果，不发起网络请求，适合检查交互、布局和保存流程。页面会明确标注「演示模式」。

### 真实 DeepSeek 解析

1. 在「设置」填写 API Key。默认 Base URL 为 `https://api.deepseek.com`。
2. 模型默认 `deepseek-flash`（支持文字和图片）；`deepseek-v4-pro` 仅支持文字，带图会被前端拦截。自定义 Base URL 可填写代理端模型名。
3. 选择快速 / 标准 / 详细模式；旧 `deepseek-chat` 设置自动迁移为快速，旧 `deepseek-reasoner` 迁移为详细，自定义 Base URL 下保留原模型名。
4. 点击「测试连接」确认 Key 可用。
5. 关闭演示模式，回到新建解析。

API Key 默认以明文存在浏览器 `localStorage`（开启「记住 API Key」时）；关闭后改存当前标签页的 `sessionStorage`，刷新通常仍在，关闭标签页后清除。浏览器存储不是加密存储。解析请求经本地 Node 代理转发，代理进程能够接触 API Key 和请求内容；服务默认只监听 `127.0.0.1`，不会把 Key 写入磁盘或日志。默认接口地址 `https://api.deepseek.com` 使用 HTTPS；如自定义 `http://` 地址，该段传输不具备加密保护，仅建议本机测试。

## 回归测试

```bash
npm test
```

`node --test test/unit.test.mjs` 共 19 个单元测试，覆盖设置迁移、模型能力、预览字段完整性、`finish_reason`、解析边界与追问历史；`node test/browser-repair-smoke.mjs` 会自行启动本地 mock DeepSeek、Node 静态服务和系统 Chrome/Edge 无头浏览器，验证合成 PNG 导入、裁剪保留原图、解析 Token 展示、追问 Token 用量、失败恢复和历史落库。需要本机安装 Chrome/Edge，或通过 `CHROME_PATH` 指定浏览器。未安装浏览器时脚本会失败并明确提示，不应把静态构建当作功能验收。

## 功能清单

- 文字题目 + 最多 9 张图片，支持粘贴、拖拽和相机拍照。
- 图片自动压缩（最长边 1800px、JPEG 质量 86），支持旋转、裁剪（保留原图或替换原图）、排序、删除。
- 自动/单选/多选/填空/简答五种题型。
- 解析结果：识别题干、图片观察、选项逐项判断、解题思路、关键知识点、易错点、置信度、警告。
- 追问：保留多轮上下文，可附加图片（演示模式返回本地模拟回答）。
- 历史：搜索题干/答案/知识点、只看收藏、收藏、编辑重答、删除、清空。
- 设置：API Key 显示/隐藏/清除、连接测试、模型与接口地址、历史保存、演示模式、密钥记忆。
- 响应式布局：桌面侧边栏 / 手机底部导航，支持深色模式跟随系统。

## 文件结构

```text
web/
├── index.html          # 应用壳
├── server.mjs          # 静态服务 + DeepSeek 转发代理
├── package.json
├── assets/
│   ├── app.js          # 路由、页面渲染、交互、业务编排
│   ├── api.js          # 提示词、请求、JSON 解析、演示数据
│   ├── store.js        # IndexedDB 历史与 localStorage 设置
│   ├── images.js       # 图片压缩、旋转、裁剪
│   ├── icons.js        # 内联 SVG 图标
│   └── styles.css      # 响应式视觉样式
└── README.md
```

## 本地数据

- 历史题目和图片：IndexedDB 数据库 `medical-question-ai-web`，只保存在当前浏览器。
- 设置：`localStorage` 键 `medical-question-ai.settings.v1`；默认包含明文 API Key。关闭「记住 API Key」时使用的 Key 改存 `sessionStorage`，localStorage 中的副本会被清除。
- 清除浏览器站点数据会删除全部历史和设置。
- 页面没有后端数据库，Node 服务只做静态文件和 API 转发；代理不落盘、不记录请求内容。

## 常见问题

**页面打不开**：确认 `web` 目录下已执行 `node server.mjs`，且 4173 端口没有被占用。

**真实解析报 401/402**：检查 API Key、账户余额和 Base URL；可先用「测试连接」定位问题。

**图片上传没反应**：浏览器需要支持 `createImageBitmap`；建议使用最新版 Chrome / Edge。

**换电脑后历史为空**：历史存在浏览器 IndexedDB，不跟随代码仓库同步。
