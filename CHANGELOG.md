# CHANGELOG

本项目基于 [saihgupr/android_relay](https://github.com/saihgupr/android_relay)(upstream `a871e8c`)二次开发,
目标链路:**手机正在播放 → 局域网推送 → ZTools 插件 PC 端实时歌词**。
原有 MQTT / Home Assistant 功能与配置**未做任何变更**。

## 版本 0.6.2(2026-10-11):桌面歌词胶囊缩放防抖 + 把手可视化

实测反馈:拖动胶囊边缘/角落调整宽高时画面抖动,且缩放把手完全隐形(只有光标
形状变化),难以发现。抖动定位出三个叠加成因,全部修复:

- **w/n 方向正反馈振荡(抖动主因)**:拖拽位移原先用 `clientX/Y`(客户区坐标)
  度量,但 w/n 方向缩放时主窗口要同步移动悬浮窗以固定对侧边——视口原点跟着窗口
  动,读数 = 手势位移 + 窗口位移,预览尺寸又反过来决定窗口位移,形成回路,
  越拖越抖。现改用 `screenX/Y` 屏幕坐标度量(不受窗口移动污染,DesktopLyrics.vue);
- **预览与推送值互抢**:主窗口推送的尺寸比本地预览滞后一个往返(~300ms),
  推送一到就清掉本地预览导致回弹;预览又是居中布局,窗口尺寸异步跟进时固定边
  来回游移。现在拖动中一律忽略推送值(收到与终值一致的 ack 或超时才撤销),
  预览期间胶囊锚定到「不动的对侧边」,起手/收手与落定布局完全一致无跳动;
- **主窗口移动+缩放两步执行**:`setPosition`+`setSize` 中间会闪一帧,且位移补偿
  读 `getSize()` 会拿到 OS 尚未应用的过期值。改为一次 `setBounds` 原子完成,
  尺寸差用记账值(ZTools 白名单方法,旧版缺失时自动退化为两步,desktop-bridge.ts);
  回传链路同步提速:子窗口 ~33ms 节流入队、主窗口 100ms 轮询且同批 resize 只应用
  最后一条。

把手可视化:四边/四角缩放区扩大(10px/18px),光标贴近对应边缘即点亮指示条
(边为短线、角为圆点,拖动中持续点亮并用主题强调色),锁定态不显示。

版本:插件 0.3.2。

## 版本 0.6.1(2026-10-11):修「切后台/锁屏仍断连 + closed 1005」

实测反馈:按 0.6.0 完成全部保活设置后,切到其他 App 或锁屏仍会断连,重连时常
显示 `closed(1005)`。定位出三个叠加成因,全部修复:

- **WifiLock 模式选错(断连主因)**:AOSP 文档明确 `WIFI_MODE_FULL_LOW_LATENCY`
  **仅前台+亮屏时生效**,而 v1.2 起在 API 29+ 恰好只持这一把——切后台/锁屏后
  等效于没有 WifiLock,WiFi 进省电模式,ws PONG 被缓冲数十秒,PC 端 20s 判死
  掐断连接。现改为 **HIGH_PERF(息屏仍有效)+ LOW_LATENCY(前台低延迟)双锁
  同时持有**(`RelayLocks.kt`);
- **PC 端判死改时间制 + 关闭帧带状态码**(`lan-server-core.js`):ws PING 仍每
  10s 一次,但「连续 2 周期(20s)无 PONG 即 terminate」放宽为「距上次 PONG ≥
  45s」,容忍息屏瞬间的包缓冲;hello 超时 5s→10s(亮屏瞬间 ROM 短暂冻结进程时
  握手先完成、hello 略晚,过短窗口会误杀)。服务端全部主动关闭携带状态码+原因
  (hello 超时 1000 / 协议错 1003 / token·版本 1008 / 顶替旧连接 1000"superseded"
  / 停服 1001)——`closed(1005)` 就是"无状态码 close 帧"的 OkHttp 报法,此后
  状态栏断连文案可区分关闭来源;协议 §6.1/§9 同步;
- **亮屏/解锁立即重连**(`LanPushClient.kt`):注册 SCREEN_ON/USER_PRESENT 广播,
  ROM 息屏期间静默断链且网络回调不触发时,亮屏瞬间重连(秒级恢复,不再等最长
  30s 退避);连接健在时该广播为 no-op。
- ColorOS 保活指引补充「应用速冻」提示(strings.xml,速冻是 OPPO/一加/realme
  切后台断连的常见遗漏项)。

单测:lan-server-core 判死用例改时间制语义(12 例),插件全量 73 例通过。
版本:Android `versionCode 9 / 1.4.1`;插件 0.3.1。

## 版本 0.6.0(2026-10-11):连接永不中断(除主动退出)

需求基线变更:**除用户主动退出(LAN 开关关闭/清理后台)外,连接在任何情况下不得中断**
——暂停、息屏、切后台全部保持在线。三项结构性改动:

- **锁策略重写**(`RelayLocks.kt`):`LockStateMachine`("仅 PLAYING 持 WakeLock"的
  省电策略)替换为 `ConnectionLocks` —— LAN 启用期间**全程**持有
  PARTIAL_WAKE_LOCK(60s 超时 + 每 2s tick 续期,进程异常死亡时锁至多 60s 自动
  失效)与 WifiLock(无超时);此前"暂停+息屏必断"是设计内已知边界(protocol §9),
  现被该策略覆盖,代价是待机耗电略增;
- **锁归属移到连接持有方**:WakeLock/WifiLock 从 `RelayForegroundService` 移入
  `LanPushManager`(start 获取 / tick 续期 / shutdown 释放)——前台服务启动失败
  (Android 12+ 后台启动限制)或被杀不再导致"连接裸奔无锁";
  `RelayForegroundService` 收窄为"常驻通知 + 系统优先级",并新增进程内 `isRunning`
  存活标记;`MainActivity.onResume` 据此**幂等补拉**前台服务,堵住启动失败完全
  静默的缺口;`LanConnectionState` 废除 playback/keepalive 两组锁信号;
- **前提不变**:电池优化白名单必须完成,否则 Doze 照样切断网络,任何锁都无效
  (「后台保活」卡四项自检仍是硬前提)。

单测:`LockStateMachineTest` → `ConnectionLocksTest`(4 例,含"锁不随播放状态释放"
的回归防护);文档同步 protocol.md §9 / README 第四节与 FAQ。

## 版本 0.5.0(2026-10-10):插件界面重构 + 桌面悬浮歌词(插件 v0.3.0)

ZTools 插件从"卡片堆叠的配置页"重构为**网易云「正在播放」页式体验**,并补齐桌面歌词:

- **「歌词」首页**:封面模糊氛围背景 + 毛玻璃正在播放条(封面/标题/歌手/来源 App/
  播放态/插值进度条)+ 全高**逐行滚动歌词** —— 当前行居中放大高亮、呼吸光晕,
  rAF 插值驱动平滑滚动(seek/切歌瞬时定位),翻译内嵌当前行下方展开;
  纯文本/搜索中/无歌词/等待播放降级态;未配对时为旋转唱片空态 + 三步引导;
- **「设置」页**(顶栏切换):①桌面歌词(开关/字号/配色/翻译/恢复默认位置)
  ②歌词显示(主界面字号/翻译开关/歌词校准:本曲/此应用两维度 ±100/±500ms)
  ③连接(服务状态、多设备切换、配对二维码、端口/Token、复制配置)④关于;
  全部设置统一走 `settings.ts`(reactive + dbStorage 整块持久化,旧字号键自动迁移);
- **桌面悬浮歌词**:`ztools.createBrowserWindow` 创建独立置顶透明窗口
  (frameless/focusable:false/skipTaskbar),网易云桌面歌词同思路 —— 胶囊体可拖动
  (app-region,位置 3s 采样自动记忆),当前句大字 + 翻译/下一句小字 + 底部细进度条,
  悬停控制条(字号/配色/关闭);主窗口经 executeJavaScript 推送状态
  (签名变化即推 + 2s 心跳,悬浮窗本地插值进度),按钮消息经 800ms 轮询回传设置页,
  关闭开关联动销毁窗口;
- **歌词匹配优先 Synced**:LyricsManager 改两轮回退 —— 先跨来源找**有效时间轴**歌词
  (`isUsableSynced` 把关:≥2 行不同时间戳且跨度 ≥5s),全部没有才退纯文本;
  LRCLIB 候选挑选优先带 syncedLyrics 的记录;网易云"伪时间轴空壳"自动降级纯文本;
  新增 11 个单测(伪时间轴判定/LRC 转纯文本/两轮回退),全量 73 个;
- **构建**:vite 多页入口(index.html + desktop.html);`plugin.json` 版本 0.3.0;
  README 重写(功能/目录/桌面歌词实现要点),根 README 配对入口同步更新。

## 版本 0.4.0(2026-10-10):后台保活引导(参考 Gotify)

移动端补齐 Gotify 式的保活设置体验:**系统化的四项自检 + 按 ROM 定制的引导**,
替代 0.3.0"一次性电池弹窗 + 泛化 ROM 提示"的做法:

- **「后台保活」折叠卡**(主界面,扫码配对入口下方):页头汇总(绿点=全部就绪 /
  橙点=x/4 待完成),卡内逐项展示状态点与说明 —— ①电池优化白名单、
  ②自启动/后台活动、③多任务加锁、④常驻通知栏;
- **判定方式**:电池优化(PowerManager)与常驻通知(通知权限 + 前台服务渠道
  是否被改为"关闭")程序化实时检测;自启动与多任务加锁无系统 API 可查,
  由用户勾选"我已完成"确认(持久化到 lan_config);
- **ROM 识别与深链**:`KeepAliveGuide.RomFlavor` 按厂商/品牌识别
  小米(MIUI/澎湃)、华为/荣耀、OPPO/一加/realme(ColorOS)、vivo/iQOO、三星、魅族、
  原生 Android;每档 ROM 有专属的指引文案与自启动设置页候选深链
  (按序尝试,失效回退应用信息页);原生系统只保留可检测的两项,其余显示"无需设置";
- **配对后分步引导**:扫码成功后按 电池 → 自启动 → 加锁 → 通知 顺序逐项弹出
  未完成项的说明弹窗(带"去设置"跳转),每个弹窗可"稍后再说"或"不再提示"
  (永久关闭;兼容 0.3.0 的旧 rom_hint_dismissed 键,老用户不被再次打扰);
- **常驻通知引导**:文案明确"可设为静默/最小化,但不要彻底禁用";
  自检覆盖应用级通知被禁与渠道被关两种情况;
- **文案与文档**:strings.xml 收口全部新文案(移除旧的 rom_dialog_*),
  README 第四节重写为"App 内保活卡 + 各 ROM 速查表"(并修正了"不创建前台服务与通知"
  的过时描述),新增 FAQ"息屏后不更新";
- **版本**:Android `versionCode 7 / 1.3.0`;新增 `KeepAliveGuideTest` 11 个单测
  (ROM 识别矩阵/自检聚合/引导顺序),全量 JVM 单测 66 个。

## 版本 0.3.0(2026-10-10):主界面改版 + 全量中文化

Android 端界面从"配置优先的长表单"重构为**状态优先的单页**:

- **信息架构**:顶栏(标题 + 状态胶囊) → **连接英雄卡**(大号状态点按状态变色/呼吸动画,
  绿=已连接、橙=连接/重连中、红=认证失败、灰=未启用;副行显示目标 `host:port`;
  新增"正在推送"行实时展示当前曲目与播放态) → 条件式**权限引导卡**(授权后自动消失,
  ADB 命令默认收起) → **扫码配对**升级为全页主按钮 → LAN / MQTT 两张**默认折叠**的设置卡
  (未配置时自动展开 LAN 卡;MQTT 标注"高级") → 版本页脚;
- **中文化**:所有布局文案、Toast、对话框收口到 `values/strings.xml`(布局零硬编码),
  `MqttRelay` 测试文案中文化,成功判定改用新增的 `onConnected()` 回调
  (替代 `contains("Connected")` 子串匹配);新增 `ConnectionUi.from` 状态映射(6 个单测);
- **曲目展示链路**:`LanConnectionState` 新增 `updateTrack` 广播,`LanPushManager`
  在发送快照时同步写入(相同静默、无会话清空),仅进程内 UI 使用,不涉及协议变更;
- **行为不变**:SharedPreferences 键名、requestRebind 保存流程、扫码配对落地链路、
  电池优化/国产 ROM 引导、LAN 一次性测试(6s 超时)、前台保活启动条件全部保持;
- **版本**:Android `versionCode 6 / 1.2.0`。

## 版本 0.2.0(2026-10-10):扫码配对

连接方式由"手动抄写 IP/端口/Token"升级为**扫码配对**(协议新增 §7):

- **协议**:`docs/protocol.md` 新增 §7 配对二维码,定义 payload
  `androidrelay://pair?v=1&port=…&ip=…&token=…`(多网卡 IP 全部编入,手机按序尝试);
- **插件端**:「手机接入」卡片新增**配对二维码**展示(qrcode-generator 生成 SVG,
  `src/qr/pair-qr.ts` 构建 payload,5 个新单测);
- **Android 端**:LAN Push 卡片新增 **Scan QR to Connect**,新增 `QrScanActivity`
  扫码页(CameraX 预览/分析 + ZXing 仅 QR 解码,兼容 rowStride/pixelStride 与旋转);
  `PairingQr.parse` 从严校验(12 个新单测);扫码成功自动填入并保存重启服务,
  手动填写路径保留;相机权限/摄像头缺失时优雅降级回手动;
- **依赖**:Android 新增 `androidx.camera:*:1.3.4`、`com.google.zxing:core:3.5.3`
  (均支持 minSdk 21,camera 声明为可选特性不影响 TV 侧载);插件新增 `qrcode-generator`;
- **版本**:Android `versionCode 5 / 1.1.0`,插件 `0.2.0`。

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
