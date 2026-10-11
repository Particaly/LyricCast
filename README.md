<h1>
  Android Relay(LAN 歌词版)
  <img src="Images/app-icon-rounded-1.png" width="80" align="right" />
</h1>

基于开源项目 [saihgupr/android_relay](https://github.com/saihgupr/android_relay) 二次开发:
在保留原有 **MQTT / Home Assistant** 功能的基础上,新增 **局域网推送(LAN Push)** 通道 ——
手机把"正在播放"的媒体信息与播放进度推到局域网,PC 端通过
[ZTools](https://github.com/ZToolsCenter/ZTools) 插件**实时显示歌曲信息、封面和与播放同步的滚动歌词**,
并提供 **网易云音乐风格的桌面悬浮歌词**(独立置顶窗口,可拖动、可调字号/配色)。

```
[Android 手机:android_relay] ──WebSocket(JSON, 局域网, token 配对)──▶ [PC:ZTools 插件]
     读取系统 MediaSession(任意音乐 App)                                  接收 → 取歌词 → 实时滚动
```

- 手机端**不做每秒推送**,只发位置锚点(切歌/状态变化/拖动进度/8 秒心跳),PC 端本地插值;
- 切歌、暂停、拖动进度、切 App 端到端时延 **≤ 1 秒**;
- 原 MQTT 通道与 LAN 通道**完全独立**,可同时开启、单独开关。

---

## 目录

- [一、Android 端安装与配置](#一android-端安装与配置)
- [二、PC 端 ZTools 插件安装](#二pc-端-ztools-插件安装)
- [三、配对与验证](#三配对与验证)
- [四、后台保活(重要)](#四后台保活重要)
- [五、工作原理与协议](#五工作原理与协议)
- [六、开发者指南](#六开发者指南)
- [七、常见问题 FAQ](#七常见问题-faq)
- [八、已知限制](#八已知限制)

---

## 一、Android 端安装与配置

### 1. 安装

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/android-relay.apk
# 或使用 Release 中的 android-relay.apk
```

要求:Android 8.0+(项目 minSdk 21;在 Android 13/14 上验证)。

### 2. 授权通知监听

App 读取"正在播放"依赖系统 MediaSession,需要**通知使用权限**:

- 打开 App → 「通知使用权限未授权」卡片 → **去授权** → 允许"Android Relay"访问通知;
- 如果界面跳转困难,可用 ADB:

```bash
adb shell cmd notification allow_listener com.saihgupr.androidrelay/.MediaSessionListenerService
```

### 3. 局域网推送设置(新增通道)

打开 App,点 **扫码配对** 扫码可自动完成
下表前两项与 Token 填写并保存;也可以按下表手动填写:

| 设置 | 说明 |
|---|---|
| 启用局域网推送 | 总开关;关闭时 LAN 通道完全静默(不影响 MQTT) |
| 电脑 IP 或主机名 | 电脑的局域网 IP(插件「设置 → 连接」会显示) |
| 端口 | 默认 `18765`,与插件一致即可 |
| 配对 Token | 与插件一致;**重新生成** 可重置 |
| 推送专辑封面 | 切歌时附带封面(≤300px JPEG);关闭可再省一点流量 |
| 忽略的应用 | 黑名单包名,逗号分隔(如视频类 App:`com.spotify.music` 等) |

填好后点 **测试连接**(需先在 PC 端启动插件),再点 **保存并重连**。

**MQTT 设置卡(「高级」折叠卡)用法与原版完全一致**(Broker、Topic、认证、测试连接),见
[docs/README-upstream.md](docs/README-upstream.md) 与下方 Home Assistant 配置示例。两个通道互不影响。

### Home Assistant(原 MQTT 功能,未变更)

```yaml
mqtt:
  sensor:
    - name: "Android TV Media State"
      state_topic: "android_tv/playback_state"
      value_template: "{{ value_json.state }}"
      json_attributes_topic: "android_tv/playback_state"
      icon: mdi:television-play
```

payload 字段保持原样:`state / title / artist / app / duration`。

## 二、PC 端 ZTools 插件安装

前置:[ZTools](https://github.com/ZToolsCenter/ZTools/releases) 已安装;Node.js 16+(构建用)。

### 方式 A:直接使用仓库内的 ztools-plugin(推荐)

```bash
cd ztools-plugin
npm install                 # 构建 UI
npm run build               # 产物输出到 src-ztools/dist
cd src-ztools/preload && npm install   # 安装 ws(服务端依赖,必须)
```

然后在 **ZTools → 设置 → 插件 → 导入本地插件**,选择 `ztools-plugin/src-ztools` 目录
(内含 `plugin.json`)。

### 方式 B:开发模式

```bash
cd ztools-plugin
npm install && npm run dev          # Vite dev server, http://localhost:5173
```

导入本地插件后,ZTools 会使用 `plugin.json` 里 `development.main` 指向的 dev 地址(UI 有热重载)。
**改 preload 需要重新进入插件**(当前无 preload 热重载,待验证行为随版本变化)。

> 插件详细开发说明见 [ztools-plugin/README.md](ztools-plugin/README.md)。

### 强烈建议:开启"跟随主程序同时启动运行"

**ZTools → 设置 → 插件 → LAN 歌词 → 「跟随主程序同时启动运行」**。
开启后 ZTools 一启动插件服务就监听端口,手机随时可连;否则需要手动进入一次插件。

## 三、配对与验证

### 方式一:扫码配对(推荐,v1.1+)

1. PC:打开 ZTools,输入"歌词"或"lyrics"进入插件,首页点 **前往设置 / 配对**(或顶栏「设置」→ 连接卡)点 **配对二维码**;
2. 手机:App → **扫码配对**,对准二维码扫一下;
3. 地址/端口/Token 自动填入并保存重启服务,状态栏显示 `已连接` 即成功;
4. 配对成功后 App 会依次引导**通知权限**与**后台保活**设置(可"稍后再说",见第四节)。

二维码内容为 `androidrelay://pair?v=1&port=…&ip=…&token=…`(多网卡 IP 全部编入,
手机按序尝试),格式定义见 [docs/protocol.md §7](docs/protocol.md)。
**二维码含配对 Token,请勿截图外传。**

### 方式二:手动填写(无摄像头 / 扫码不便时)

1. PC:插件「设置 → 连接」显示 `IP:端口` 与 Token,可点 **复制配置** 一次复制三要素;
2. 手机:App → 展开「局域网推送设置」卡 → 填入 IP/端口/Token → **测试连接** → 提示"连接成功" → **保存并重连**。

### 验证

1. 手机播放任意音乐,PC 插件应在 **2 秒内**显示歌名/歌手,歌词随即滚动;
2. 插件「设置 → 连接」显示手机型号与实时延迟(ping/pong 测得);

验证清单(对应验收标准):

- 暂停/继续/拖进度/切歌/切 App → 1 秒内反映;
- 歌词对齐误差 ≤300ms,不对就在 **设置 → 歌词校准** 按 `+100/-100` 校准(可按本曲或按 App 记忆);
- Token 填错 → 握手被拒,无任何数据下发;
- PC 重启/手机切网 → 自动重连(指数退避 1s→30s,网络恢复立即重试);
- 手机端可用 `tools/mock-server.js` 独立调试,PC 端可用 `tools/mock-phone.js` 无手机演示(见第六节)。

## 四、后台保活(重要)

App 的采集服务是 `NotificationListenerService`,由**系统**管理绑定:开机自动恢复;
连接建立后(`LanPushManager`)**全程持有** WakeLock/WifiLock——不区分播放/暂停/息屏,
保证链路层心跳与重连定时器持续运转;另有前台服务(`RelayForegroundService`)以常驻
通知抬高进程优先级,即使它被杀或启动失败,连接与锁也不受影响(打开 App 时会自动补拉)。
但国内厂商 ROM 的"省电策略"仍可能杀掉/冻结它们。

### 4.1 App 内的「后台保活」卡(推荐从这里开始)

做法参考 Gotify:**扫码配对成功后会自动弹出保活引导**,按序引导完成未完成的项;
之后随时可在主界面「后台保活」折叠卡中查看与继续。四项自检:

| 自检项 | 判定方式 | 说明 |
|---|---|---|
| 电池优化白名单 | 程序检测 | 系统弹窗点「允许」,即"忽略电池优化";省电策略改「无限制」 |
| 自启动 / 后台活动 | 用户勾选确认 | 系统无 API 可查,按当前 ROM 的指引手动开启后勾选"我已完成" |
| 多任务加锁 | 用户勾选确认 | 多任务界面锁定 Android Relay 卡片,防止一键清理被杀 |
| 常驻通知栏 | 程序检测 | 不要关闭「后台播放推送」通知——它是前台保活的凭证;觉得碍眼可设为"静默",但别彻底禁用 |

- 卡片页头有汇总状态(绿点=全部就绪,橙点=有待完成项与数量);
- 引导弹窗与卡内「去设置」都会尝试直接跳到对应系统页(按当前 ROM 选择入口,
  深链失效时回退应用信息页);每个弹窗均可「稍后再说」或「不再提示」;
- 原生 Android(Pixel 等)只显示电池优化与常驻通知两项,其余自动视为"无需设置"。

### 4.2 各 ROM 手动设置速查(与 App 内指引一致)

| 厂商 | 自启动 / 后台活动 | 多任务加锁 |
|---|---|---|
| 通用 | 设置 → 电池 → Android Relay 设为「无限制/不受限」 | 多任务中长按卡片 → 锁定 |
| 小米/MIUI/澎湃 | 打开「自启动」;省电策略 → 无限制;允许「后台弹出界面」 | 长按任务卡 → 🔒 |
| 华为/荣耀 | 应用启动管理 → 关闭"自动管理":允许自启动/关联启动/后台活动 | 下滑任务卡 → 🔒 |
| OPPO/一加/realme | 允许完全后台行为;自启动管理中允许自启动与关联启动 | 长按任务卡 → 锁定 |
| vivo/iQOO | i管家 → 自启动;后台耗电 → 允许后台高耗电 | 长按任务卡 → 🔒/白名单 |
| 三星 One UI | 电池 → 后台使用限制:移出"睡眠/深度睡眠"列表 | 点任务卡应用图标 → 锁定此应用 |
| 魅族 Flyme | 手机管家 → 自启动管理 → 允许自启动 | 下滑任务卡 → 🔒 |

- **锁屏/息屏推送**:完成上述设置后,暂停/息屏/后台期间连接与推送均保持
  (WakeLock/WifiLock 在 LAN 启用期间全程持有,代价是待机耗电略增,见 protocol.md §9);
- 若系统临时解绑通知监听,App 会通过 `onListenerDisconnected → requestRebind`(Android 7+)自动恢复;
  若长时间失效,重新打开 App 检查「通知使用权限」卡是否重新出现;
- Android 13+ 的通知权限:**需要**(常驻通知在状态栏可见,也是自检项之一)。

## 五、工作原理与协议

- 协议 v1 全文:**[docs/protocol.md](docs/protocol.md)**
  (hello 握手/token 校验、state 锚点快照、artwork 封面、ping/pong 时钟校正、2MB 帧上限、错误码);
- PC 端插值公式与手机端发送策略也写在协议 §4/§5;
- Android 端多会话选择:PLAYING 优先 → 最近活跃,支持黑/白名单(纯逻辑,含单元测试);
- **安全**:token ≥16 位随机串;任何日志不打印 token;服务端限制单帧 2MB。
  ⚠️ 传输为**明文 ws://** 且 PC 监听 0.0.0.0,**仅限可信局域网使用**,不要映射到公网;
  Android 端为此放开了应用级 cleartext(`network_security_config.xml`,见文件内注释说明取舍)。

## 六、开发者指南

```bash
# Android 端
./gradlew assembleDebug            # 构建
./gradlew testDebugUnitTest        # 65 个 JVM 单测(保活锁/保活引导/位置估算/会话选择/配对码/状态缓存等)

# 插件端
cd ztools-plugin
npm test                           # vitest:39 个测试(LRC 解析/清洗/匹配/缓存/偏移/服务端插值)
npm run build                      # UI 构建到 src-ztools/dist

# 联调工具(无真机/无 ZTools 均可演示)
cd tools && npm install
node mock-server.js --token <你的token>     # 假 PC:调 Android 端
node mock-phone.js --token <你的token>      # 假手机:调插件,完整时间线(切歌/seek/暂停/心跳/封面)
node mock-phone.js --speedup 6             # 时间线 6 倍速快速过场景
```

代码结构:

```
app/src/main/kotlin/com/saihgupr/androidrelay/
├── MediaSessionListenerService.kt   # 系统监听入口;MQTT 原逻辑未动
├── MediaMetadataReader.kt           # MediaController → MediaInfo(含封面探测)
├── MediaInfo.kt                     # 数据类 + 位置估算(纯 JVM 可测)
├── SessionSelector.kt               # 多会话选择 + 黑白名单(纯 JVM 可测)
├── MqttRelay.kt                     # 原 MQTT 客户端(未改动)
├── LanPushClient.kt                 # OkHttp WebSocket + 指数退避 + 网络恢复重试
├── LanPushManager.kt                # LAN 发送策略(锚点/心跳/seek/封面/去重)
├── LanSettings.kt / LanConnectionState.kt
├── RelayLocks.kt                    # 保活锁:LAN 启用期间全程持有 WakeLock/WifiLock(纯逻辑可测)
├── RelayForegroundService.kt        # 前台保活服务(常驻通知 + 系统优先级)
├── KeepAliveGuide.kt                # 保活引导:ROM 识别/四项自检/系统页跳转(纯逻辑可测)
├── QrScanActivity.kt / PairingQr.kt # 扫码配对
└── MainActivity.kt                  # 设置页(状态卡 + 保活卡 + LAN 卡 + MQTT 卡)

ztools-plugin/
├── src/                             # Vue3 + TS UI(歌词渲染在此)
│   ├── components/Lyrics.vue        #   滚动歌词 + 校准 + 字号
│   ├── lyric/                       #   providers / 解析 / 匹配 / 缓存 / 偏移
│   └── store.ts                     #   事件聚合
└── src-ztools/                      # 导入 ZTools 的插件根
    ├── plugin.json                  #   backgroundRunning: true(后台不节流)
    └── preload/                     #   ws 服务端(核心 + 适配层,可纯 Node 测试)
```

## 七、常见问题 FAQ

**Q:歌词对不上/慢半拍?**
歌词页顶部有 `+100/-100/+500/-500` 校准按钮;默认记在"本曲",切到"此应用"可对该 App 全部歌曲生效。
部分 App(如某些车载模式)position 更新很慢,心跳 8 秒才校正一次属正常。

**Q:息屏一会儿/一键清理后就不更新了?**
若已按「后台保活」卡完成四项设置,暂停/息屏不应再断开(v0.6 起 WakeLock/WifiLock
全程持有);仍中断说明进程被 ROM 杀了——按引导补齐电池优化白名单 / 自启动 /
多任务加锁 / 常驻通知(详见第四节);一键清理后重开 App 会自动重连。

**Q:显示"进度未知 --:--"?**
系统报告 `PLAYBACK_POSITION_UNKNOWN`,协议规定不伪造位置;歌词会按暂停处理。换一首或换 App 通常恢复。

**Q:封面不显示?**
部分 App 只给 URI 或干脆没有封面。点击封面占位符会向手机重发请求;仍无则是 App 未提供。

**Q:手机端"测试连接"一直超时?**
① PC 防火墙放行 18765 入站(Windows 首次监听会弹窗,选"允许");② IP 填的是 PC 的局域网 IP 而非 127.0.0.1;③ 插件已打开或开启了"跟随主程序同时启动运行"。

**Q:歌词来源是什么?会不会失效?**
LRCLIB(主源,公益 API)+ 网易云公开接口(中文回退源)。网易云接口**非官方**,可能随时间失效;
失效时自动只用 LRCLIB,查不到的结果缓存 24 小时避免反复请求。

**Q:MQTT 和 LAN 能同时用吗?**
能。两条通道独立开关、独立去重与连接管理,互不影响。

**Q:会耗电吗?**
LAN 通道只在有事件时发包(心跳 8 秒一条,几十字节),封面仅切歌发一次;
无连接时指数退避不空转。原项目的轻量特性保留。

## 八、已知限制

- 网易云/QQ 等非官方接口可能失效(独立 provider 隔离,不影响主流程);
- 双声道之外的场景未处理:PC 控制手机播放、公网穿透、iOS 为本期范围外;
- ZTools 重启后若未开启"跟随主程序同时启动运行",需要手动进入一次插件;
- 多设备同时连接时只展示一个活跃设备(playing 优先),UI 可切换;
- 悬浮歌词(独立置顶窗口)、PC 控制播放、mDNS 自动发现为二期计划。

---

原项目(纯 MQTT 版)的英文说明归档于 [docs/README-upstream.md](docs/README-upstream.md)。
