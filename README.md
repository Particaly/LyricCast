<h1>
  Android Relay(LAN 歌词版)
  <img src="Images/app-icon-rounded-1.png" width="80" align="right" />
</h1>

基于开源项目 [saihgupr/android_relay](https://github.com/saihgupr/android_relay) 二次开发:
在保留原有 **MQTT / Home Assistant** 功能的基础上,新增 **局域网推送(LAN Push)** 通道 ——
手机把"正在播放"的媒体信息与播放进度推到局域网,PC 端通过
[ZTools](https://github.com/ZToolsCenter/ZTools) 插件**实时显示歌曲信息、封面和与播放同步的滚动歌词**。

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

- 打开 App → Service Status → **Grant Permission** → 允许"Android Relay"访问通知;
- 如果界面跳转困难,可用 ADB:

```bash
adb shell cmd notification allow_listener com.saihgupr.androidrelay/.MediaSessionListenerService
```

### 3. LAN Push 设置(新增通道)

打开 App 的 **LAN Push (Lyrics)** 卡片:

| 设置 | 说明 |
|---|---|
| Enable LAN Push | 总开关;关闭时 LAN 通道完全静默(不影响 MQTT) |
| PC IP / Hostname | 电脑的局域网 IP(插件首页会显示) |
| Port | 默认 `18765`,与插件一致即可 |
| Pairing Token | 与插件一致;**New Token** 可重新生成 |
| Send Album Artwork | 切歌时附带封面(≤300px JPEG);关闭可再省一点流量 |
| Ignored apps | 黑名单包名,逗号分隔(如视频类 App:`com.spotify.music` 等) |

填好后点 **Test Connection**(需先在 PC 端启动插件),再点 **Save LAN Settings and Reconnect**。

**MQTT 设置卡片用法与原版完全一致**(Broker、Topic、认证、Test Connection),见
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

1. PC:打开 ZTools,输入"歌词"或"lyrics"进入插件,首页显示 `IP:端口` 与 Token;
2. 手机:App → LAN Push → 填入 IP/端口/Token → **Test Connection** → 提示 `Connected!` → 保存;
3. 手机播放任意音乐,PC 插件应在 **2 秒内**显示歌名/歌手,歌词随即滚动;
4. 插件页可点 **复制配置** 一次复制三要素,顺手粘到手机。

验证清单(对应验收标准):

- 暂停/继续/拖进度/切歌/切 App → 1 秒内反映;
- 歌词对齐误差 ≤300ms,不对就按 `+100/-100` 校准(可按本曲或按 App 记忆);
- Token 填错 → 握手被拒,无任何数据下发;
- PC 重启/手机切网 → 自动重连(指数退避 1s→30s,网络恢复立即重试);
- 手机端可用 `tools/mock-server.js` 独立调试,PC 端可用 `tools/mock-phone.js` 无手机演示(见第六节)。

## 四、后台保活(重要)

App 的采集服务是 `NotificationListenerService`,由**系统**管理绑定:开机自动恢复、无需常驻前台通知。
但国内厂商 ROM 的"省电策略"可能杀掉它:

| 厂商 | 建议设置 |
|---|---|
| 通用 | 系统设置 → 电池 → 把 Android Relay 设为"不限制/无限制";关闭"电池优化" |
| 小米/MIUI | 省电策略 → 无限制;开启"自启动";锁屏后清后台改为保留 |
| 华为/鸿蒙 | 电池 → 启动管理 → 手动管理:允许自启动/关联启动/后台活动 |
| OPPO/ColorOS | 允许自启动;电池 → 应用速冻中排除本应用 |
| vivo/OriginOS | 后台耗电 → 允许后台高耗电;加速白名单中加入 |

- **锁屏/息屏推送**:在完成上述白名单设置后,息屏 5 分钟以上推送正常(监听由系统绑定,不依赖网络常驻唤醒);
- 若系统临时解绑,App 会通过 `onListenerDisconnected → requestRebind`(Android 7+)自动恢复;
  若长时间失效,重新打开 App 的权限页检查监听是否仍开启;
- Android 13+ 的通知权限:**不需要**。本应用不创建前台服务与通知,监听服务由系统绑定。

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
./gradlew testDebugUnitTest        # 19 个 JVM 单测(位置估算/会话选择/trackId)

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
└── MainActivity.kt                  # 设置页(MQTT 卡 + LAN 卡)

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

**Q:显示"进度未知 --:--"?**
系统报告 `PLAYBACK_POSITION_UNKNOWN`,协议规定不伪造位置;歌词会按暂停处理。换一首或换 App 通常恢复。

**Q:封面不显示?**
部分 App 只给 URI 或干脆没有封面。点击封面占位符会向手机重发请求;仍无则是 App 未提供。

**Q:手机端 Test Connection 一直超时?**
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
