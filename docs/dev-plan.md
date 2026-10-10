# 二次开发现状分析与实施计划(LAN 歌词推送)

> 状态:**待确认**。本文档对应「手机正在播放 → 局域网推送 → ZTools 插件 PC 端实时歌词」任务的
> 第 8 节工作方式第 1 条:先调研后编码。确认后按第 4 节分阶段实施,每阶段独立 commit。
> 所有 ZTools 结论均来自官方仓库源码(ZToolsCenter/ZTools,已 shallow clone 核对)与官方文档站
> (https://ztoolscenter.github.io/ZTools-doc/),标注了出处;未能 100% 确认的点标注「待验证」。

---

## 一、android_relay 现状分析

### 1.1 项目构成(全部源码已通读)

| 文件 | 职责 |
|---|---|
| `MediaSessionListenerService.kt` | `NotificationListenerService` + `MediaSessionManager.OnActiveSessionsChangedListener`。为每个活跃会话的包名注册 `MediaController.Callback`,`onPlaybackStateChanged` / `onMetadataChanged` 触发上报。 |
| `MqttRelay.kt` | Paho Java 客户端(`org.eclipse.paho.client.mqttv3` 的**同步** `MqttClient`,自起线程连接),`isAutomaticReconnect=true`、QoS1,发布到 `tcp://<broker>:1883`。 |
| `MainActivity.kt` | 单 Activity 设置页:通知权限引导、MQTT Broker/Topic/认证、Test Connection,保存后 `requestRebind`。 |
| `activity_main.xml` | ScrollView + 卡片式布局(状态卡、MQTT 配置卡),风格可直接复用给 LAN 设置卡。 |

构建参数:`minSdk 21`、`compileSdk/targetSdk 34`、AGP 8.2.2、Gradle 8.5(wrapper)、Kotlin 1.8.0、
`source/targetCompatibility 1.8`。依赖仅 core-ktx、appcompat、paho mqttv3 1.2.5、
`paho.android.service:1.1.1`(**已声明但代码未使用**)。

### 1.2 关键行为细节(与设计相关)

1. **MQTT payload**:`{state, title, artist, app, duration(秒)}`;state 映射为
   `playing / paused / buffering / idle`(`STATE_STOPPED`/`STATE_NONE` 都归为 `idle`)。
2. **"智能缓存"实为 per-app 的整条 payload 字符串等值去重**(`lastPublishedPayloads[app]`)。
   MQTT payload 不含位置,所以它天然与位置无关;LAN 通道只要不复用这个去重器即可完全正交,
   不需要改动现有逻辑。
3. `onPlaybackStateChanged` 高频触发是常态(部分 App 用它推送进度),这是现有缓存存在的理由。
4. **无 `onSessionDestroyed` 处理**(依赖 `onActiveSessionsChanged` 兜底),**无
   `onListenerDisconnected` 处理**(API 24+ 系统回收监听后不会自动 requestRebind)。
5. Manifest 已有 `INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`(后者未用);
   **没有** `networkSecurityConfig` / `usesCleartextTraffic`。MQTT 走原生 socket,不受
   cleartext HTTP 策略影响,所以现状能工作;但 **OkHttp 的 `ws://` 会被 targetSdk 28+ 的
   默认 cleartext 策略拦截**,必须处理(见 4.2 阶段 2 的取舍说明)。
6. 保存配置后已使用 `NotificationListenerService.requestRebind()`,该机制可复用。

### 1.3 基线构建

本机 JDK 21(Temurin 21.0.9)、`local.properties` 指向 `C:/Users/98014/AppData/Local/Android/Sdk`,
`./gradlew assembleDebug` 基线构建已在后台验证(结果见交付总结)。

---

## 二、ZTools 机制核实结果(逐条回答任务书问题)

| 任务书问题 | 核实结论 | 证据 |
|---|---|---|
| preload 能否用 npm 依赖 | **能**。preload 遵循 CommonJS,可 require 原生模块、自写模块、第三方 npm 包;要求 preload.js 同级有 `package.json`(`type: "commonjs"`)且 `node_modules` 实际存在、随插件分发,源码保持可读(不得打包/压缩/混淆——这是发布审核要求,本地开发不受影响)。`ws` 纯 JS 无原生依赖,适用。 | 官方文档 node-js 页;file-structure 页 |
| UI 与 preload 如何通信 | **共享 window 上下文**(主进程对插件视图 `contextIsolation:false`),preload 把函数挂到 `window.*` 上,UI 直接调用,无需 IPC。 | preload-js 文档 + `pluginWindowManager.ts:248` |
| 关闭窗口后能否后台常驻 | **能,但有两个前提**。① 插件视图被隐藏/切走时**只从 contentView 移除、实例保留复用**,不销毁;只有用户「终止插件」/ ZTools 退出 / 插件崩溃才销毁。② 默认隐藏后启用 Electron `backgroundThrottling`(rAF/timer 被节流),plugin.json 写 `"pluginSetting": { "backgroundRunning": true }` 可关闭节流,后台全速运行。 | `pluginManager.ts:907`(隐藏保留)、`:98/:346`(backgroundRunning)、`:724`(读取配置) |
| 服务如何随 ZTools 启动 | 每个插件在设置里有「**跟随主程序同时启动运行**」开关,开启后 ZTools 启动即 `preloadPlugin()` 预加载插件 → preload 里的 WebSocket 服务随之启动。 | `appMain.ts:175-198`;`PluginDetailToolbar.vue:160` |
| 独立/置顶/透明窗口 | **支持**。`ztools.createBrowserWindow(url, options, callback)`,options 与 Electron `BrowserWindowConstructorOptions` 一致(全量透传),支持 `alwaysOnTop`/`transparent`/`frame:false`;URL 仅允许本地相对路径或 `file://`。→ 二期「桌面悬浮歌词」可行。 | `plugin-api.html`;`window.ts:32-55`;`pluginWindowManager.ts:242`(`...options`) |
| 持久化存储 API | `ztools.db.put/get/remove/bulkDocs/allDocs`(文档型,需 `_id`;`promises.*` 同形)+ `postAttachment/getAttachment`(存封面二进制可行);简化键值 `ztools.dbStorage.setItem/getItem/removeItem`(自动 JSON 序列化)。 | `plugin-api.html` |
| 无界面插件 | plugin.json **不写 `main`** 即为无界面插件(宿主加载内部 `hideWindow.html`,preload 照常运行);按 feature 生效的 `mode:'none'` 声明在 preload 的 `window.exports[featureCode] = { mode:'none', args:{ enter(action){} } }`,宿主触发 feature 时回调 `enter`。任务书中「preload.js 中 mode:'none'」的说法位置不准确,机制相同。 | `pluginManager.ts:250,1673-1682`;`resources/preload.js:1860-1944` |
| 本地开发流程 | ① `npm i -g @ztools-center/plugin-cli` → `ztools create <name>`(模板:Vue3+TS+Vite / React+TS+Vite / Preload Only);② `npm run dev` 起 Vite dev server,`plugin.json` 的 `development.main` 指向 dev URL → **UI 有 HMR**;③ ZTools 设置 → 插件 → 「**导入本地插件**」选择项目(源码确认存在 `importDevPlugin` 等完整管理接口)。 | first-plugin 文档;`PluginsSetting.vue:742`;`env.d.ts:100-119` |
| preload 热重载 | **未见插件文件监听/自动重载机制**(全局搜索无相关代码)。UI 改动走 Vite HMR;preload 改动需重新进入/重载插件。**待验证**:导入的本地插件对 preload 文件修改后重新进入是否即生效。 | 源码检索结论 |

补充(与本设计相关):插件窗口 ESC 退出、`onPluginOut(isKill)`、插件可 `detach` 到独立窗口;
发布需 `ztools publish` 走 GitHub PR(本期本地使用,不发布)。

---

## 三、与任务书假设不符之处

1. **minSdk 是 21**,README 的「SDK 34 / Java 21」指 compileSdk 与构建 JDK。minSdk 21 意味着:
   `onListenerDisconnected`(API 24+)、`requestRebind`(静态方法,API 21+)、`POST_NOTIFICATIONS`
   (API 33)等都要按版本分支;`androidx.media` 不引入,继续用 framework `android.media.session.*`(API 21+)。
2. **「智能 payload 缓存」非常简单**(per-app 整串等值),且 MQTT payload 不含位置 → LAN 通道
   与它天然正交,**不需要**对现有代码做任何「隔离改造」,新通道独立封装即可。
3. 现有 state 枚举是 `idle` 而非 `stopped/none`;LAN 协议按任务书用新枚举,**MQTT 字段一个不动**。
4. ZTools:`plugin.json` 顶层没有 `mode` 字段;无界面插件的判定是「不写 main」+ preload exports 里的
   per-feature mode(见上表)。文档站没有「开发者模式」专页,实际入口是设置页的「导入本地插件」。
5. ZTools 存储不是 uTools 的 `dbStorage.setItem` 单一 API——文档型 `ztools.db` 与键值
   `ztools.dbStorage` 并存;封面用 attachment 或 dataURL 存 dbStorage 均可,取后者(简单)。
6. 歌词源实测(本机直连):**LRCLIB `/api/get`、`/api/search` 可用**(中英文歌曲均返回);
   **网易云公开接口**(music.163.com `/api/search/get` + `/api/song/lyric`)可用且返回带时间轴 LRC。
   仍按「随时可能失效」设计:独立 provider、失败静默回退、负缓存。
7. `paho.android.service:1.1.1` 声明未用且与高 targetSdk 不兼容,但**当前构建可通过** → 按任务书
   「不顺手重构」原则,不动它(仅在风险表记录)。

---

## 四、实施计划(5 阶段,每阶段独立 commit、独立可验证)

### 阶段 0:准备(已完成大半)
- [x] 通读 android_relay 源码;ZTools 源码级核实;歌词源实测。
- [x] 确认 `./gradlew assembleDebug` 基线通过(JDK 21,产出 `android-relay.apk`)。
- [ ] `ztools create ztools-plugin`(Vue3+TS+Vite 模板)生成骨架,跑通「导入本地插件」→ 空页面显示(验证开发环境)。

### 阶段 1:Android 媒体信息扩展(纯采集 + 日志,不动 MQTT 行为)
新增文件(`com.saihgupr.androidrelay` 包内平级新增,不改现有类对外行为):
- `MediaInfo.kt`:纯 Kotlin 数据类(app/track/playback 三段 + `estimatedPositionMs(elapsedRealtimeNow)` 纯函数,clamp 到 `[0,duration]`,`positionKnown` 标记)。
- `MediaMetadataReader.kt`:从 `MediaController` 读 title/artist/album/albumArtist/duration/genre/year/trackNumber/mediaId + 封面(`ALBUM_ART`→`ART`→`ALBUM_ART_URI`→`ART_URI`→`DISPLAY_ICON` 依次尝试);`PlaybackState` 读 state/position/lastPositionUpdateTime/speed/actions;state 枚举映射 LAN 协议值(`playing/paused/buffering/stopped/none`)。
- `SessionSelector.kt`:多会话选择(PLAYING 优先 → 最近活跃 `getLastActiveTime` 降序 → 包名稳定排序;黑/白名单过滤,读自 prefs)。
修改:
- `MediaSessionListenerService`:`reportState()` 内先 build `MediaInfo`,MQTT 发布路径**保持原 payload 与原去重不变**;`MediaInfo` 全量 Log.d;补 `onSessionDestroyed` 清理;API 24+ 补 `onListenerDisconnected → requestRebind`;增加可选的 app 黑名单过滤(默认空,行为不变)。
- 单元测试(纯 JVM,不引 Robolectric):位置估算(speed>0/paused/UNKNOWN(-1)/越界 clamp)、SessionSelector 排序与过滤。
验证:真机播放网易云/QQ/Spotify,logcat 观察;`assembleDebug` 通过。
Commit: `feat(android): extend media info collection (position/album/artwork metadata) with logging only`

### 阶段 2:协议 v1 + mock-server + Android LAN 推送
- `docs/protocol.md`:按任务书消息格式,补充:插值公式(`currentPos = positionMs + (nowPc - anchorPc) * speed`,锚点默认取 PC 收到时刻、可选 ping/pong 时钟偏差校正)、心跳 8s、去重规则(仅作用于「不含位置变化的重复状态事件」;seek 判定 = 与上次发送的估算位置差 > 2s 或 state 变化)、版本协商(只识别 `v:1`,未知 type/字段忽略)、2MB 消息上限、Token 不落日志。
- `tools/mock-server.js`:Node + `ws`,token 校验、打印消息、可回发 `request`。
- `LanPushClient.kt`:OkHttp `WebSocket`(新增依赖 `com.squareup.okhttp3:okhttp:4.12.0`),指数退避 1s→30s,`ConnectivityManager` 网络恢复触发立即重试,状态回调供设置页展示。
- `LanPushManager.kt`:组装/发送 hello、state(reason 四类)、artwork(仅切歌,长边 ≤300px JPEG q80 Base64 + hash;收到 `request` 重发)、ping/pong;**独立于 MQTT 缓存的去重器**;seq 单调递增;心跳定时器仅在 playing 且已连接时运转。
- 设置 UI:`activity_main.xml` 新增 "LAN Push" 卡片(样式复用 MQTT 卡):启用开关、PC 地址、端口(默认 18765)、Token(默认随机生成 24 位,可重置)、「测试连接」、状态行、「发送封面」开关、App 黑名单输入(逗号分隔包名)。
- **明文 ws 取舍**(任务书要求"尽量限定范围"):`networkSecurityConfig` 是静态 XML,无法按用户动态输入的 IP 限定域名白名单;采用 `base-config cleartextTrafficPermitted="true"` 的全局放开方案 + 文档声明风险,理由:本应用除 `ws://局域网` 外无任何 HTTP 流量,MQTT 走原生 socket 不受该策略影响,行为面最小。若不接受,备选方案是改用 `org.java-websocket:Java-WebSocket`(自实现握手,绕过 HTTP 栈检查),代价是多一个依赖、代码更绕。**默认按前者实施,如需改请指出。**
验证:`node tools/mock-server.js` + 真机;断网重连自动恢复;Token 改错被拒。
Commit: `feat(android): add LAN push channel (WebSocket) with protocol v1 + mock server`

### 阶段 3:插件通信服务 + mock-phone + 基础 UI
- `ztools-plugin/`(CLI 生成后精简):
  - `plugin.json`:`name: "lan-lyrics"`,`main: "dist/index.html"`,`preload: "preload.js"`,`pluginSetting.backgroundRunning: true`,features 一个(`code: "lyrics"`,cmds:`["歌词","lyrics"]`);`development.main` 指向 Vite dev URL。
  - `preload.js`(CommonJS 源码,同级 `package.json` type:commonjs + `npm i ws`):启动/停止 `ws` 服务(0.0.0.0:18765)、Token 校验(错则 error+close)、单消息 2MB 上限、多设备连接但单一活跃设备(有 playing 的优先,其次最新 state;UI 可切换,本期简单实现)、20s 无消息判断线、ping/pong 统计 RTT 与时钟偏差;通过 `window.__lanLyrics` 暴露状态与订阅接口给 UI(共享 window,无需 IPC);服务随插件 preload 启动,ZTools 重启后依赖「跟随主程序同时启动运行」(设置引导文案)。
  - UI(Phase 3 版):连接状态、本机局域网 IP/端口/Token 展示 + 一键复制配置串(二维码二期)、歌曲卡片(封面/标题/歌手/专辑)、进度条(插值驱动)、来源 App、延迟;深浅色跟随 `ztools.isDarkColors()`。
- `tools/mock-phone.js`:模拟 hello/state(切歌/播放/暂停/seek)/心跳/artwork/ping-pong,可脚本化场景。
- 单元测试:插值函数(纯 TS)。
验证:mock-phone 全场景;真机实连;ZTools 重启 + 自动启动开关后服务在线。
Commit: `feat(plugin): WebSocket relay service + base UI (track card & interpolator)`

### 阶段 4:歌词获取与展示
- `src/lyric/providers/`:`LyricProvider` 接口 + `lrclib.ts`(主源,get→search 回退)+ `netease.ts`(中文源,独立失败隔离,注明非官方风险);统一 5s 超时、切歌取消(AsyncController);候选挑选(清洗后标题/歌手相似度 + 时长 ±3s 优先)。
- `src/lyric/clean-title.ts`:去 `(Live)`/`feat.`/`- Remastered`/全半角括号附注等。
- `src/lyric/cache.ts`:键 `norm(title)|norm(artist)|duration±1s桶`;命中直接用;**负缓存 24h**(存 provider + 时间戳);实现用 `ztools.dbStorage`(同步 API,适合 preload)。
- `src/lyric/parse-lrc.ts`:多时间戳、`[offset:]`、毫秒 2-3 位;增强型逐字时间戳本期只解析不渲染(标注)。
- UI 渲染:`requestAnimationFrame` 以插值位置驱动当前行高亮 + 平滑滚动(网络到达频率无关);paused 停住、buffering 保持、seek 立即跳行、切歌淡出旧/淡入新;无歌词降级(封面+进度条),纯文本歌词静态滚动;手动校准 ±100/±500ms(按 trackId 与按来源 App 双维度记忆,取精确者优先);状态栏(连接/来源 App/歌词源/延迟)。
- 单元测试:LRC 解析、标题清洗、候选匹配、插值。
验证:真机网易云/QQ/Spotify 各一首;四条验收场景(暂停/seek/切歌/切 App ≤1s)。
Commit: `feat(plugin): lyric providers (LRCLIB + NetEase), LRC parser & synced scrolling UI`

### 阶段 5:收尾(健壮性、文档、测试)
- Android:README 补电池优化白名单与小米/华为/OPPO 自启动设置;NotificationListener 重绑策略说明(`requestRebind` 时机);Android 13+ 通知说明(本应用不加前台服务,通知由系统管理,不申请 `POST_NOTIFICATIONS`,README 解释)。
- `README.md` 重写为中文(安装、权限、配对步骤、保活、FAQ、协议链接、插件使用);原英文 README 归档到 `docs/README-upstream.md` 保留上游内容。
- `CHANGELOG.md`:全部改动文件、已知限制(厂商杀后台、非官方接口风险、时钟依赖、cleartext 说明)、后续建议(悬浮歌词/控制消息/mDNS/逐字歌词)。
- 测试全量跑通;mock 演示脚本写入 README。
Commit: `docs+test: zh README, changelog, keep-alive guide, unit tests`

---

## 五、风险点

| # | 风险 | 处置 |
|---|---|---|
| 1 | OkHttp `ws://` 被 cleartext 策略拦截;networkSecurityConfig 无法按动态 IP 限定 | 采用全局 cleartext 放开 + 文档警示(见 4.2 取舍);不接受则换 Java-WebSocket |
| 2 | 厂商 ROM 杀 NotificationListener(小米/华为/OPPO) | README 保活指南;`onListenerDisconnected → requestRebind`;不承诺 100% 存活 |
| 3 | 网易云/QQ 非官方接口失效或风控(限频/需特定 UA) | 独立 provider、LRCLIB 主源、负缓存、失败静默;文档标注合规风险 |
| 4 | 元数据质量参差(空 artist、"歌名 - 歌手"拼接、position 不更新、speed=0) | 清洗 + 匹配打分;`positionKnown=false` 时 UI 显示进度未知,歌词按暂停处理不乱跳 |
| 5 | 手机/PC 时钟偏差导致 sentAt 不可信 | 锚点默认用 PC 收到时刻(局域网单程 <5ms);ping/pong 偏差校正作为增强,协议两者都支持 |
| 6 | ZTools 插件服务不启动(用户未触发插件/未开自启动) | UI 首页放引导(开启「跟随主程序同时启动运行」);文档说明;**待验证**:ZTools 重启后已导入 dev 插件的自启动行为 |
| 7 | preload 无热重载,调 Server 逻辑效率低 | Server 逻辑与 UI 解耦;用 mock-phone 在 Vite dev 下先调 UI;preload 层薄 |
| 8 | paho.android.service 依赖与 targetSdk 34 兼容性(未使用) | 不动;若未来构建失败再移除(记录在 CHANGELOG) |
| 9 | LRCLIB 限流(公益服务) | 负缓存 + 命中缓存优先 + 单源 5s 超时;不并发轰炸 |
| 10 | 多会话并发(手机+平板同时连) | 单活跃设备策略;非活跃连接保留待切换 |

## 六、待你确认的点

1. 阶段 2 的 **cleartext 方案**(全局 networkSecurityConfig 放开 vs 换 Java-WebSocket 绕过)——默认前者。
2. `README.md` 中文化方式——默认改写为中文、上游英文归档到 `docs/`。
3. 插件名/目录:`ztools-plugin/`(目录,任务书指定)+ 插件 `name: "lan-lyrics"`。
4. 其余取舍均按「最简单可回退」执行,不阻塞。
