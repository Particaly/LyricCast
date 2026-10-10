# LAN Push 协议 v1(Android 手机 ⇆ PC 插件)

状态:**v1,已实现**(Android 端 `LanPushManager` / 插件端 `preload/ws-server`)。

传输层:WebSocket 文本帧,单帧 JSON,UTF-8。手机为**客户端**,PC 插件为**服务端**,
URL 固定路径 `ws://<PC_IP>:<port>/relay`(默认端口 `18765`)。

设计目标:手机**不做每秒推送**,只发位置锚点(切歌 / 状态变化 / seek / 心跳快照),
由 PC 端以本地时钟插值出实时进度;切歌、暂停、拖动进度、切 App 的端到端时延 ≤ 1 秒。

---

## 1. 通用约定

- 所有消息顶层带 `"v": 1`。收到不认识的 `type` 或多余字段时**必须忽略**,保持向前兼容。
- 版本协商:手机在 `hello` 中带上自己实现的 `v`。服务端只支持 `v: 1`;若 `hello.v > 1`,
  回 `error {code:"unsupported_version"}` 并关闭连接;`hello_ack.v` 表示协商结果(当前恒为 1)。
- 时间戳字段(`sentAt` / `t` / `serverTime`)均为 **epoch 毫秒**,但分别是**发送方本地时钟**
  的读数;两侧时钟可能不同步,换算见 §5。
- 服务端限制单条消息 ≤ **2 MB**,超限直接断开(防滥用)。
- `token` 是 ≥ 16 位随机字符串;**任何一端的日志都不得打印 token**。
- 连接不区分设备,但**同一时刻 PC 只展示一个活跃设备**(选择规则见插件 README)。

## 2. 消息定义

### 2.1 手机 → PC:握手(连接后第一条消息)

```jsonc
{ "type": "hello", "v": 1, "token": "xxxx", "deviceId": "...", "deviceName": "Pixel 8", "appVersion": "1.1.0" }
```

### 2.2 PC → 手机:握手确认

```jsonc
{ "type": "hello_ack", "v": 1, "serverTime": 1730000000000 }
```

token 错误时不回 hello_ack,而是回 `error {code:"bad_token"}` 并关闭连接;
token 校验通过前收到的任何其他消息一律回 `error {code:"bad_message"}` 并关闭连接。

### 2.3 手机 → PC:状态快照(切歌 / 状态变化 / seek / 心跳)

```jsonc
{
  "type": "state", "v": 1, "seq": 123,
  "sentAt": 1730000000000,          // 手机发送时刻(手机时钟 epoch 毫秒)
  "reason": "track_change",         // track_change | playback_change | seek | heartbeat
  "app": { "package": "com.netease.cloudmusic", "name": "网易云音乐" },
  "playback": {
    "state": "playing",             // playing | paused | buffering | stopped | none
    "positionMs": 83000,            // 已换算为 sentAt 时刻的估算位置;未知为 -1
    "positionKnown": true,          // false 表示系统报告 PLAYBACK_POSITION_UNKNOWN,不得伪造
    "speed": 1.0
  },
  "track": {
    "id": "sha1(title|artist|album|duration)",
    "title": "晴天", "artist": "周杰伦", "album": "叶惠美", "albumArtist": "周杰伦",
    "durationMs": 269000, "genre": "", "year": 2003, "trackNumber": 3, "mediaId": ""
  },
  "artwork": { "available": true, "hash": "<sha1(raw bytes)>" }
}
```

发送时机(手机端,见 §4):

| reason | 触发条件 |
|---|---|
| `track_change` | track.id 变化,立即发送 |
| `playback_change` | playback.state 变化(播放/暂停/缓冲/停止/无会话),立即发送 |
| `seek` | 估算位置与"上次快照的外推位置"偏差 > 2 秒(拖动进度),立即发送 |
| `heartbeat` | playing 且距上次任意快照 ≥ 8 秒,校正插值漂移 |

去重规则:**只有"不含位置变化的重复事件"才会被吞掉**(例如部分 App 高频重发同一
metadata/state)。seek 与心跳永远不会被去重。手机无活跃会话时,会以 `state:"none"`
发送最后一条曲目的快照(便于 PC 展示"已停止"),之后不再发送。

### 2.4 手机 → PC:封面(独立消息,仅切歌时)

```jsonc
{ "type": "artwork", "v": 1, "trackId": "<track.id>", "mime": "image/jpeg", "dataBase64": "..." }
```

- 仅在 `track_change` 时发送一次(受手机端"发送封面"开关控制);
- 位图统一转码:最长边 ≤ 300px,JPEG 质量 80;
- `hash` = 原始(编码前)字节的 sha1,与 `state.artwork.hash` 对应;封面在后台线程准备,
  所以紧随切歌的 `state` 快照中 `hash` 可能为空串,以 `artwork` 消息实际到达为准
  (PC 端按 `trackId` 关联,不要依赖 `state.artwork.hash` 的时序);
- PC 端丢包/后启可随时发 `request` 重取;手机端仅在字节可用时重发,否则不响应(无 error)。

### 2.5 PC → 手机:请求重发

```jsonc
{ "type": "request", "v": 1, "what": "artwork" }   // 重发当前曲目封面
{ "type": "request", "v": 1, "what": "state" }     // 立即重发完整快照(reason=heartbeat)
```

### 2.6 双向:时延与时钟测量

```jsonc
// 发起方 → 对方:t 为发起方本地时钟
{ "type": "ping", "v": 1, "t": 1730000000000 }
// 应答方 → 发起方:t 原样回传,serverTime 为应答方当时本地时钟
{ "type": "pong", "v": 1, "t": 1730000000000, "serverTime": 1730000000010 }
```

- 收到 `ping` **必须**尽快回 `pong`(两侧都实现);
- 实现内置节奏:PC 每 10 秒 ping 一次手机(测 RTT + 时钟偏差);手机每 15 秒 ping 一次
  (保活 + 自测 RTT);这属于传输层心跳,与 `state`(业务心跳)无关。

### 2.7 错误

```jsonc
{ "type": "error", "v": 1, "code": "bad_token", "message": "invalid token" }
```

v1 错误码:`bad_token`、`bad_message`、`unsupported_version`。收到 `error` 后手机端
视为不可用连接:bad_token 停止重试(等用户改配置),其余按断线退避重连。

## 3. 预留:PC → 手机控制消息(v1 不实现)

```jsonc
{ "type": "control", "v": 1, "action": "play | pause | next | previous" }
```

v1 手机端收到 `control` 一律忽略(向前兼容);实现在后续版本引入。

## 4. 手机端发送策略小结

1. 事件驱动(切歌/状态/seek)+ 8 秒心跳,**绝不每秒推送**;
2. seek 判定:`|当前估算位置 − 上次快照按速度外推的位置| > 2000ms`;
3. 位置锚点:`positionMs` 一律换算为 `sentAt` 时刻的估算位置
   (`estimate = position + (elapsedRealtime − lastPositionUpdateTime) × speed`,夹到
   `[0, duration]`);`PLAYBACK_POSITION_UNKNOWN` 时 `positionMs=-1`、`positionKnown=false`;
4. 无连接时空转退避重连(1s 起、×2、封顶 30s),网络恢复事件触发立即重试。

## 5. PC 端插值与时钟校正

设最近一次 `state` 快照为 S:

```
anchorPos  = S.playback.positionMs        // S.sentAt(手机钟)时刻的位置
anchorTime = S 的接收时刻换算到 PC 时钟(见下)
currentPos = anchorPos + (nowPc − anchorTime) × S.playback.speed
```

- **时钟校正(推荐,自动)**:PC 周期性 ping 手机,得到 RTT 与偏差
  `offset = serverTime − (t_echo + rtt/2)`(手机钟相对 PC 钟的偏移),
  则 `anchorTime ≈ S.sentAt − offset`。
- **降级(无 ping 数据时)**:`anchorTime = 收到 S 的 PC 本地时刻`
  (局域网单程时延通常 < 5ms,误差可忽略)。
- `positionKnown=false` 时不插值,UI 显示"进度未知",歌词按暂停处理。
- `playback.state != playing` 时冻结在 anchorPos;seek/新快照到达立即覆盖锚点。

## 6. 安全说明

- 本协议为**明文 WebSocket**,且 PC 端监听 `0.0.0.0`。仅限**可信局域网**使用;
  不要把端口暴露到公网(路由器端口映射、公网 WiFi 均不安全)。
- token 防止局域网内无关设备读取播放信息,但不加密传输内容;
  需要更强保证时应在两端之间加 VPN/隧道,本协议不引入 TLS(手机端自签名证书体验差,二期再议)。
- 服务端须:校验 token 后才接受业务消息、限制单帧 2MB、失败若干次握手后可临时拉黑(插件端 v1 先不做,标记为增强项)。
