# LAN Push 协议 v1.1(Android 手机 ⇆ PC 插件)

状态:**v1.1,已实现**(Android 端 `LanPushManager` / 插件端 `preload/lan-server-core.js`)。

> v1.1 变更(向后兼容,v1 两端混用时按 §8 降级表工作):
> 1. 新增手机→PC 消息 `snapshot`(连接建立后的全量状态,§2.8);
> 2. `seq` 语义收紧为"每次连接内从 1 开始单调递增"(§2.9);
> 3. 存活判定拆为**链路层(ws ping/pong 帧)与应用层(业务心跳)**两层,并明确各自生效条件(§6);
> 4. 明确同一 deviceId 重复连接策略:新连接顶掉旧连接(§7);
> 5. 明确乱序/过期消息丢弃规则(§2.9)。
> v1 的 `state` / `artwork` / `request` / `ping` / `pong` / `error` 消息原样保留,字段无删改。

传输层:WebSocket 文本帧,单帧 JSON,UTF-8。手机为**客户端**,PC 插件为**服务端**,
URL 固定路径 `ws://<PC_IP>:<port>/relay`(默认端口 `18765`)。

设计目标:手机**不做每秒推送**,只发位置锚点(切歌 / 状态变化 / seek / 心跳快照),
由 PC 端以本地时钟插值出实时进度;切歌、暂停、拖动进度、切 App 的端到端时延 ≤ 1 秒。
播放期间连接尽量不断;偶发断线时 PC 显示降级不清空,重连后以 `snapshot` 自动校正。

---

## 1. 通用约定

- 所有消息顶层带 `"v": 1`。收到不认识的 `type` 或多余字段时**必须忽略**,保持向前兼容。
- 版本协商:手机在 `hello` 中带上自己实现的 `v`。服务端只支持 `v: 1`(v1.1 仍走 `v: 1`,
  新能力全部以"新增消息/新增字段"表达);若 `hello.v > 1`,
  回 `error {code:"unsupported_version"}` 并关闭连接;`hello_ack.v` 表示协商结果(当前恒为 1)。
- 时间戳字段(`sentAt` / `t` / `serverTime`)均为 **epoch 毫秒**,但分别是**发送方本地时钟**
  的读数;两侧时钟可能不同步,换算见 §5。**注意 `sentAt` 有意不用
  `SystemClock.elapsedRealtime`**:epoch 值配合 §5 的时钟偏差估计可以直接换算到 PC 时钟
  作为插值锚点,elapsedRealtime 对 PC 是无意义数字,换算后反而丢精度。
- 服务端限制单条消息 ≤ **2 MB**,超限直接断开(防滥用)。
- `token` 是 ≥ 16 位随机字符串;**任何一端的日志都不得打印 token**。
- 连接不区分设备,PC 端按 `deviceId` 维护多设备,**同一时刻只展示一个活跃设备**
  (选择规则见插件 README);同一 `deviceId` 的重复连接处理见 §7。

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
    "positionMs": 83000,            // 发送瞬间按插值算出的估算位置;未知为 -1
    "positionKnown": true,          // false 表示系统报告 PLAYBACK_POSITION_UNKNOWN,不得伪造
    "speed": 1.0                    // 播放速率(协议语义里的 playbackSpeed)
  },
  "track": {
    "id": "sha1(title|artist|album|duration)",
    "title": "晴天", "artist": "周杰伦", "album": "叶惠美", "albumArtist": "周杰伦",
    "durationMs": 269000, "genre": "", "year": 2003, "trackNumber": 3, "mediaId": ""
  },
  "artwork": { "available": true, "hash": "<sha1(raw bytes)>" }
}
```

进度三要素的统一语义(**字段名沿用 v1 的 `positionMs` / `speed` / `state`**,两端已实现,
改名即破坏兼容;文档层面即本协议对 position / playbackSpeed / state 的正式定义):

- `playback.positionMs`:手机端在**发送瞬间**按插值算出的当前位置(毫秒),
  即 `position + (elapsedRealtime − positionUpdateTime) × speed` 夹到 `[0, duration]`;
- `playback.speed`:浮点,默认 1.0,负值表示倒退,0 表示不动;
- `playback.state`:`playing | paused | buffering | stopped | none`(沿用 v1 枚举;
  `none` 专指"手机无活跃媒体会话",`stopped` 指会话存在但已停止)。

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
- **过期丢弃规则(v1.1 明确写入)**:封面结果回到主线程时若已切歌
  (`trackId != lastSentTrackId`),直接丢弃,不发送;
- PC 端丢包/后启可随时发 `request` 重取;手机端仅在字节可用时重发,否则不响应(无 error)。

### 2.5 PC → 手机:请求重发

```jsonc
{ "type": "request", "v": 1, "what": "artwork" }   // 重发当前曲目封面
{ "type": "request", "v": 1, "what": "state" }     // 立即重发完整快照
```

### 2.6 双向:应用层时延与时钟测量

```jsonc
// 发起方 → 对方:t 为发起方本地时钟
{ "type": "ping", "v": 1, "t": 1730000000000 }
// 应答方 → 发起方:t 原样回传,serverTime 为应答方当时本地时钟
{ "type": "pong", "v": 1, "t": 1730000000000, "serverTime": 1730000000010 }
```

- 收到 `ping` **必须**尽快回 `pong`(两侧都实现);
- 这是**应用层**消息,仅用于测 RTT、估计时钟偏差(§5)和 NAT 保活,**不用于存活判定**
  (存活判定见 §6)。内置节奏:PC 每 10 秒 ping 一次;手机每 15 秒 ping 一次。

### 2.7 错误

```jsonc
{ "type": "error", "v": 1, "code": "bad_token", "message": "invalid token" }
```

v1 错误码:`bad_token`、`bad_message`、`unsupported_version`。收到 `error` 后手机端
视为不可用连接:bad_token 停止重试(等用户改配置),其余按断线退避重连。

### 2.8 手机 → PC:全量快照(v1.1 新增)

```jsonc
{
  "type": "snapshot", "v": 1, "seq": 1,
  "sentAt": 1730000000000,
  "reason": "reconnect",            // reconnect | request | resync(预留)
  "app": { "package": "...", "name": "..." },
  "playback": { "state": "...", "positionMs": 0, "positionKnown": true, "speed": 1.0 },
  "track": { "...": "与 state.track 完全同构" },
  "artwork": { "available": true, "hash": "..." }
}
```

- **发送时机**:WebSocket `onOpen` 并通过 hello 校验后立即发送一次;此后 PC 发来的
  `request {what:"state"}` 也以 `snapshot` 应答(reason=`request`)。
- **载荷与 `state` 同构**:当前选中会话的完整元数据 + §2.3 的进度字段 + 播放状态。
  无活跃会话时发送 `playback.state="none"` 的空快照(track 携带最后一首的元数据,
  便于 PC 展示"无播放"),与 v1 的 `sendNoneIfNeeded` 语义一致。
- **PC 收到后全量覆盖本地状态**(歌曲、元数据、封面关联、进度锚点、播放状态),
  并以该消息的 `seq` 作为本连接的乱序判定基准(§2.9)。
- 封面不随 snapshot 携带本体,沿用 §2.4 的按需逻辑(PC 需要时发 `request`)。
- **兼容性**:v1 PC 端收到未知 type `snapshot` 会忽略(不崩溃),退化为等待下一条
  `state`/心跳;两端同仓库同步升级,实际不出现该组合(§8)。

### 2.9 seq 与乱序/过期消息(v1.1 收紧)

- `seq` 为**每次连接内从 1 开始**单调递增的整数(手机端 onConnected 时归零);
  `snapshot` 重置基准。断线重连后从 1 重新计数,PC 不得跨连接比较 seq。
- **PC 端规则**:同一连接内,携带 `seq` 的消息(`state`/`snapshot`)seq 小于该连接已处理的
  最大 seq 时直接丢弃(理论上 TCP 保证单连接内有序,此规则防御未来的多路复用/重放);
  `snapshot` 无条件接受并重置基准。`artwork` 消息**不携带 seq**(沿 v1 字段),
  按 `trackId` 关联,不参与乱序判定。旧版手机不发 seq 时,PC 跳过乱序判断(§8)。

## 3. 预留:PC → 手机控制消息(v1 不实现)

```jsonc
{ "type": "control", "v": 1, "action": "play | pause | next | previous" }
```

v1 手机端收到 `control` 一律忽略(向前兼容);实现在后续版本引入。

## 4. 手机端发送策略小结

1. 事件驱动(切歌/状态/seek)+ 8 秒心跳,**绝不每秒推送**;断线期间事件**不排队累积**,
   只保留"最新状态",重连后以 `snapshot` 发出;
2. seek 判定:`|当前估算位置 − 上次快照按速度外推的位置| > 2000ms`;
3. 位置锚点:`positionMs` 一律换算为 `sentAt` 时刻的估算位置
   (`estimate = position + (elapsedRealtime − lastPositionUpdateTime) × speed`,夹到
   `[0, duration]`);`PLAYBACK_POSITION_UNKNOWN` 时 `positionMs=-1`、`positionKnown=false`;
4. 无连接时退避重连(1s 起、×2、封顶 30s),网络恢复事件与**断连期间新到的媒体事件**
   都触发立即重连;`snapshot` 在每次连接建立后立即发出。

## 5. PC 端插值与时钟校正

设最近一次 `state`/`snapshot` 快照为 S,PC 在收到时记下:

```
basePosition = S.playback.positionMs     // S.sentAt(手机钟)时刻的位置
baseRecvAt   = 收到 S 的 PC 本地时钟(epoch ms)
speed        = S.playback.speed
state        = S.playback.state
```

**进度推算公式(唯一公式,两端文档化)**:

```
当前进度 = basePosition + (now − baseRecvAt) × speed      仅 state=playing 时推进
```

- `state != playing`(paused/stopped/buffering/none)时冻结在 `basePosition`;
- 新快照到达立即覆盖基准点;`positionKnown=false` 时不插值,UI 显示"进度未知",
  歌词按暂停处理;
- UI 按定时器/rAF 周期调用该公式,不依赖数据持续到达;页面不可见时降低刷新频率。

**延迟补偿(可选,v1.1 现状:EMA 时钟偏差,保留)**:
PC 维护 `clockOffset`(手机钟 − PC 钟)的估计,把锚点换算为
`baseRecvAt ≈ S.sentAt − clockOffset`,可修正消息在传输/调度中被延迟的情况。
现状实现为 ping/pong 采样值的 EMA(α=0.3);备选方案为滑动窗口最小值
`clockOffset = min(recvAt − sentAt)`(对单向延迟更稳健但需窗口管理)。
实现代价过高时可降级为直接用接收时刻(`baseRecvAt = recvAt`),
局域网单程时延通常 < 5ms,误差可忽略——**取舍:本协议允许三种实现并存,行为兼容**。

## 6. 存活判定(两层,职责分离)

### 6.1 链路层(ws 协议帧,与应用消息无关)

- 手机端:OkHttp `pingInterval` = **10 秒**(自动发 ws PING 帧,`ws` 服务端自动回 PONG;
  两个周期无 PONG OkHttp 自己判定连接死亡并走 onFailure 重连);
- PC 端:服务端每 **10 秒**向每个设备发 ws PING 帧,**距上次 PONG ≥ 45 秒**才
  `terminate()`(硬断,不等 close 握手),清理设备并更新 UI 状态。v1.1 的
  "连续 2 个周期(20s)"计数制会误杀息屏时因 WiFi 省电缓冲而 PONG 迟到的健康连接
  (v1.4 起改时间制,实测息屏唤醒的包延迟可达数十秒);
- **服务端主动关闭必须携带状态码与原因**(OkHttp 收到无状态码 close 帧会报
  `closed(1005)`,无法排障):hello 超时 `1000/"hello timeout"`、协议错误 `1003`、
  token/版本不符 `1008`、新连接顶替 `1000/"superseded"`、停服 `1001/"server shutdown"`;
  hello 超时窗口为 **10 秒**(亮屏瞬间进程可能被 ROM 短暂冻结,握手先完成、
  hello 略晚,过短窗口会把它误杀);
- 本层在任何播放状态下都生效(paused/stopped 也保活),仅依赖 TCP/时钟,不依赖
  应用层 Handler 心跳。**v1 的"20 秒无任何应用消息即断开"watchdog 在 v1.1 废除**,
  由本层取代——这是 v1 息屏断连的主因之一(见 §9 已知问题)。

### 6.2 应用层(业务心跳,仅 playing 生效)

- 仅当 `playback.state = "playing"` 时,PC 若超过 **3 × 心跳间隔 = 24 秒**没有收到
  该设备任何**应用层消息**(state/snapshot/artwork/ping/pong 任一),把连接标为
  **stale**:降级显示(§6.3),**不关闭连接、不清空状态**;
- paused / stopped / buffering / none 时不做应用层超时判定(手机端本就不发心跳);
- 收到任意新消息后立即恢复 connected。

### 6.3 PC 端连接状态机(展示语义)

```
connected   ──playing 超 24s 无应用消息──▶ stale
stale       ──收到任意应用消息───────────▶ connected
connected/stale ──链路层断开(ws close/terminate)──▶ disconnected
disconnected ──新连接(同 deviceId)建立+snapshot──▶ connected
```

- stale:进度**继续按公式推算**,UI 轻微提示(如"数据延迟");
- disconnected:进入 **30 秒宽限期**,期间进度继续推算(设备可能只是网络抖动);
  超过宽限期则冻结进度、显示"已断开",**保留最后一首歌的信息但标灰**,不清空;
- 任何状态下收到新消息或 `snapshot` 后立即恢复 connected 并全量覆盖(§2.8)。

## 7. 重复连接策略(同一 deviceId)

同一 `deviceId` 的新连接完成 hello 校验时,服务端**立即关闭旧连接**(旧连接可能是
半开状态,直接 `terminate`),以新连接为准;后续以新连接的 `snapshot` 覆盖状态。
旧连接在 close 时不得误删新连接的设备记录。不同 `deviceId`(多台手机)互不影响。

## 8. 兼容性降级表

| 组合 | 行为 |
|---|---|
| 新手机 → 新 PC | 全功能:snapshot 校正、seq 乱序丢弃、两层存活判定 |
| 旧手机(v1)→ 新 PC | snapshot 缺失:PC 沿用 v1 逻辑(首个 state 全量覆盖);无 seq:跳过乱序判断;缺新字段按缺省值(speed=1.0、positionKnown=false 等),不报错 |
| 新手机 → 旧 PC(v1) | snapshot 被旧 PC 忽略(不崩溃),旧 PC 等下一条 state/心跳;seq 在旧 PC 无语义;两层存活判定退化为 v1 的 20s watchdog |
| 未知 type / 未知字段(任意方向) | 必须忽略,不得崩溃(v1 起的约定) |

## 9. 已知边界(非协议缺陷,环境决定)

- 手机 Doze / CPU 深睡期间**所有**线程(含 OkHttp)冻结,链路层 ping 也会停。
  v1.2 起手机端对策升级:LAN 启用期间**全程**持有 PARTIAL_WAKE_LOCK + WifiLock
  (不再仅 playing,由连接持有方 LanPushManager 管理,前台服务缺席也不影响),
  前提是应用已加入电池优化白名单——未加白名单时 Doze 仍会切断网络,任何锁都无效;
  常驻锁的代价是待机耗电略增(应用层权衡,非协议约束)。
  WifiLock 模式注意(AOSP 文档约束):`WIFI_MODE_FULL_LOW_LATENCY` **仅前台+亮屏
  时生效**;息屏/后台的射频保活必须用 `WIFI_MODE_FULL_HIGH_PERF`。v1.4 起两把
  同时持有(此前 API 29+ 只持 LOW_LATENCY,等效于息屏无 WifiLock,是"锁屏/切
  后台必断"的直接原因);亮屏/解锁广播会触发客户端立即重连,兜底 ROM 息屏期间
  静默断链且网络回调不触发的场景。
- PC 睡眠 / Wi-Fi 省电 / ZTools 退出或"终止插件"都会断连;ZTools 重启后需
  "跟随主程序启动"或手动进入插件,服务才恢复(插件 README)。
- 本协议为**明文 WebSocket**,仅限可信局域网(同 v1 §6,原样保留见 git 历史/下方安全节)。

## 10. 安全说明(同 v1)

- 本协议为**明文 WebSocket**,且 PC 端监听 `0.0.0.0`。仅限**可信局域网**使用;
  不要把端口暴露到公网(路由器端口映射、公网 WiFi 均不安全)。
- token 防止局域网内无关设备读取播放信息,但不加密传输内容;
  需要更强保证时应在两端之间加 VPN/隧道,本协议不引入 TLS(手机端自签名证书体验差,二期再议)。
- 服务端须:校验 token 后才接受业务消息、限制单帧 2MB、失败若干次握手后可临时拉黑(增强项)。

## 11. 配对二维码(扫码接入,同 v1,未变更)

配对不走 WebSocket,而是插件端生成二维码、手机端相机扫码。**payload 格式**
(插件端构建 `src/qr/pair-qr.ts`,手机端解析 `PairingQr.parse`):

```
androidrelay://pair?v=1&port=18765&ip=192.168.1.5&ip=192.168.1.8&token=xxxx
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | 否 | payload 版本,缺省视为 `1`;大于 1 拒绝解析 |
| `port` | 否 | 服务端口,缺省 `18765`;合法范围 1–65535 |
| `ip` | 是 | 可重复,本机全部局域网 IPv4(含虚拟网卡);解析时校验格式与 0–255 段并去重,**手机按出现顺序逐个尝试连接** |
| `token` | 是 | 配对 Token(≥16 位 `[A-Za-z0-9_-]`),与 WebSocket 握手用同一个 token |

约定:

- scheme 固定 `androidrelay://`、host 固定 `pair`,大小写不敏感;
- 解析**从严**:scheme/host/版本/端口/IPv4/token 任一不合法即整体拒绝(扫码页提示
  "不是配对二维码",不误填配置);
- payload 是普通 URI,任何通用二维码工具都可生成/识别,便于调试;
- 二维码内容含 Token:插件 UI 在二维码旁提示"请勿截图外传";
- 手机端实现:扫码页 `QrScanActivity`(CameraX 预览 + YUV 亮度矩阵 + ZXing 仅 QR 解码),
  解析成功回填设置并保存重启服务;相机权限被拒或设备无摄像头时提示退回手动填写。
