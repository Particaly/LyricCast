# CHANGELOG

本项目基于 [saihgupr/android_relay](https://github.com/saihgupr/android_relay)(upstream `a871e8c`)二次开发,
目标链路:**手机正在播放 → 局域网推送 → ZTools 插件 PC 端实时歌词**。
原有 MQTT / Home Assistant 功能与配置**未做任何变更**。

## 版本 0.1.0(2026-10-10)

### 提交一览

| 提交 | 内容 |
|---|---|
| `6cf018b` | docs:调研结论与五阶段实施计划(`docs/dev-plan.md`) |
| `7d5b6e7` | Android:媒体信息扩展(位置/速度/专辑/封面元数据),仅日志,MQTT 不动 |
| `82bd6e1` | Android:LAN Push 通道(OkHttp WebSocket)+ 协议 v1 文档 + mock-server + 设置 UI |
| `7168f04` | 插件:ZTools 插件骨架(CLI 生成)+ 协议服务端 + 基础 UI + mock-phone |
| `997abbd` | 插件:补 .gitignore(CLI 生成器跳过隐藏文件),untrack 构建产物 |
| `a97c9d5` | 插件:歌词源(LRCLIB/网易云)+ LRC 解析 + 滚动展示 + 手动校准 + 39 个单测 |

### 改动文件清单

**Android 端**(`app/`):

- 新增 `src/main/kotlin/.../MediaInfo.kt` —— 播放状态枚举(协议取值)、TrackInfo(含
  `stableId = sha1(title|artist|album|duration)`)、PlaybackInfo(位置估算:playing 时
  `position + elapsed×speed` 夹到 `[0,duration]`,UNKNOWN 不伪造)、MediaInfo;
- 新增 `.../MediaMetadataReader.kt` —— 全量元数据读取 + 封面探测
  (ALBUM_ART→ART→ALBUM_ART_URI→ART_URI→DISPLAY_ICON→DISPLAY_ICON_URI,位图按需才解码);
- 新增 `.../SessionSelector.kt` —— 多会话选择(PLAYING 优先→最近活跃)+ 黑/白名单(纯逻辑);
- 新增 `.../LanPushClient.kt` —— OkHttp WebSocket,指数退避 1s→30s,网络恢复立即重试,
  回调统一切主线程,支持一次性模式(设置页"测试连接");
- 新增 `.../LanPushManager.kt` —— 发送策略:track_change/playback_change/seek(外推偏差>2s)
  立发 + playing 心跳 8s;去重只吞"无位置变化的重复事件";封面切歌单发
  (≤300px JPEG q80 Base64,线程池处理);ping/pong;token 不落日志;
- 新增 `.../LanSettings.kt`("lan_config",独立于 MQTT)、`.../LanConnectionState.kt`(进程内状态广播);
- 新增 `src/test/java/.../PlaybackInfoTest.kt`、`SessionSelectorTest.kt`(含 TrackIdTest)—— 19 个用例;
- 新增 `src/main/res/xml/network_security_config.xml` —— 放开明文 ws(取舍见文件注释/协议 §6);
- 修改 `MediaSessionListenerService.kt` —— 构建 MediaInfo 供 LAN 与日志;补
  `onSessionDestroyed` 清理与 `onListenerDisconnected → requestRebind`(API 24+ 守卫);
  **MQTT payload 与去重逻辑逐字未动**;
- 修改 `MainActivity.kt` / `activity_main.xml` —— 新增 LAN Push 设置卡(开关/地址/端口/Token/
  封面开关/黑名单/测试连接/状态),MQTT 卡未动;
- 修改 `AndroidManifest.xml`(networkSecurityConfig)、`app/build.gradle`(okhttp、junit)。

**协议与工具**:

- 新增 `docs/protocol.md` —— 协议 v1:消息定义、发送策略、去重与 seek 判定、PC 端插值与
  ping/pong 时钟校正、版本兼容、安全说明、预留 control 消息;
- 新增 `tools/mock-server.js`(假 PC)、`tools/mock-phone.js`(假手机,完整时间线 + `--speedup`)。

**ZTools 插件**(`ztools-plugin/`,由 `@ztools-center/plugin-cli` 的 vue-vite 模板生成后开发):

- 新增 `src-ztools/plugin.json` —— 单 feature `lyrics`;`pluginSetting.backgroundRunning: true`
  (ZTools 源码确认:关闭隐藏态节流,服务常驻);`development.main` 指向 Vite dev;
- 新增 `src-ztools/preload/lan-server-core.js` —— 协议服务端核心(纯 Node 可测):token 握手
  (5s 超时/bad_token 拒绝)、2MB 帧上限、20s 空闲看门狗、10s ping(RTT + 时钟偏差 EMA)、
  活跃设备选择、`getPosition` 插值(协议 §5,可注入时钟);
- 新增 `src-ztools/preload/server.js` —— `window.services.lanLyrics` 适配层
  (ztools.dbStorage 持久化配置,无 ztools 环境自动退化);
- 新增 `src/lyric/*` —— LRCLIB 主源(`get`→`search`)、网易云回退源(非官方,失败隔离);
  标题/歌手清洗;Dice 相似度 + 时长权重的候选挑选;LRC 解析(多时间戳/offset/增强标签);
  缓存(24h 负缓存);5s 超时 + 切歌取消;手动偏移(本曲/此应用两维度);
- 新增 `src/components/Lyrics.vue` —— rAF 驱动滚动歌词(seek 大跳瞬时定位)、翻译条、
  纯文本/无歌词降级、校准按钮、字号持久化;
- 修改 `src/store.ts`(事件聚合 + 歌词生命周期)、`src/App.vue`(歌曲卡 + 插件接线)、
  `src/env.d.ts`(服务面类型)、`src/main.css`(深浅色主题变量);
- 测试:39 个 vitest 用例(含服务端插值的确定性时钟测试);两个 provider 已真网验证。

**文档**:

- 新增 `docs/dev-plan.md`(调研与计划)、`docs/protocol.md`(协议)、`docs/README-upstream.md`(原英文 README 归档);
- 重写 `README.md`(中文:安装/权限/配对/保活/FAQ/开发者指南);
- 重写 `ztools-plugin/README.md`(插件开发与使用);删除模板自带 `ztools-plugin/CHANGELOG.md`(并入本文件);
- `.gitignore` 增加 `node_modules/`。

### 已知限制

1. **传输明文**:ws:// 无加密,仅限可信局域网;Android 端因此应用级放开了 cleartext
   (无法按动态 IP 限定 networkSecurityConfig,取舍见 `network_security_config.xml` 注释);
2. **网易云/QQ 等非官方接口**可能随时失效或风控(已隔离在独立 provider,失败只影响该源);
   合规上仅作个人歌词展示用途;
3. **厂商 ROM 杀后台**:NotificationListener 依赖系统绑定,但小米/华为/OPPO 等需要手动
   白名单(README 第四节);极端情况下仍可能失效,需重新授权;
4. **ZTools 生命周期**:ZTools 完全退出或用户"终止插件"时服务停止;重启后需开启
   「跟随主程序同时启动运行」或手动进入一次插件(preload 无热重载;以下行为**待验证**:
   不同 ZTools 版本对导入的本地插件自启动的表现可能不同);
5. **元数据质量**:部分 App 的 title 是"歌名 - 歌手"拼接、artist 为空、position 长期
   UNKNOWN(协议标记 `positionKnown=false`,UI 显示 `--:--`,不伪造数据);
6. **多设备**:同时只展示一个活跃设备;设备切换为手动(下拉框);
7. LRCLIB 为公益服务,可能限流(已做缓存与负缓存,未做重试风暴保护);
8. `paho.android.service:1.1.1` 依赖声明未使用且与高 targetSdk 存在兼容隐患,按
   "不顺手重构"原则保留;若未来构建失败可优先排查它。

### 后续建议(二期)

- 桌面悬浮歌词:ZTools `createBrowserWindow` 支持 `alwaysOnTop/transparent`
  (已在源码核实),可做独立置顶小窗;
- PC → 手机控制消息:协议已预留 `{"type":"control"}`,Android 端用
  `MediaController.transportControls` 即可实现播放/暂停/切歌;
- mDNS(Bonjour)自动发现 + 二维码配对,免手动填 IP;
- 增强 LRC(逐字/KTV)渲染(解析器已标记 `isEnhanced`);
- 频控与拉黑:服务端对连续握手失败的来源临时拒绝;
- 手机端通知栏快捷开关 LAN 通道(TileService)。
