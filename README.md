# Not I - AI智能通知过滤器

一个基于AI的Android通知过滤应用，智能识别并拦截垃圾通知，只保留重要信息。

[中文](#中文) | [English](#english)

---

## 中文

### 功能特性

**🤖 AI智能过滤**
- AI实时分析每条通知
- 自动拦截垃圾短信、营销推送、广告通知
- 只放行重要信息（银行提醒、快递通知、验证码、工作消息）
- 使用DeepSeek API进行智能内容分析

**📝 自动规则生成**
- AI自动从被拦截的通知中提取关键词
- 为未来类似通知创建过滤规则
- 规则通过 PENDING → CONFIRMED 机制逐步优化
- 命中次数追踪，评估规则质量

**🎯 灵活规则系统**
- 黑名单：拦截匹配特定模式的通知
- 白名单：只放行匹配特定模式的通知
- 堆叠：将多个通知合并为可展开的分组
- 支持正则表达式进行高级模式匹配

**⏰ 定时规则**
- 设置规则在特定时间段生效
- 适合工作时间、睡眠时间、会议期间使用

**📊 统计与监控**
- 实时AI判断统计
- 跟踪被拦截通知数量
- 监控自动生成规则的表现
- AI生成规则的可视化标识

**🔒 隐私优先**
- 无需网络权限（AI API除外）
- 不收集任何数据
- 所有处理在设备本地或通过你自己的API密钥完成
- 开源透明

### 安装方法

1. 从 [Releases](https://github.com/jajoker-cl/-NotiAI-/releases) 下载最新APK
2. 安装到Android设备（需要Android 7.0+）
3. 授予通知监听权限
4. 在设置中配置DeepSeek API密钥
5. 启用AI过滤功能

### 使用方法

1. **启用AI过滤**
   - 进入 设置 → AI功能
   - 开启"AI过滤"开关
   - 输入你的DeepSeek API密钥

2. **查看统计**
   - 在设置中查看AI判断次数
   - 在历史记录中查看被拦截的通知
   - 在规则标签中查看自动生成的规则

3. **管理规则**
   - 查看带有"AI"标识的自动生成规则
   - 手动创建自定义规则
   - 导入/导出规则为JSON文件

### 技术细节

**架构**
- Kotlin + Jetpack Compose + Material 3
- NotificationListenerService 实现实时过滤
- 后台AI处理，带超时机制
- LRU缓存存储AI判断结果

**AI集成**
- DeepSeek API进行通知分析
- 异步处理（不阻塞主线程）
- 5秒超时，失败时放行
- 从AI结果自动生成规则

**文件结构**
```
app/src/main/java/com/donotnotify/donotnotify/
├── AiJudgment.kt              # AI判断结果数据类
├── AiMetadata.kt              # AI规则元数据
├── AiNotificationCache.kt     # AI结果LRU缓存
├── AiNotificationJudge.kt     # 核心AI判断类
├── AiRuleGenerator.kt         # 自动规则生成
├── AiStatsStorage.kt          # AI统计存储
├── BlockerRule.kt             # 规则数据模型
├── NotificationBlockerService.kt  # 主通知服务
└── ui/screens/
    ├── SettingsScreen.kt      # AI设置界面
    └── RulesScreen.kt         # 规则显示（带AI标识）
```

### 编译

```bash
# Debug版本
./gradlew assembleDebug

# Release版本
./gradlew assembleRelease
```

**要求：**
- Android Studio Hedgehog+
- JDK 11+
- Android SDK 34

### 许可证

MIT License - 详见 [LICENSE](LICENSE)

---

## English

### Features

**🤖 AI-Powered Smart Filtering**
- AI analyzes every notification in real-time
- Automatically blocks spam, promotions, and unwanted notifications
- Only allows important alerts (banking, deliveries, OTPs, work)
- Uses DeepSeek API for intelligent content analysis

**📝 Automatic Rule Generation**
- AI automatically extracts keywords from blocked notifications
- Creates filtering rules for future similar notifications
- Rules improve over time with PENDING → CONFIRMED progression
- Hit count tracking for rule quality assessment

**🎯 Flexible Rule System**
- Denylist: Block notifications matching specific patterns
- Allowlist: Only allow notifications matching specific patterns
- Stack: Collapse multiple notifications into expandable groups
- Regex support for advanced pattern matching

**⏰ Time-Based Rules**
- Schedule rules to activate during specific time windows
- Perfect for work hours, sleep time, or meetings

**📊 Statistics & Monitoring**
- Real-time AI judgment statistics
- Track blocked notifications count
- Monitor auto-generated rules performance
- Visual indicators for AI-generated rules

**🔒 Privacy First**
- No network permissions required (except for AI API)
- No data collection or tracking
- All processing happens on-device or via your own API key
- Open source and transparent

### Installation

1. Download the latest APK from [Releases](https://github.com/jajoker-cl/-NotiAI-/releases)
2. Install on your Android device (requires Android 7.0+)
3. Grant notification listener permission
4. Configure your DeepSeek API key in Settings
5. Enable AI filtering

### Usage

1. **Enable AI Filtering**
   - Go to Settings → AI Features
   - Toggle "AI Filtering" ON
   - Enter your DeepSeek API key

2. **Monitor Statistics**
   - View AI judgment count in Settings
   - Check blocked notifications in History
   - See auto-generated rules in Rules tab

3. **Manage Rules**
   - View AI-generated rules with special "AI" badge
   - Manually create custom rules
   - Import/Export rules as JSON

### Technical Details

**Architecture**
- Kotlin + Jetpack Compose + Material 3
- NotificationListenerService for real-time filtering
- Background AI processing with timeout handling
- LRU cache for AI judgment results

**AI Integration**
- DeepSeek API for notification analysis
- Asynchronous processing (non-blocking)
- 5-second timeout with fail-open design
- Automatic rule generation from AI results

**File Structure**
```
app/src/main/java/com/donotnotify/donotnotify/
├── AiJudgment.kt              # AI judgment result data class
├── AiMetadata.kt              # AI rule metadata
├── AiNotificationCache.kt     # LRU cache for AI results
├── AiNotificationJudge.kt     # Core AI judgment class
├── AiRuleGenerator.kt         # Automatic rule generation
├── AiStatsStorage.kt          # AI statistics storage
├── BlockerRule.kt             # Rule data model
├── NotificationBlockerService.kt  # Main notification service
└── ui/screens/
    ├── SettingsScreen.kt      # AI settings UI
    └── RulesScreen.kt         # Rules display with AI badges
```

### Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

**Requirements:**
- Android Studio Hedgehog+
- JDK 11+
- Android SDK 34

### License

MIT License - See [LICENSE](LICENSE) for details

---

## Credits

This project is based on [DoNotNotify](https://github.com/anujja/DoNotNotify) by [anujja](https://github.com/anujja).

Modified and enhanced with AI-powered features by [jajoker-cl](https://github.com/jajoker-cl).
