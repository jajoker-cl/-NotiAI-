# NotiAI - 智能通知过滤 (DoNotNotify AI版)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)

> 📱 基于DeepSeek AI的Android智能通知过滤器 | AI-powered Android notification filter

基于 [DoNotNotify](https://github.com/anujja/DoNotNotify) 二次开发，新增 **DeepSeek AI 智能判断** 功能，让AI帮你决定哪些通知值得响铃。

Built on [DoNotNotify](https://github.com/anujja/DoNotNotify) with added **DeepSeek AI** integration for intelligent notification filtering.

---

## ✨ 功能特点 | Features

### 🆕 AI智能过滤 (新增)
- **DeepSeek AI 接管** — 开启AI模式后，所有通知由DeepSeek判断重要性
- **一句话调教** — 对AI说"银行扣款、快递到了、验证码这些放行"，AI自动理解
- **AI日志查看** — 每条通知的处理过程全记录（来了什么 → AI判断 → 拦截还是放行）
- **纠错反馈** — 点一条日志标记"判断错误"，AI下次会更准

### 📋 原版规则引擎 (保留)
- **通知拦截** — 基于NotificationListenerService实时过滤
- **灵活规则** — 黑名单/白名单/堆叠三种模式
- **关键词匹配** — 支持标题/正文关键词和正则表达式
- **时间规则** — 指定时间段生效
- **预置规则** — 40+常用App预置规则
- **完全离线** — 不需要网络权限（AI模式除外）

---

## 📥 下载 | Download

[📦 下载最新APK](https://github.com/jajoker-cl/-NotiAI-/releases)

或从 [Releases](https://github.com/jajoker-cl/-NotiAI-/releases) 页面下载

---

## ⚙️ AI模式使用 | Setup

1. 安装后给 **通知使用权** 权限
2. 进入 **设置 → AI智能过滤**
3. 填入你的 **DeepSeek API Key**（[获取地址](https://platform.deepseek.com)）
4. 打开 **启用AI模式** 开关
5. 在"对AI说句话"里写你的偏好，例如：`银行交易、快递物流、验证码、家人消息放行，其余拦截`

AI日志可在设置页查看，点❌可纠错反馈。

---

## 🛠 编译 | Build

```bash
git clone https://github.com/jajoker-cl/-NotiAI-.git
cd 项目目录
./gradlew assembleDebug
```

APK输出: `app/build/outputs/apk/debug/app-debug.apk`

**开发环境:**
- Android Studio + JDK 21
- Gradle 8.13
- Kotlin 2.0 + Jetpack Compose

---

## 🙏 致谢 | Credits

| 角色 | 贡献者 |
|------|--------|
| **原作者** | [Anuj Jain](https://github.com/anujja) — DoNotNotify 原版 |
| **AI功能开发** | [jajoker-cl](https://github.com/jajoker-cl) — DeepSeek集成、AI日志、纠错系统 |
| **原版仓库** | [github.com/anujja/DoNotNotify](https://github.com/anujja/DoNotNotify) |

---

## 📄 License

MIT License — 详见 [LICENSE](LICENSE)
