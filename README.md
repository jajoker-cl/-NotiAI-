# NotiAI - 智能通知过滤 🛡️

> 越用越懂你，Smarter with every use.

[![Version](https://img.shields.io/badge/version-5.21.8-blue)](https://github.com/jajoker-cl/-NotiAI-/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)

---

## ☕ 支持开发者

如果NotiAI帮你省下了被垃圾通知轰炸的时间，欢迎请开发者喝杯咖啡：

<p align="center">
  <img src="donate_qr.jpg" width="200" alt="收款码">
</p>

---

## 中文简介

### 一句话标语

越用越懂你，Smarter with every use.

### 产品介绍

是否长期开启手机静音或免打扰，却担心遗漏验证码、快递、家人私信等重要通知？NotiAI 基于 DeepSeek 大模型打造安卓端智能通知过滤工具，完美解决这一痛点。

默认全局静默所有推送，由AI逐条判别消息优先级：银行交易、取件提醒、验证码、亲友对话自动放行响铃；广告营销、测速弹窗、垃圾推广直接静默拦截。

### 使用流程

1. 授予通知使用权，在设置填入 DeepSeek API Key，开启AI智能过滤；
2. 在「AI评判」页面人工标注AI判断对错，持续迭代模型判断力；
3. 数据积累完成一键生成本地规则，切换纯离线规则引擎，实现1ms以内极速本地拦截，零流量消耗、不再调用API；
4. 规则引擎拦截结果仍由AI二次兜底校验，误拦消息自动提醒恢复。

### 运行架构

```
通知推送 → AI云端判别 / 本地规则引擎拦截 → 放行或静默拦截
用户对错反馈 → AI持续学习 → 批量生成静态规则库 → 纯离线极速过滤
```

### 隐私说明

AI联网模式仅调用DeepSeek接口；规则引擎完全离线运行，所有数据仅存储于本机，不上传、不收集任何个人隐私信息。

---

## English Introduction

### Slogan

Smarter with every use, the more you use it, the better it knows you.

### Overview

Tired of keeping your phone on Do Not Disturb mode for anti-spam but afraid of missing critical messages? NotiAI is an Android intelligent notification filter powered by DeepSeek LLM.

It mutes all notifications by default and judges every single message via AI. Important alerts including bank deductions, parcel pick-up codes, verification codes and family chats will ring normally; ads, promotions and irrelevant pop-ups will be blocked silently.

### Usage Guide

1. Grant notification access permission, input your DeepSeek API Key in Settings and enable AI filter mode.
2. Review AI judgement records on the AI Judgement page, mark correct or wrong decisions to train the model continuously.
3. Generate local static rules with one click after sufficient feedback. Switch to rule engine for ultra-fast local interception within 1ms without API calls or mobile data.
4. AI will double-check results blocked by local rules and remind you automatically for false interceptions.

### Architecture

```
Incoming notifications → Cloud AI judgement / Local rule engine → Release or block silently
User feedback correction → Model continuous learning → Auto generate local rules → Fully offline lightweight filtering
```

### Privacy Policy

AI mode requires network access to call DeepSeek API. Rule engine works entirely offline. All data is stored locally on your device, no personal data will be collected or uploaded externally.

---

## 🙏 致谢 | Credits

| 角色 | 贡献者 |
|------|--------|
| **原作者** | [Anuj Jain](https://github.com/anujja) — DoNotNotify 原始版本 |
| **AI功能开发** | [jajoker-cl](https://github.com/jajoker-cl) — DeepSeek集成、AI评判、规则生成 |

原版仓库：[github.com/anujja/DoNotNotify](https://github.com/anujja/DoNotNotify)

---

## 📄 License

MIT License — 详见 [LICENSE](LICENSE)
