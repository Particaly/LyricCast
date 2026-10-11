# 平台歌曲 ID 接入设计草案(阶段 5,只调研不改协议)

状态:调研结论 + 诊断工具已落地;**本阶段不修改协议、不改 PC 端消费逻辑**。

## 1. 现状结论(代码为准)

手机端**已经在采集并推送 `mediaId`**:

| 位置 | 内容 |
|---|---|
| `MediaMetadataReader.kt:43` | 读取 `MediaMetadata.METADATA_KEY_MEDIA_ID` → `TrackInfo.mediaId` |
| `MediaInfo.kt` | `TrackInfo` 持有 `mediaId` 字段(与内容 hash 的 `stableId` 分离) |
| `SnapshotJson.kt` | `state.track.mediaId` 已在协议 v1.1 载荷中(JSON 字段 `"mediaId"`) |
| PC 端 `env.d.ts` | `ProtocolStateMessage.track` **尚未声明** `mediaId`(UI 侧被忽略) |

尚未采集的位置(诊断工具将逐一验证):
- `MediaMetadata.getExtras()`(Bundle):部分播放器会把平台 ID/歌单 ID 放这里;
- `MediaController.getExtras()` 与 `PlaybackState.getExtras()`:厂商定制固件偶有额外键;
- 通知栏内容(`onNotificationPosted`):当前 `NotificationListenerService` 仅用于获取
  MediaSession 权限,未解析通知文本。

各 App 的 `mediaId` 语义差异(待实测确认,诊断日志的目的):
- 网易云音乐:mediaSession 的 mediaId 通常是**歌曲 ID**(可直接调 `/api/song/lyric`);
- QQ 音乐:通常是 `mid` 或 `songid`(二选一,需看 extras 里是否有补充字段);
- 酷狗:通常是 `hash`(恰好可直接用于 krcs 歌词检索,省一次搜索);
- 本地播放器(如 VLC):可能是文件路径或随机 ID,**不可**当平台 ID 用。

## 2. 诊断工具(已实现,默认关闭)

- 开关:设置页「连接」区块新增「媒体会话诊断日志」开关,默认关闭,
  存 `lan_config`(`LanSettings.KEY_DIAGNOSTIC_LOG`)。
- 实现:`MediaDiagnostics.logSession(controller)` 挂在 `reportState()` 尾部,仅在开关开启时:
  - 打印 `metadata.keySet()` 全部键值(`mediaId` 单列;位图类只标注 `<bitmap omitted>`);
  - 打印 `controller.extras` 与 `playbackState.extras` 的全部键值(类型+值,长文本截断 120 字符);
  - 全部走 `Log.d("RelayDiag", …)`,**不推送、不写入任何存储**。
- 用户实测方式:`adb logcat -s RelayDiag`,在各音乐 App 里切几首歌,把输出贴回 issue。

## 3. 协议设计(草案,本阶段不实施)

协议 v1.1 **无需任何消息格式变更**:`state.track.mediaId` 已是向后兼容的可选字段
(老版本 PC 端直接忽略;文档 §1 已声明"收到多余字段必须忽略")。要做的是补充语义约定:

```
track.mediaId:string
  - 来源 App 的 MediaSession 报告的 MEDIA_ID 原文,语义因 App 而异;
  - 可能为空串(部分 App 不填);
  - 消费方不得假定它一定是平台歌曲 ID,必须按 app.package 解释。
```

若 extras 里发现结构化的平台 ID,才考虑**新增**可选字段(如 `track.platformIds` 对象,
`{"netease": "186016"}`),同样遵循"忽略未知字段"兼容规则。

## 4. PC 端消费设计(草案)

### 4.1 查询入参扩展

`LyricQuery` 增加可选字段(向后兼容):

```ts
interface LyricQuery {
  ...
  /** 平台歌曲 ID(来自 state.track.mediaId,按 appPackage 解释);缺省 undefined */
  platformId?: string
  /** 来源 App 包名,platformId 的解释依据 */
  sourceApp?: string
}
```

### 4.2 Provider 新增 `fetchById`

```ts
interface LyricProvider {
  ...
  /**
   * 按平台歌曲 ID 直接取词(跳过搜索)。
   * 返回 not_found 表示"ID 有效性内确认无词",参与负缓存;error 不写负缓存。
   * 实现:网易云 fetchById(id) → /api/song/lyric;酷狗 fetchById(hash) → krcs+download。
   */
  fetchById?(id: string, query: LyricQuery, signal: AbortSignal): Promise<ProviderOutcome>
}
```

### 4.3 编排层路由(在缓存查询之后、搜索之前)

```
app.package ∈ {com.netease.cloudmusic, com.kugou.android, …}
  且 query.platformId 非空
  且 对应 provider 启用且实现 fetchById
→ 直接 fetchById,跳过搜索(省 1~2 个请求,彻底消除"同名歌误配")
→ found 走既有 normalize/缓存;not_found 回退到普通搜索(同一轮内完成)
```

缓存键保持 `规范title|规范artist`(阶段 2 的 v2 键),`fetchById` 命中照常写入;
负缓存仅对"搜索确认无词"写,`fetchById` 的 not_found **不写负缓存**
(平台 ID 失效可能是版本变化,搜索仍可能找到其他版本)。

### 4.4 风险与注意

- ID 语义按 `app.package` 解释,未知 App 一律不启用 fetchById(白名单推进);
- 诊断日志确认某个 App 的 mediaId 不可靠后,把该 App 移出白名单即可,协议无需变更;
- `fetchById` 与搜索一样纳入熔断与 RequestSupervisor。

## 5. 实施顺序建议(后续阶段)

1. 收集各 App 实测输出 → 确定 netease/QQ/酷狗三家的 ID 可靠性;
2. PC 端 `env.d.ts` 声明 `mediaId` + `LyricQuery.platformId` 透传;
3. 网易云 `fetchById`(收益最大:中文曲库搜索命中率最低的环节);
4. 酷狗 `fetchById(hash)`(省一次搜索请求)。
