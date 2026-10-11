# 词投 LyricCast(ZTools 插件)

> 手机正在播放 → 局域网推送 → ZTools 插件实时滚动歌词 + 桌面悬浮歌词。
> 配套 Android 端:[android_relay(词投 LyricCast 版)](../README.md)。

由 `@ztools-center/plugin-cli`(`ztools create`,Vue3 + TypeScript + Vite 模板)生成骨架后二次开发。

## 功能

- **「歌词」首页(网易云「正在播放」页风格)**:封面模糊氛围背景 + 毛玻璃正在播放条
  (封面/标题/歌手/来源 App/播放态)+ 全高**逐行滚动歌词** —— 当前行居中放大高亮,
  平滑跟随播放进度,翻译内嵌当前行下方;未配对时是旋转唱片空态引导;
- **「设置」页**:桌面歌词 / 歌词显示 / 歌词校准 / 连接 / 关于四个分区;
- **桌面悬浮歌词**:独立置顶透明窗口(网易云桌面歌词同思路)—— 常驻屏幕可拖动,
  歌词按统一行高轨道平滑滚动(与首页同款手感),保持 2 行视图:当前句大字 +
  翻译/下一句小字,行推进平滑上滑;对齐方式可调(左对齐/居中,默认左对齐),
  底部细进度条;悬停出控制条(A± 字号、翻译、对齐、配色、关闭),
  字号/配色/翻译/对齐在设置页可调,窗口位置自动记忆;
- **歌词匹配**:LRCLIB 主源 + 网易云回退源,跨来源**优先取带有效时间轴(Synced)的歌词**,
  伪时间轴空壳自动降级纯文本;缓存 + 24h 负缓存;
- **封面 API 兜底**(可选开关):手机未推送封面时,按曲名/歌手/专辑依次从 LrcAPI
  (`api.lrc.cx/cover`)、MusicBrainz + Cover Art Archive 拉取封面 URL;
  切歌后等 3s 宽限期防与手机推送重复,结果持久缓存 30 天(负缓存 24h);
- **繁体转简体**(可选开关):歌词判定为繁体时,经内置 OpenCC 字表(约 3000 对)**逐字直转**
  为简体显示,主歌词/翻译/纯文本全覆盖,日语歌词(含假名)自动跳过;
- **配对二维码**:设置 → 连接卡展示,含全部局域网 IP + 端口 + Token,
  手机 App「Scan QR to Connect」扫码即完成配对(docs/protocol.md §7);
- **多设备**:单活跃设备,多台手机时可在设置页切换;手动校准 ±100/±500ms
  按"本曲/此应用"记忆;主界面字号、翻译开关持久化。

## 快速开始

```bash
# 1. 构建 UI(产物在 src-ztools/dist,含主界面 index.html 与悬浮窗 desktop.html)
npm install
npm run build

# 2. 安装 preload 服务端依赖(ws,必须;随插件分发)
cd src-ztools/preload && npm install && cd ../..

# 3. 单元测试(LRC 解析/清洗/匹配/缓存/偏移/服务端插值/synced 优先策略/简繁转换/封面兜底)
npm test
```

然后在 **ZTools → 设置 → 插件 → 导入本地插件**,选择 **`src-ztools/`** 目录。

> 首次进入插件会自动生成随机 Token 并启动服务(端口 18765)。
> 建议在插件详情里开启 **「跟随主程序同时启动运行」**,ZTools 一启动服务即可用,
> 桌面歌词也会随插件常驻。

### 无手机演示

```bash
cd ../tools && npm install
node mock-phone.js --token <插件设置页显示的Token>          # 完整时间线
node mock-phone.js --token <Token> --speedup 6             # 6 倍速
```

## 开发模式

```bash
npm run dev:full   # dev server + dist watch build(推荐)
npm run dev        # 仅 Vite dev server(http://localhost:5173)
```

导入的本地插件会读取 `plugin.json` 的 `development.main` 指向 dev 地址,UI 改动热重载;
悬浮窗固定加载 `dist/desktop.html`(ZTools 的 `createBrowserWindow` 只接受相对路径/file://),
由 watch build 在改动后自动重建,无需手动 build。
**注意**:脱离 ZTools 直接用浏览器打开 dev 页面时,`window.services` 不存在,页面不可用;
请配合 ZTools 使用,或用 `tools/mock-phone.js` 造数据。

改 `src-ztools/preload/` 下的代码后需重新进入插件(无 preload 热重载)。

## 目录结构

```
ztools-plugin/
├── index.html / desktop.html    # 主界面与悬浮歌词两个入口(多页构建)
├── src/
│   ├── App.vue                  # 顶栏导航 + 歌词首页(氛围舞台/正在播放条/空态)
│   ├── components/
│   │   ├── Lyrics.vue           # 全高逐行滚动歌词(网易云风格,行内翻译)
│   │   ├── SettingsView.vue     # 设置页:桌面歌词/歌词显示/校准/连接/关于
│   │   └── DesktopLyrics.vue    # 悬浮歌词胶囊 UI(独立窗口页面)
│   ├── store.ts                 # 事件聚合 + 歌词生命周期
│   ├── settings.ts              # 用户设置(reactive + dbStorage 持久化)
│   ├── desktop-bridge.ts        # 悬浮窗生命周期/状态推送/按钮消息轮询/位置记忆
│   ├── desktop-state.ts         # 悬浮窗侧状态容器(与主窗口 store 隔离)
│   ├── qr/pair-qr.ts            # 配对二维码 payload 构建(protocol §7)
│   └── lyric/
│       ├── providers/           # lrclib.ts(主源) netease.ts(中文回退,非官方)
│       ├── lyrics-manager.ts    # 编排:缓存 → synced 优先两轮回退 → 写缓存
│       ├── cover.ts             # 封面 API 兜底(LrcAPI + MusicBrainz/CAA,Image 探测 + 缓存)
│       ├── t2s.ts               # 繁→简显示转换(整段繁体判定 + 字表直转)
│       ├── t2s-table.ts         # 繁→简单字映射表(提取自 OpenCC TSCharacters)
│       ├── parse-lrc.ts         # LRC 解析(多时间戳/offset/增强标签/伪时间轴判定)
│       ├── clean-title.ts       # 标题/歌手清洗
│       ├── match.ts             # 候选挑选(Dice 相似度 + 时长权重)
│       ├── cache.ts             # 缓存 + 24h 负缓存
│       ├── fetcher.ts           # 超时 + 切歌取消
│       └── offsets.ts           # 手动偏移(本曲/此应用)
└── src-ztools/                  # 导入 ZTools 的插件根
    ├── plugin.json              # features + pluginSetting.backgroundRunning
    ├── dist/                    # 构建产物(gitignore,build 生成)
    └── preload/
        ├── package.json         # type: commonjs + ws 依赖
        ├── lan-server-core.js   # 协议服务端核心(纯 Node,可独立测试)
        └── server.js            # window.services 适配层
```

## 桌面歌词实现要点

- **独立窗口**:`ztools.createBrowserWindow('dist/desktop.html', { transparent, alwaysOnTop,
  frame: false, focusable: false, skipTaskbar: true, ... })`,不加载 preload、不跑 WebSocket,
  是纯哑渲染器;固定加载 dist 产物,开发时靠 watch build 刷新;
- **状态推送**:主窗口 rAF tick 里计算当前句下标/进度锚点,经 `webContents.executeJavaScript`
  调用悬浮窗的 `window.__applyDesktopState`;ok 状态推送整首行文本 + 平行译文 +
  当前行下标 + linesVersion(歌词整体重建时自增),内容签名变化立即推,否则 2s 心跳;
  悬浮窗据此自建统一行高的滚动轨道,行推进平滑上滑;进度条按 speed 本地插值,不依赖推送频率;
- **按钮回传**:悬浮窗无向主窗口的推送通道,控制条动作写入 `window.__drainDesktopMessages`
  队列,主窗口每 800ms 轮询消费,统一落回 `settings` 持久化(关闭即联动销毁窗口);
- **拖动**:整个胶囊 `-webkit-app-region: drag`(控制条 no-drag),无 JS 参与;
  位置每 3s 采样持久化,重开时校验仍落在某块屏幕内才恢复,否则回默认位;
- **生命周期**:`onPluginOut` 在「转入后台」和「完全退出」时都会触发(processExit 区分),
  转后台时悬浮窗常驻(bridge 自持定时器,不依赖主窗口),仅完全退出时销毁。

## 工作机制要点

- **后台常驻**:插件视图隐藏后 ZTools 只移除不销毁;`plugin.json` 的
  `pluginSetting.backgroundRunning: true` 关闭后台节流,WebSocket 服务持续在线;
  用户「终止插件」或退出 ZTools 才会停止(重启后依赖自启动开关或手动进入);
- **preload 与 UI 通信**:共享 window 上下文(`contextIsolation: false`),
  preload 把 `window.services` 挂给 UI 直接调用,无需 IPC;
- **npm 依赖**:preload 遵循 CommonJS,`ws` 安装在 `preload/` 目录内随插件分发;
  按 ZTools 发布要求 preload 源码保持可读(不打包/不混淆);
- **歌词获取在 PC 侧**:Node 无 CORS 限制;查询键 `规范标题|规范歌手|时长(秒)`,
  未命中结果负缓存 24h;单源 5 秒超时,切歌取消在途请求;
- **繁→简为展示层转换**:缓存里始终存来源原文,开关切换仅按原始结果同步重绘
  (不重新请求来源);判定以整段"繁体特征字占比"为准,含假名视为日文一律跳过。

## 打包

发布到插件市场:`git init && git commit` 后在项目根运行 `ztools publish`(走 GitHub PR)。
本地使用无需打包——直接导入 `src-ztools/` 即可。

## 已知限制

- 网易云 provider 为非官方接口,可能失效(失败自动回退 LRCLIB,不影响主流程);
- 封面兜底的 LrcAPI 为公益接口(可能限流/变动),MusicBrainz 有速率约束;
  两处都拉不到封面时保持占位符,点击可重试;
- 繁→简为字表直转,不做词汇级地域用语转换(如「滑鼠/鼠标」),个别表外生僻字保留原形;
- 桌面歌词悬浮窗为不透明胶囊(圆角外区域不响应鼠标穿透),后续可按需加"鼠标穿透锁定";
- 悬浮窗在 `createBrowserWindow` 不可用的环境(如 ZTools 版本过旧)无法开启,设置页会提示错误;
- ZTools 重启后,未开「跟随主程序同时启动运行」时需要手动进入一次插件;
- 三期计划:逐字(KTV)歌词、mDNS 自动发现、悬浮窗锁定与鼠标穿透。
