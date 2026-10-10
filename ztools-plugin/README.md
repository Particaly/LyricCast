# LAN 歌词(ZTools 插件)

> 手机正在播放 → 局域网推送 → ZTools 插件实时滚动歌词。
> 配套 Android 端:[android_relay(LAN 歌词版)](../README.md)。

由 `@ztools-center/plugin-cli`(`ztools create`,Vue3 + TypeScript + Vite 模板)生成骨架后二次开发。

## 功能

- 局域网 WebSocket 服务端(协议 v1,见 [docs/protocol.md](../docs/protocol.md)):token 配对、
  多设备接入(单活跃设备)、20s 超时、ping/pong 测延迟与时钟偏差;
- 实时歌曲卡片:封面、标题、歌手、专辑、来源 App、插值进度条(rAF 驱动,不依赖推送频率);
- 滚动歌词:LRCLIB 主源 + 网易云回退源,LRC 逐行高亮平滑滚动,翻译条,纯文本/无歌词降级;
- 手动校准:±100/±500ms,按"本曲"或"来源 App"记忆;字号调节持久化。

## 快速开始

```bash
# 1. 构建 UI(产物在 src-ztools/dist)
npm install
npm run build

# 2. 安装 preload 服务端依赖(ws,必须;随插件分发)
cd src-ztools/preload && npm install && cd ../..

# 3. 单元测试(39 个:LRC 解析/清洗/匹配/缓存/偏移/服务端插值)
npm test
```

然后在 **ZTools → 设置 → 插件 → 导入本地插件**,选择 **`src-ztools/`** 目录。

> 首次进入插件会自动生成随机 Token 并启动服务(端口 18765)。
> 建议在插件详情里开启 **「跟随主程序同时启动运行」**,ZTools 一启动服务即可用。

### 无手机演示

```bash
cd ../tools && npm install
node mock-phone.js --token <插件首页显示的Token>            # 完整时间线
node mock-phone.js --token <Token> --speedup 6             # 6 倍速
```

## 开发模式

```bash
npm run dev        # Vite dev server(http://localhost:5173)
```

导入的本地插件会读取 `plugin.json` 的 `development.main` 指向 dev 地址,UI 改动热重载。
**注意**:脱离 ZTools 直接用浏览器打开 dev 页面时,`window.services` 不存在,页面不可用;
请配合 ZTools 使用,或用 `tools/mock-phone.js` 造数据。

改 `src-ztools/preload/` 下的代码后需重新进入插件(无 preload 热重载)。

## 目录结构

```
ztools-plugin/
├── src/                         # Vue3 + TS 源码(构建进 src-ztools/dist)
│   ├── App.vue                  #   连接信息 / 设备 / 正在播放卡片
│   ├── components/Lyrics.vue    #   滚动歌词 + 校准 + 字号
│   ├── store.ts                 #   事件聚合 + 歌词生命周期
│   └── lyric/
│       ├── providers/           #   lrclib.ts(主源) netease.ts(中文回退,非官方)
│       ├── parse-lrc.ts         #   LRC 解析(多时间戳/offset/增强标签剥离)
│       ├── clean-title.ts       #   标题/歌手清洗
│       ├── match.ts             #   候选挑选(Dice 相似度 + 时长权重)
│       ├── cache.ts             #   缓存 + 24h 负缓存
│       ├── fetcher.ts           #   超时 + 切歌取消
│       ├── offsets.ts           #   手动偏移(本曲/此应用)
│       └── lyrics-manager.ts    #   编排:缓存 → 回退链 → 写缓存
└── src-ztools/                  # 导入 ZTools 的插件根
    ├── plugin.json              #   features + pluginSetting.backgroundRunning
    ├── dist/                    #   构建产物(gitignore,build 生成)
    └── preload/
        ├── package.json         #   type: commonjs + ws 依赖
        ├── lan-server-core.js   #   协议服务端核心(纯 Node,可独立测试)
        └── server.js            #   window.services 适配层
```

## 工作机制要点

- **后台常驻**:插件视图隐藏后 ZTools 只移除不销毁;`plugin.json` 的
  `pluginSetting.backgroundRunning: true` 关闭后台节流,WebSocket 服务持续在线;
  用户「终止插件」或退出 ZTools 才会停止(重启后依赖自启动开关或手动进入);
- **preload 与 UI 通信**:共享 window 上下文(`contextIsolation: false`),
  preload 把 `window.services` 挂给 UI 直接调用,无需 IPC;
- **npm 依赖**:preload 遵循 CommonJS,`ws` 安装在 `preload/` 目录内随插件分发;
  按 ZTools 发布要求 preload 源码保持可读(不打包/不混淆);
- **歌词获取在 PC 侧**:Node 无 CORS 限制;查询键 `规范标题|规范歌手|时长(秒)`,
  未命中结果负缓存 24h;单源 5 秒超时,切歌取消在途请求。

## 打包

发布到插件市场:`git init && git commit` 后在项目根运行 `ztools publish`(走 GitHub PR)。
本地使用无需打包——直接导入 `src-ztools/` 即可。

## 已知限制

- 网易云 provider 为非官方接口,可能失效(失败自动回退 LRCLIB,不影响主流程);
- ZTools 重启后,未开「跟随主程序同时启动运行」时需要手动进入一次插件;
- 二期计划:桌面悬浮歌词(独立置顶窗口)、mDNS 自动发现、二维码配对、逐字(KTV)歌词。
