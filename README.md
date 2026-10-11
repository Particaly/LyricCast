<h1 align="center">词投 LyricCast</h1>

<div align="center">

<img src="Images/app-icon-rounded-1.png" alt="词投 LyricCast" width="120">

**手机正在播放的歌，通过局域网实时投到电脑 —— 歌词同步滚动 + 桌面悬浮歌词**

_基于 [saihgupr/android_relay](https://github.com/saihgupr/android_relay) 二次开发 ·
原有 MQTT / Home Assistant 功能完整保留_

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](#-平台与要求)
[![ZTools](https://img.shields.io/badge/ZTools-插件-orange)](https://github.com/ZToolsCenter/ZTools)
[![Version](https://img.shields.io/badge/插件版本-0.4.0-blue)](./ztools-plugin/src-ztools/plugin.json)

</div>

---

Android 端读取系统 **MediaSession**（任意音乐 App），把「正在播放」的媒体信息与播放进度
通过**局域网 WebSocket** 推给 PC 端 [ZTools](https://github.com/ZToolsCenter/ZTools) 插件；
插件在本地从多个歌词源匹配并实时滚动展示，另有**网易云音乐风格的桌面悬浮歌词**
（独立置顶窗口，可拖动、可调字号/配色/对齐）。

```
[Android 手机:词投] ──WebSocket(JSON, 局域网, token 配对)──▶ [PC:ZTools 插件「词投 LyricCast」]
     读取系统 MediaSession(任意音乐 App)                          接收 → 多源匹配 → 实时滚动
```

---

## ✨ 特性

- 📱 **实时投送** — 手机读取系统 MediaSession，任意音乐 App 的歌名/歌手/封面/进度都投到 PC，切歌、暂停、拖动进度、切 App 端到端时延 **≤ 1 秒**
- ⚡ **低功耗推送** — 手机不做每秒推送，只发位置锚点（切歌/状态变化/拖动进度/8 秒心跳），PC 端本地插值补齐
- 🎤 **五源歌词聚合** — LRCLIB（主源，公益 API）/ 网易云 / 酷狗 / lrcmux 聚合 / Better Lyrics，并发匹配自动择优，各源可单独开关；查不到的结果缓存 24h
- 🎯 **候选面板** — 匹配不准可手动选词：多源候选列表、「正在播放」参考条、固定候选（按本曲/按 App 记忆）、重开面板高亮当前项
- 🀄 **繁→简转换** — 繁体歌词逐行转简体（内置字表，无网络依赖），可开关
- 🖼️ **封面兜底** — 手机没推封面时，按曲目元数据从 LrcAPI / MusicBrainz + Cover Art Archive 兜底拉图，命中缓存 30 天
- 🪟 **桌面悬浮歌词** — 独立置顶窗口，2 行视图整轨道平滑滚动（morph 过渡）；字号/主题/对齐/透明度/尺寸/锁定可调，翻译行可开关；插件转后台窗口常驻
- 📶 **扫码配对** — 二维码携带 IP/端口/Token 一键配对，多网卡 IP 全部编入按序尝试；也可手动填写
- 🔋 **后台保活** — 前台服务 + WakeLock/WifiLock 全程持有 + ROM 保活四项自检引导（交互参考 Gotify），息屏/后台不断流
- 🏠 **MQTT / Home Assistant 保留** — 上游 MQTT 通道一行未改，与 LAN 通道独立开关、互不影响
- 🔒 **安全配对** — Token ≥16 位随机串、握手校验不通过不下发数据、单帧 2MB 上限、日志不打印 Token

## 🔗 工作原理与协议

- 协议 v1 全文：**[docs/protocol.md](docs/protocol.md)**（hello 握手/Token 校验、state 锚点快照、
  artwork 封面、ping/pong 时钟校正、2MB 帧上限、错误码）；PC 端插值公式与手机端发送策略见协议 §4/§5；
- Android 端多会话选择：PLAYING 优先 → 最近活跃，支持黑/白名单（纯逻辑，含单元测试）；
- **安全须知**：传输为**明文 ws://** 且 PC 监听 `0.0.0.0`，**仅限可信局域网使用**，不要映射到公网；
  Android 端为此放开了应用级 cleartext（`network_security_config.xml`，见文件内注释说明取舍）。

## 🚀 快速开始

### 1. Android 端安装

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/android-relay.apk
# 或直接使用 Release 中的 android-relay.apk
```

安装后打开 App，按引导完成 **通知使用权限** 授权（读取"正在播放"依赖系统 MediaSession）。
界面跳转困难时可用 ADB：

```bash
adb shell cmd notification allow_listener com.saihgupr.androidrelay/.MediaSessionListenerService
```

### 2. PC 端 ZTools 插件安装

前置：[ZTools](https://github.com/ZToolsCenter/ZTools/releases) 已安装；Node.js 16+（仅构建需要）。

```bash
cd ztools-plugin
npm install                            # 构建 UI
npm run build                          # 产物输出到 src-ztools/dist
cd src-ztools/preload && npm install   # 安装 ws(服务端依赖,必须)
```

然后在 **ZTools → 设置 → 插件 → 导入本地插件**，选择 `ztools-plugin/src-ztools` 目录（内含 `plugin.json`）。

> **强烈建议开启「跟随主程序同时启动运行」**（ZTools → 设置 → 插件 → 词投 LyricCast）。
> 开启后 ZTools 一启动插件服务就监听端口，手机随时可连；否则需要手动进入一次插件。

### 3. 配对

**方式一：扫码配对（推荐，v1.1+）**

1. PC：输入"歌词"或"lyriccast"进入插件 → **前往设置 / 配对** → 点 **配对二维码**；
2. 手机：App → **扫码配对**，对准二维码扫一下；
3. 地址/端口/Token 自动填入并保存重启服务，状态栏显示 `已连接` 即成功；
4. 配对成功后 App 会依次引导**通知权限**与**后台保活**设置（可"稍后再说"）。

二维码内容为 `androidrelay://pair?v=1&port=…&ip=…&token=…`（多网卡 IP 全部编入，手机按序尝试），
格式定义见 [docs/protocol.md §7](docs/protocol.md)。**二维码含配对 Token，请勿截图外传。**

**方式二：手动填写（无摄像头 / 扫码不便时）**

1. PC：插件「设置 → 连接」显示 `IP:端口` 与 Token，可点 **复制配置** 一次复制三要素；
2. 手机：App → 展开「局域网推送设置」卡 → 填入 IP/端口/Token → **测试连接** → 提示"连接成功" → **保存并重连**。

### 4. 验证

1. 手机播放任意音乐，PC 插件应在 **2 秒内**显示歌名/歌手，歌词随即滚动；
2. 插件「设置 → 连接」显示手机型号与实时延迟（ping/pong 测得）；
3. 暂停/继续/拖进度/切歌/切 App → 1 秒内反映；歌词对齐误差 ≤300ms，不对就在歌词页校准。

## 📱 Android 端设置

### 局域网推送设置

打开 App，点 **扫码配对** 可自动完成下表前两项与 Token 填写并保存；也可手动填写：

| 设置 | 说明 |
|---|---|
| 启用局域网推送 | 总开关；关闭时 LAN 通道完全静默（不影响 MQTT） |
| 电脑 IP 或主机名 | 电脑的局域网 IP（插件「设置 → 连接」会显示） |
| 端口 | 默认 `18765`，与插件一致即可 |
| 配对 Token | 与插件一致；**重新生成** 可重置 |
| 推送专辑封面 | 切歌时附带封面（≤300px JPEG）；关闭可再省一点流量 |
| 忽略的应用 | 黑名单包名，逗号分隔（如视频类 App：`com.spotify.music` 等） |

填好后点 **测试连接**（需先在 PC 端启动插件），再点 **保存并重连**。

### Home Assistant（原 MQTT 功能，未变更）

MQTT 设置卡（「高级」折叠卡）用法与原版完全一致（Broker、Topic、认证、测试连接），见
[docs/README-upstream.md](docs/README-upstream.md)。payload 字段保持原样：`state / title / artist / app / duration`。

```yaml
mqtt:
  sensor:
    - name: "Android TV Media State"
      state_topic: "android_tv/playback_state"
      value_template: "{{ value_json.state }}"
      json_attributes_topic: "android_tv/playback_state"
      icon: mdi:television-play
```

### 后台保活（重要）

App 的采集服务是 `NotificationListenerService`，由**系统**管理绑定：开机自动恢复；连接建立后
（`LanPushManager`）**全程持有** WakeLock/WifiLock——不区分播放/暂停/息屏，保证链路层心跳与重连
定时器持续运转；另有前台服务（`RelayForegroundService`）以常驻通知抬高进程优先级，即使它被杀或
启动失败，连接与锁也不受影响（打开 App 时会自动补拉）。但国内厂商 ROM 的"省电策略"仍可能杀掉/冻结它们。

**App 内「后台保活」卡（推荐从这里开始）**：扫码配对成功后会自动弹出保活引导，按序完成未完成的项；
之后随时可在主界面「后台保活」折叠卡中查看与继续。四项自检：

| 自检项 | 判定方式 | 说明 |
|---|---|---|
| 电池优化白名单 | 程序检测 | 系统弹窗点「允许」，即"忽略电池优化"；省电策略改「无限制」 |
| 自启动 / 后台活动 | 用户勾选确认 | 系统无 API 可查，按当前 ROM 的指引手动开启后勾选"我已完成" |
| 多任务加锁 | 用户勾选确认 | 多任务界面锁定 Android Relay 卡片，防止一键清理被杀 |
| 常驻通知栏 | 程序检测 | 不要关闭「后台播放推送」通知——它是前台保活的凭证；觉得碍眼可设为"静默"，但别彻底禁用 |

- 卡片页头有汇总状态（绿点=全部就绪，橙点=有待完成项与数量）；引导弹窗与卡内「去设置」都会尝试
  直接跳到对应系统页，每个弹窗均可「稍后再说」或「不再提示」；
- 原生 Android（Pixel 等）只显示电池优化与常驻通知两项，其余自动视为"无需设置"。

**各 ROM 手动设置速查（与 App 内指引一致）**：

| 厂商 | 自启动 / 后台活动 | 多任务加锁 |
|---|---|---|
| 通用 | 设置 → 电池 → Android Relay 设为「无限制/不受限」 | 多任务中长按卡片 → 锁定 |
| 小米/MIUI/澎湃 | 打开「自启动」；省电策略 → 无限制；允许「后台弹出界面」 | 长按任务卡 → 🔒 |
| 华为/荣耀 | 应用启动管理 → 关闭"自动管理"：允许自启动/关联启动/后台活动 | 下滑任务卡 → 🔒 |
| OPPO/一加/realme | 允许完全后台行为；自启动管理中允许自启动与关联启动 | 长按任务卡 → 锁定 |
| vivo/iQOO | i管家 → 自启动；后台耗电 → 允许后台高耗电 | 长按任务卡 → 🔒/白名单 |
| 三星 One UI | 电池 → 后台使用限制：移出"睡眠/深度睡眠"列表 | 点任务卡应用图标 → 锁定此应用 |
| 魅族 Flyme | 手机管家 → 自启动管理 → 允许自启动 | 下滑任务卡 → 🔒 |

- 完成上述设置后，暂停/息屏/后台期间连接与推送均保持（代价是待机耗电略增，见 protocol.md §9）；
- 若系统临时解绑通知监听，App 会通过 `onListenerDisconnected → requestRebind`（Android 7+）自动恢复；
- Android 13+ 的通知权限**需要**（常驻通知在状态栏可见，也是自检项之一）。

## 🧩 插件功能详解

### 🖥️ 实时歌词（首页）

- 状态卡（未配对/等待播放/搜索中/播放中）、封面（点击可向手机重发请求 + 走 API 兜底）、歌名/歌手；
- 歌词逐行高亮平滑滚动，翻译行可开关；**校准** `+100/-100/+500/-500`（ms），默认记在"本曲"，
  切到"此应用"可对该 App 全部歌曲生效；字号可调；
- 歌词匹配失败时打开**候选面板**手动选词（见下）。

### 🎯 候选面板（手动选词）

- 五源候选列表一键重搜/切换来源；标题下方有「正在播放」参考条（歌名/歌手/时长），选词有对照；
- **固定候选**：所选条目随曲目持久化，重开面板与 UI 重载后仍高亮「当前」徽标；「恢复自动匹配」即清；
- 固定条目同样走缓存，后续切回本曲不再重复请求。

### 🪟 桌面歌词

- **独立置顶悬浮窗**（不随插件退出立即关闭，转后台常驻；完全退出插件才销毁）；
- 2 行视图（开翻译 = 当前句 + 译文，关翻译 = 当前句 + 下一句），行推进为 **0.55s 缓动的整轨道平滑上滑**，
  下一句滑入主行位时字号 morph 放大；seek 跨行/切歌直接落位不播动画；
- 可调：字号、主题（自动/亮/暗）、对齐（左/居中，悬浮窗工具栏可切）、透明度、尺寸、锁定位置、翻译行；
- 非 ok 状态（搜索中/无歌词/断开/等待播放）回落主行+副行居中占位。

### ⚙️ 设置页

- **连接**：配对二维码、复制配置、已连接设备（型号/延迟）、跟随主程序同时启动运行；
- **歌词来源**：LRCLIB / 网易云 / 酷狗 / lrcmux / Better Lyrics 五源独立开关、繁→简转换、封面 API 兜底开关；
- **桌面歌词**：外观项同上；**关于**：版本信息。

## 🛠️ 技术栈

- **Android 端**: Kotlin · OkHttp WebSocket（指数退避 + 网络恢复重试）· MediaSession /
  NotificationListenerService · MQTT（上游原实现）· 前台服务 + WakeLock/WifiLock
- **PC 插件**: Vue 3 + TypeScript + Vite · `ws` 服务端（preload，可纯 Node 测试）· vitest
- **歌词**: LRCLIB（公益 API，主源）· 网易云公开接口（中文回退）· 酷狗 KRC · lrcmux 聚合 ·
  Better Lyrics（TTML）；繁简转换内置字表 `t2s-table.ts`
- **封面**: LrcAPI（`api.lrc.cx`，301 到 CDN）· MusicBrainz + Cover Art Archive（官方 API）

## 📁 项目结构

```
android_relay/
├── app/src/main/kotlin/com/saihgupr/androidrelay/
│   ├── MediaSessionListenerService.kt   # 系统监听入口;MQTT 原逻辑未动
│   ├── MediaMetadataReader.kt           # MediaController → MediaInfo(含封面探测)
│   ├── MediaInfo.kt                     # 数据类 + 位置估算(纯 JVM 可测)
│   ├── SessionSelector.kt               # 多会话选择 + 黑白名单(纯 JVM 可测)
│   ├── MqttRelay.kt                     # 原 MQTT 客户端(未改动)
│   ├── LanPushClient.kt                 # OkHttp WebSocket + 指数退避 + 网络恢复重试
│   ├── LanPushManager.kt                # LAN 发送策略(锚点/心跳/seek/封面/去重)
│   ├── RelayLocks.kt                    # 保活锁:LAN 启用期间全程持有 WakeLock/WifiLock
│   ├── RelayForegroundService.kt        # 前台保活服务(常驻通知 + 系统优先级)
│   ├── KeepAliveGuide.kt                # 保活引导:ROM 识别/四项自检/系统页跳转
│   ├── QrScanActivity.kt / PairingQr.kt # 扫码配对
│   └── MainActivity.kt                  # 设置页(状态卡 + 保活卡 + LAN 卡 + MQTT 卡)
├── ztools-plugin/
│   ├── src/                             # Vue3 + TS UI
│   │   ├── components/                  #   Lyrics(滚动歌词) / DesktopLyrics(悬浮窗) / CandidatePicker(选词)
│   │   ├── lyric/                       #   providers / 解析 / 匹配 / 缓存 / 偏移 / t2s / cover
│   │   └── store.ts                     #   事件聚合
│   └── src-ztools/                      # 导入 ZTools 的插件根
│       ├── plugin.json                  #   backgroundRunning: true(后台不节流)
│       └── preload/                     #   ws 服务端(核心 + 适配层,可纯 Node 测试)
├── tools/                               # 联调 mock 工具(无真机/无 ZTools 均可演示)
└── docs/                                # 协议全文 / 上游 README 归档 / 设计文档
```

## 🧑‍💻 开发者指南

```bash
# Android 端
./gradlew assembleDebug            # 构建
./gradlew testDebugUnitTest        # 65 个 JVM 单测(保活锁/保活引导/位置估算/会话选择/配对码/状态缓存等)

# 插件端
cd ztools-plugin
npm test                           # vitest:221 个测试(LRC 解析/清洗/匹配/缓存/偏移/服务端插值/t2s/封面兜底)
npm run build                      # UI 构建到 src-ztools/dist

# 联调工具(无真机/无 ZTools 均可演示)
cd tools && npm install
node mock-server.js --token <你的token>     # 假 PC:调 Android 端
node mock-phone.js --token <你的token>      # 假手机:调插件,完整时间线(切歌/seek/暂停/心跳/封面)
node mock-phone.js --speedup 6             # 时间线 6 倍速快速过场景
```

> 插件开发模式：`cd ztools-plugin && npm run dev` 启动 Vite dev server，
> ZTools 会使用 `plugin.json` 里 `development.main` 指向的 dev 地址（UI 热重载）。
> 改 preload 需要重新进入插件。插件侧详细说明见 [ztools-plugin/README.md](ztools-plugin/README.md)。

## 📋 平台与要求

| 组件 | 要求 |
|---|---|
| Android 端 | Android 8.0+（项目 minSdk 21；在 Android 13/14 上验证） |
| PC 插件 | 已安装 [ZTools](https://github.com/ZToolsCenter/ZTools/releases)；随 ZTools 跨平台 |
| 构建 | Node.js 16+（仅 PC 插件构建需要） |
| 网络 | 手机与 PC 同一局域网；PC 防火墙放行 `18765` 入站 |

- 网易云/QQ 等非官方接口可能随时间失效（独立 provider 隔离，失效时自动只用其余源，不影响主流程）；
- 多设备同时连接时只展示一个活跃设备（playing 优先），UI 可切换；
- PC 控制手机播放、公网穿透、iOS 为范围外；悬浮歌词增强、mDNS 自动发现为二期计划。

## ❓ 常见问题 FAQ

**Q:歌词对不上/慢半拍？**
歌词页顶部有 `+100/-100/+500/-500` 校准按钮；默认记在"本曲"，切到"此应用"可对该 App 全部歌曲生效。
部分 App（如某些车载模式）position 更新很慢，心跳 8 秒才校正一次属正常。

**Q:息屏一会儿/一键清理后就不更新了？**
按「后台保活」卡完成四项设置后，暂停/息屏不应再断开；仍中断说明进程被 ROM 杀了——按引导补齐
电池优化白名单 / 自启动 / 多任务加锁 / 常驻通知（见上文速查表）；一键清理后重开 App 会自动重连。

**Q:显示"进度未知 --:--"？**
系统报告 `PLAYBACK_POSITION_UNKNOWN`，协议规定不伪造位置；歌词会按暂停处理。换一首或换 App 通常恢复。

**Q:封面不显示？**
部分 App 只给 URI 或干脆没有封面。点击封面占位符会向手机重发请求并立即走一次 API 兜底；仍无则是两端都拿不到。

**Q:手机端"测试连接"一直超时？**
① PC 防火墙放行 18765 入站（Windows 首次监听会弹窗，选"允许"）；② IP 填的是 PC 的局域网 IP 而非 127.0.0.1；
③ 插件已打开或开启了"跟随主程序同时启动运行"。

**Q:歌词来源是什么？会不会失效？**
LRCLIB（主源，公益 API）+ 网易云/酷狗/Better Lyrics 等公开接口，lrcmux 聚合兜底。
非官方接口可能随时间失效；失效时自动只用其余源，查不到的结果缓存 24 小时避免反复请求。

**Q:MQTT 和 LAN 能同时用吗？**
能。两条通道独立开关、独立去重与连接管理，互不影响。

**Q:会耗电吗？**
LAN 通道只在有事件时发包（心跳 8 秒一条，几十字节），封面仅切歌发一次；无连接时指数退避不空转。
保活锁在 LAN 启用期间全程持有，待机耗电略增。

## 🐛 问题反馈

遇到问题？请在 [Issues](https://github.com/Particaly/android_relay/issues) 中反馈，并尽量包含：

- 手机型号与 Android 版本、PC 操作系统与 ZTools 版本
- 插件/插件端版本、复现步骤、日志（如有）

## 💝 致谢

- [saihgupr/android_relay](https://github.com/saihgupr/android_relay) — 上游项目（MQTT / Home Assistant 版）
- [ZTools](https://github.com/ZToolsCenter/ZTools) — 插件宿主
- [LRCLIB](https://lrclib.net/) — 公益歌词 API（主源）
- [Better Lyrics](https://better-lyrics.boidu.dev/) — TTML 歌词源
- [Gotify](https://github.com/gotify/android) — 后台保活引导交互参考
- [Vue.js](https://vuejs.org/) · [Vite](https://vite.dev/) — 前端框架与构建工具

---

原项目（纯 MQTT 版）的英文说明归档于 [docs/README-upstream.md](docs/README-upstream.md)。

<div align="center">

**如果这个项目对你有帮助，请给个 Star ⭐️**

</div>
