# 歌词匹配与健壮性改进(2026-10-11)

本文是本轮"歌词插件匹配准确度与健壮性"专项的总变更说明。五个阶段各自独立提交,
全部插件测试(170 个)与 Android 编译均通过。

- 阶段 1 匹配核心:`91c2776`
- 阶段 2 流程与工程:`b2c334d`
- 阶段 3 手动纠错:`6ac0182`
- 阶段 4 酷狗第三源:`2c66967`
- 阶段 5 平台 ID 调研:`559e889`

---

## 阶段 1:匹配核心(match.ts / clean-title.ts / normalize.ts)

**改了什么**

- 新增 `normalize.ts`:深度规范化(NFKC、繁→简小映射表(约 300 字)、`&`↔`and`、
  `Pt.`/`Part`、独立 token 罗马数字↔阿拉伯数字、片假名长音符/中点删除、去标点留
  CJK+字母数字),供打分与缓存键共用;
- `clean-title.ts`:清洗时同步提取版本标签(`extractVersionTags`,词表集中在
  `VERSION_KEYWORDS`,只扫括号附注/破折号后缀区域);`parseArtists` 返回完整歌手列表
  (feat/、///&/×/x 拆分,AC/DC、Simon & Garfunkel 等固定名白名单保护);
  `cleanTitle`/`cleanArtist` 原行为不变;
- `match.ts`:标题相似度改为三路加权(LCS-Dice 0.5 + bigram Jaccard 0.3 + 归一化编辑
  距离 0.2,权重常量);短标题保护(规范化后 ≤3 CJK / ≤5 拉丁必须全等或包含且长度比
  <2);版本标签对称惩罚(重惩罚 1.2:live/remix/dj/伴奏/翻唱/不插电/demo/速度变体;
  remaster 轻惩罚 0.4;重惩罚失配 + 时长差 >15s 直接排除);歌手集合重叠
  (交集占比 ×2,合作者全命中 +0.5,零交集且时长差远也排除;跨语言降权 0.5 仅在
  "标题几乎全等 + 时长一致 + 歌手零交集 + 文字体系不同"时生效);专辑加分(全等 +1、
  近似 +0.5,不匹配不扣);歌词内容反校验(候选带歌词时:尾时间戳超时长 +10s 扣 2、
  语言比例失衡扣 2、序奖励 有时间轴 0.6 > 行数 0~0.3 > 翻译 0.2 > 逐字 0.1);
  新增 `pickBestWithScore` 供置信度与缓存元数据使用;查询侧新增 `allArtists`
  (完整歌手列表,打分用)与 `versionTags`;
- `query-variants.ts`(新):查询变体纯函数(清洗后 title+artist → 仅 title → 原始
  标题 → 去全部括号),去重封顶 4;
- providers:LRCLIB `/api/search` 先分字段再 `q`,总请求封顶 4;网易云把歌曲
  `alias`/`tns` 并入标题别名、歌手 alias 并入歌手别名。

**为什么**:标题词相似但版本不同的候选(Live/Remix/翻唱)是串歌主因;短标题
("爱"匹配"爱你")与跨语言歌手名是两类典型误配;负缓存误写大多来自"源出错当没找到"。

**如何验证**:`match-cases.test.ts` 51 条样例表(Live 误配、Remix、短标题、简繁、
全半角、feat、中英歌手名、专辑不同同名歌、翻唱、纯音乐、Demo/Sped Up/重制、别名、
歌词反校验、序奖励、硬门槛),加 `clean-title.test.ts`/`match.test.ts`/`parse-lrc.test.ts`
扩展,共 71 个匹配相关用例。

## 阶段 2:流程与工程(lyrics-manager / cache / parse-lrc / 开关熔断路由)

**改了什么**

- `ProviderOutcome` 显式区分 `found` / `not_found` / `error`;切歌 abort 仍以
  AbortError 抛出单独识别;
- 负缓存只写"所有实际运行的来源都明确 not_found"(error/abort/全部被跳过都不写);
  来源开关(`isProviderEnabled`)跳过的不算结论;
- `cache.ts` v2:主键 `规范title|规范artist`(时长不进键),条目带 `durationMs`
  校验字段(差 >5s 视为不同版本不命中);条目带结构版本 `v:2`,旧格式/损坏数据平滑
  失效不抛错;条目携带 `meta{provider,score,userPinned}`;
- 并行时间预算(默认开,可关):各来源同时请求,首个"高置信"(score ≥ 8)有效时间轴
  立即返回并取消其余;否则首个结果后等 1.5s 收集窗口,统一按打分择优;总预算 5s;
  全部请求在 RequestSupervisor 之下,切歌即取消;
- `provider-router.ts`:查询以 CJK 为主时 `prefersCjk` 来源提前(纯函数);
- `circuit-breaker.ts`:连续 3 次 error → 打开 5 分钟(期间视为跳过),到期半开放行
  一次,探测失败立即重开;not_found 不计失败;
- `parse-lrc.ts`:开头区域的元数据行(作词/作曲/编曲/Lyrics by…,关键词+全半角冒号+
  ≤50 字符)在行数/跨度统计与渲染之前过滤;正式歌词出现后不再过滤(防误删)。

**验证**:`synced-preference.test.ts` 18 例(负缓存决策矩阵、abort 不写缓存、
串行回退、伪时间轴降级)、`circuit-breaker.test.ts`、`provider-router.test.ts`、
`cache.test.ts`(时长窗口/旧格式兼容/固定优先级)、`parse-lrc.test.ts` 元数据过滤
5 例;并行预算用假 provider + vi.useFakeTimers。

## 阶段 3:手动纠错与时间偏移

**改了什么**

- Provider 可选 `searchCandidates` / `fetchLyricsById`;LRCLIB 候选自带歌词内容,
  网易云候选带 id 按源回取;
- `LyricsManager.fetchCandidates`(路由序聚合、单源失败静默、纳入 RequestSupervisor)、
  `pinLyrics`(normalize 后 `putPinned`,优先于自动匹配、不受 24h 负缓存与自动重匹配
  影响)、`unpinLyrics` / `isPinned`;
- `store.ts`:`candidatePicker` 状态 + open/close/choose/unpin 动作(带序号防串歌);
- UI:「正在播放」卡片上"歌词不对?换一个"幽灵徽标按钮 → `CandidatePicker.vue`
  覆盖层(标题/歌手/专辑/时长/来源/时间轴/翻译徽标;点击立即切换并固定;底部
  "恢复自动匹配"清除固定);
- 时间偏移:沿用既有实现(`offsets.ts` 本曲/此应用两维度、±100/±500 步进、复位),
  `[offset:]` 标签在解析时应用、用户偏移在行定位计算处叠加,缓存原文不变;
  新增 `offset-combination.test.ts` 固化叠加数学。

**产品决策说明**:入口选了正在播放卡片上的徽标按钮 + 无歌词占位保持原有"未找到"
提示(最小、贴合现有 UI 风格);固定粒度是"歌曲"(缓存主键),不区分设备。

## 阶段 4:酷狗第三源

选择酷狗而非 QQ 音乐:酷狗的搜索/歌词接口无需签名参数(三次直连请求即可),
QQ 音乐需要 sign 计算且接口变化频繁,在无真实联调条件下风险过高。

- `providers/kugou.ts`:搜索(变体,命中即停)→ 复用 `pickBestWithScore` 打分 →
  krcs 按 hash 检索 → 下载 base64 LRC 解码;`prefersCjk` 中文路由;纳入开关与熔断;
  失败静默;候选面板/按 hash 回取齐备;
- 头部注释明确标注非官方接口、随时可能变动;
- 已知限制:该接口**没有独立翻译轨道**(translation 恒为 null)、没有逐字时间轴、
  候选无别名;
- 测试:以录制的样例响应做解析层单测(搜索/krcs/下载,含 URL-safe base64 与损坏数据
  容错)+ 打分集成(Live 让位原版)。**真实联调未做**——沙箱无网络,需要用户在
  ZTools 环境实测连通性与限流行为。

## 阶段 5:平台歌曲 ID 调研(只调研)

- 结论:**协议 v1.1 的 `state.track.mediaId` 已存在**且 Android 端已填充
  (`MediaMetadataReader.kt` 读 `METADATA_KEY_MEDIA_ID`,`SnapshotJson` 发送),
  PC 端尚未声明/消费;metadata/controller/playbackState 三处 extras 均未读取;
- 诊断工具:`MediaDiagnostics.kt`(设置页开关,默认关,仅本地 logcat `RelayDiag`,
  不推送、不解码位图、绝不打印 token),用于在各音乐 App 实测 mediaId 语义;
- 设计草案:`docs/platform-id-lyrics-design.md`(协议零改动;PC 端
  `LyricQuery.platformId` + `provider.fetchById` 路由设计与负缓存注意点)。

## 增量:新增聚合源与 TTML 源(2026-10-11 第二轮)

- **lrcmux**(`providers/lrcmux.ts`,公开实例 `api.lrcmux.dev`):聚合
  LRCLIB/Musixmatch/酷狗/Genius/Deezer 等,服务端扇出择优,LRCLIB 搜不到的曲目
  常能命中。请求 `GET /get?artist&title&album&duration&level=line&format=json`,
  自转 LRC(词级 `words` 转增强标签,信息保留);命中来源写入
  `source: "lrcmux:<providerId>"`。404/400(如无 artist)按未命中,429/5xx 按
  error(不写负缓存)。**无候选列表**(服务端聚合),不出现在"换一个"面板。
- **Better Lyrics**(`providers/betterlyrics.ts`,`lyrics-api.boidu.dev`):
  音节级 TTML(开源众包)。`GET /getLyrics?s=&a=&al=&d=` → `{ttml}`;
  `ttmlToLrc` 把 `<p>/<span>` 转为增强 LRC(行戳 `[mm:ss.xx]` + 词戳
  `<mm:ss.xx>`,parseLrc 剥离渲染并标记 isEnhanced,未来逐字渲染无需换源)。
  **公共实例限制**:未缓存的新歌要求 X-API-Key(401)——该源定位为热门歌的
  TTML 增强;401 不写负缓存(缓存可能随后变热),404 按确认无词。
- 两者均默认启用、可在设置页关闭、纳入熔断与 RequestSupervisor;解析层用
  2026-10-11 真实公共实例录制的样例做 fixture 单测(实现当日已对真实 API
  联调通过:命中/404/400 三条路径)。
- 逐字渲染仍未实现(行级 + 增强标签保留),与阶段 1 的 isEnhanced 语义一致。

## 增量:Better Lyrics 密钥导入(2026-10-11 第三轮)

- 设置页新增「Better Lyrics 密钥」导入:粘贴浏览器插件导出的密钥 JSON
  (`{version,keyId,publicKey,privateKey,…}`,自动提取 keyId)或直接的 API key
  字符串,存为 `betterLyricsApiKey`;请求时作为 `X-API-Key` 头发送(服务端为纯
  字符串比对)。无效 key 仅 401,按未命中静默跳过,不写负缓存。
- **重要事实(实测)**:浏览器插件导出的 JSON 是 Unison 众包签名身份(ECDSA
  密钥对),**不是** lyrics API 的 key —— keyId/privateKey.d 作为 X-API-Key 实测
  均 `401 invalid`;官方文档明确"API key 暂不向新用户发放"。该导入功能对"将来
  拿到官方 key"或自建部署(自行配置 API_KEY)有效。
- 官方 workaround(冷门歌拿不到词时):**用 Better Lyrics 浏览器扩展播放该曲
  一次**让响应进入服务端缓存,之后任何客户端都能免 key 读取;热歌基本都已在
  缓存内,无 key 也正常。

## 新增配置项(插件设置页「歌词显示」区块)

| 名称 | 默认 | 含义 |
|---|---|---|
| `providerLrclib` | 开 | LRCLIB 主源开关 |
| `providerNetease` | 开 | 网易云(中文回退,非官方接口)开关 |
| `providerKugou` | 开 | 酷狗(中文补充,非官方接口)开关 |
| `providerLrcmux` | 开 | lrcmux 聚合源开关(LRCLIB/Musixmatch/酷狗/Genius 等) |
| `providerBetterLyrics` | 开 | Better Lyrics(TTML 逐字,未缓存新歌需 API key)开关 |
| `betterLyricsApiKey` | 空 | Better Lyrics 的 X-API-Key;设置页「导入密钥」写入 |
| `parallelProviders` | 开 | 并行时间预算模式;关闭退化为按路由顺序串行 |

Android 端新增:`lan_diagnostic_log`(默认关)——媒体会话诊断日志开关。

## 已知限制与风险

1. **非官方接口**(网易云 /api/search/get、酷狗 mobiles/krcs/lyrics 三域名)随时可能
   变更或限流;全部失败静默、纳入熔断,不影响 LRCLIB 主流程;
2. 酷狗源无翻译轨道、无逐字歌词、无候选别名;
3. 沙箱环境无网络:LRCLIB/网易云/酷狗的**真实连通性与限流行为未实测**,建议按下表
   手动验证;
4. `npx tsc --noEmit` 对 `.vue` 文件的两条模块解析报错是**既有现象**(构建门禁
   `vue-tsc --noEmit` 通过),与本次改动无关;
5. 负缓存键升级导致旧缓存(lyric.v1.*)整体失效一次(一次性重新搜索,无功能影响)。

## 建议手动验证清单

- [ ] 中文歌(网易云 App)→ 酷狗/网易云优先命中,有翻译;
- [ ] 英文歌 → LRCLIB 优先,Live/Remix 候选不压过原版;
- [ ] 播放 Live/伴奏/翻唱版本,歌词命中同版本而非原版(对称惩罚);
- [ ] 冷门歌(搜不到)→ 24h 内不重复打网络;断网时反复拉取**不会**写负缓存;
- [ ] 同一首歌切歌连按:无旧歌词闪现(RequestSupervisor + fetchSeq);
- [ ] "歌词不对?换一个" → 面板出候选 → 选择立即生效;重启插件后仍固定;
- [ ] "恢复自动匹配" → 回到自动结果;
- [ ] 歌词行与进度对不齐时,设置页 ±100/±500 校准仍然生效;
- [ ] 设置页逐个关闭来源/并行开关,行为符合预期;连续失败的来源 5 分钟内不再请求;
- [ ] 酷狗源在真实环境连通(首个中文歌观察 `netease`/`kugou` 来源徽标);
- [ ] lrcmux:播放一首 LRCLIB 搜不到的歌,观察来源徽标出现 `lrcmux:*`;
- [ ] Better Lyrics:播放海外热歌,来源徽标 `betterlyrics`,切换展示正常;
- [ ] 关闭两个新源开关后不再请求(冷门歌观察负缓存行为);
- [ ] Android:开启「媒体会话诊断日志」,在网易云/QQ 音乐/酷狗各播一首歌,
      `adb logcat -s RelayDiag` 观察 mediaId 是否为平台歌曲 ID,反馈到 issue。
