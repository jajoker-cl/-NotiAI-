# NotiAI - 智能通知过滤 🛡️

> 基于 DeepSeek AI 的 Android 智能通知过滤器。让你的手机只响重要的，不响垃圾的。

[![Version](https://img.shields.io/badge/version-5.21.8-blue)](https://github.com/jajoker-cl/-NotiAI-/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com)

---

## ☕ 支持开发者

如果这个App帮你省下了被垃圾通知轰炸的时间，欢迎请我喝杯咖啡：

<p align="center">
  <img src="donate_qr.jpg" width="200" alt="收款码">
</p>

（扫码捐赠，金额随意，感谢支持！）

---

## 📖 中文说明

### 它解决什么问题？

你是否整天开着静音/免打扰，又怕错过重要消息？NotiAI 帮你解决这个两难：

- 🔇 **默认全静音** — 所有通知来了先不出声
- 🤖 **DeepSeek AI 判断** — AI 分析每条通知是否重要
- 🔊 **重要才响** — 银行扣款、快递取件、验证码、家人消息 → 放行响铃
- 🚫 **垃圾静默** — 广告推送、促销信息、无关通知 → 直接拦截

### 怎么用？

**第一步：开启AI模式**
1. 安装后给「通知使用权」权限
2. 设置 → AI智能过滤 → 填入 DeepSeek API Key（[免费获取](https://platform.deepseek.com)）
3. 打开「启用AI模式」开关

**第二步：训练AI（关键！）**
1. 去主页「AI评判」标签查看AI的判断记录
2. 判断对的点 ✅，判断错的点 ❌ 并告诉AI哪里错了
3. 积累几天纠错数据后，点「生成规则」

**第三步：切换规则引擎**
- AI生成规则后，可以关闭AI模式
- 规则引擎以 <1ms 速度本地拦截，不吃流量不耗API
- 规则引擎拦截的通知，AI还会在后台校验——如果AI觉得不该拦，会自动恢复并弹窗提醒你

### 架构逻辑

```
通知到达 → AI模式/AI+规则模式 → 判断 → 拦截/放行
                ↓
         AI评判页面 → 用户✅❌纠错 → AI学习优化
                ↓
         积累足够数据 → 点击「生成规则」 → 规则库
                ↓
         关闭AI模式 → 纯本地 <1ms 闪电拦截
```

### 隐私

- AI模式需要联网调用DeepSeek API
- 规则模式完全离线，数据不出手机
- 不收集任何个人信息

---

## 📖 English

### What it does

NotiAI uses DeepSeek AI to intelligently filter Android notifications:

- All notifications are evaluated by AI
- Important ones (banking, delivery, verification codes, family messages) → let through
- Spam (ads, promotions, irrelevant) → blocked silently

### How to use

1. Enable notification access
2. Go to Settings → AI Filter → enter your DeepSeek API Key
3. Turn on AI mode
4. Review AI decisions in the "AI Judgement" tab, mark correct/incorrect
5. After accumulating data, click "Generate Rules" to create local rules
6. Switch to rule engine for <1ms local filtering

### Architecture

- **AI Mode**: DeepSeek cloud AI judges notifications (~1-2s)
- **Rule Engine**: Local keyword matching (<1ms)
- **AI Validation**: AI re-checks rule engine decisions, alerts you on conflicts
- **AI Rule Generation**: AI learns from your feedback and auto-generates rules

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
