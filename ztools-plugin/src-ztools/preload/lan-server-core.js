'use strict'
/**
 * lan-server-core.js — LAN 歌词插件的 WebSocket 服务端核心(docs/protocol.md v1)。
 *
 * 纯 Node 模块,不依赖 window / Electron,可在普通 Node 进程中单元测试;
 * preload/server.js 是它接入 ZTools 的薄适配层。
 *
 * 职责:
 * - 监听 0.0.0.0:<port>,校验 hello.token(错误回 error 并断开,不泄漏任何数据);
 * - 多设备接入、按"playing 优先 → 最近快照优先"选活跃设备(UI 可手动切换);
 * - 20 秒无消息判超时断开;每 10 秒向设备 ping,测 RTT 与时钟偏差(EMA);
 * - getPosition():按协议 §5 用"sentAt − 时钟偏差"为锚点插值出当前进度;
 * - 单帧消息上限 2MB(ws maxPayload);日志不打印 token。
 */
const { WebSocketServer } = require('ws')

const PROTOCOL_V = 1
const HELLO_TIMEOUT_MS = 5_000
const DEVICE_IDLE_TIMEOUT_MS = 20_000
const PC_PING_INTERVAL_MS = 10_000
const WATCHDOG_INTERVAL_MS = 5_000
const OFFSET_EMA_ALPHA = 0.3

class LanServerCore {
  /**
   * @param {object} options
   * @param {number} options.port 监听端口(默认 18765)
   * @param {string} options.token 配对 Token(≥16 位,校验失败直接拒绝)
   * @param {object} [options.logger] 注入的日志对象(默认 console;测试可传静默实现)
   */
  constructor(options) {
    this.port = options.port || 18765
    this.token = options.token || ''
    this.logger = options.logger || console
    this.onEvent = null // (event) => void,由适配层/UI 订阅

    /** @type {import('ws').WebSocketServer|null} */
    this.wss = null
    /** @type {Map<string, object>} deviceId → 设备状态 */
    this.devices = new Map()
    this.activeDeviceId = null
    this._timers = []
  }

  /** 启动监听;端口占用等错误通过 rejected Promise 返回。 */
  start() {
    if (this.wss) return Promise.resolve()
    return new Promise((resolve, reject) => {
      let settled = false
      const wss = new WebSocketServer({
        port: this.port,
        host: '0.0.0.0',
        path: '/relay',
        maxPayload: 2 * 1024 * 1024
      })
      wss.on('listening', () => {
        settled = true
        this.logger.log(`[lan-server] listening on 0.0.0.0:${this.port}/relay`)
        this._startTimers()
        resolve()
      })
      wss.on('error', (err) => {
        if (!settled) reject(err)
        else this.logger.error(`[lan-server] server error: ${err.message}`)
        this._emit({ kind: 'server-error', message: err.message })
      })
      wss.on('connection', (ws, req) => this._onConnection(ws, req))
      this.wss = wss
    })
  }

  /** 停止监听并断开全部设备。 */
  stop() {
    this._timers.forEach(clearInterval)
    this._timers = []
    if (this.wss) {
      for (const device of this.devices.values()) this._close(device.ws)
      const wss = this.wss
      this.wss = null
      try {
        wss.close()
      } catch (_) { /* 已关闭时忽略 */ }
    }
    this.devices.clear()
    this.activeDeviceId = null
    this._emit({ kind: 'snapshot' })
  }

  // ==================== 连接生命周期 ====================

  _onConnection(ws, req) {
    const peer = `${req.socket.remoteAddress}:${req.socket.remotePort}`
    this.logger.log(`[lan-server] connection from ${peer}`)

    /** @type {object} */
    const device = {
      ws,
      peer,
      deviceId: null,
      deviceName: '',
      appVersion: '',
      authed: false,
      lastState: null, // 最近一次协议 state 消息
      lastStateAt: 0,
      lastMessageAt: Date.now(),
      rttMs: -1,
      clockOffsetMs: 0, // 手机钟 − PC 钟 的 EMA 估计
      artwork: new Map() // trackId → {mime, dataBase64}
    }

    // 5 秒内必须完成 hello,防呆滞连接占位
    const helloTimer = setTimeout(() => {
      if (!device.authed) {
        this.logger.log(`[lan-server] ${peer} hello timeout, closing`)
        this._close(ws)
      }
    }, HELLO_TIMEOUT_MS)
    if (helloTimer.unref) helloTimer.unref()

    ws.on('message', (raw) => {
      device.lastMessageAt = Date.now()
      let msg
      try {
        msg = JSON.parse(raw.toString('utf8'))
      } catch (_) {
        this._send(ws, { type: 'error', v: PROTOCOL_V, code: 'bad_message', message: 'invalid JSON' })
        this._close(ws)
        return
      }
      if (!device.authed) {
        clearTimeout(helloTimer)
        this._handleHello(device, msg)
        return
      }
      this._handleMessage(device, msg)
    })

    ws.on('close', () => {
      clearTimeout(helloTimer)
      if (device.deviceId && this.devices.get(device.deviceId) === device) {
        this.devices.delete(device.deviceId)
        this.logger.log(`[lan-server] ${device.deviceName || peer} disconnected`)
        if (this.activeDeviceId === device.deviceId) this.activeDeviceId = this._autoSelect()
        this._emit({ kind: 'snapshot' })
      }
    })
    ws.on('error', () => { /* close 事件会跟进 */ })
  }

  /** 握手:第一条消息必须是 hello;token/版本不对直接拒绝。 */
  _handleHello(device, msg) {
    if (msg.type !== 'hello') {
      this._send(device.ws, { type: 'error', v: PROTOCOL_V, code: 'bad_message', message: 'expected hello first' })
      this._close(device.ws)
      return
    }
    if (typeof msg.token !== 'string' || msg.token !== this.token) {
      this.logger.log(`[lan-server] ${device.peer} rejected: bad_token`)
      this._send(device.ws, { type: 'error', v: PROTOCOL_V, code: 'bad_token', message: 'invalid token' })
      this._close(device.ws)
      return
    }
    if (Number(msg.v) > PROTOCOL_V) {
      this._send(device.ws, { type: 'error', v: PROTOCOL_V, code: 'unsupported_version', message: 'please upgrade the PC plugin' })
      this._close(device.ws)
      return
    }
    device.authed = true
    device.deviceId = String(msg.deviceId || device.peer)
    device.deviceName = String(msg.deviceName || device.deviceId)
    device.appVersion = String(msg.appVersion || '')

    // 同一设备重连:顶掉旧连接,保留最新
    const existing = this.devices.get(device.deviceId)
    if (existing && existing.ws !== device.ws) {
      existing.deviceId = null // 防止 close 时误删新记录
      this._close(existing.ws)
    }
    this.devices.set(device.deviceId, device)
    if (!this.activeDeviceId || !this.devices.has(this.activeDeviceId)) {
      this.activeDeviceId = this._autoSelect()
    }
    this.logger.log(`[lan-server] device connected: ${device.deviceName} (${device.deviceId})`)
    this._send(device.ws, { type: 'hello_ack', v: PROTOCOL_V, serverTime: Date.now() })
    this._emit({ kind: 'snapshot' })
  }

  /** 已认证设备的业务消息路由。 */
  _handleMessage(device, msg) {
    switch (msg.type) {
      case 'state':
        device.lastState = msg
        device.lastStateAt = Date.now()
        // 新设备首发快照后,若当前无活跃设备则接管
        if (!this.activeDeviceId || !this.devices.has(this.activeDeviceId)) {
          this.activeDeviceId = this._autoSelect()
        }
        this._emit({ kind: 'state', deviceId: device.deviceId, state: msg })
        this._emit({ kind: 'snapshot' })
        break
      case 'artwork':
        if (msg.trackId && typeof msg.dataBase64 === 'string') {
          device.artwork.set(msg.trackId, { mime: String(msg.mime || 'image/jpeg'), dataBase64: msg.dataBase64 })
          // 只保留当前与上一首的封面,防内存膨胀
          if (device.artwork.size > 4) {
            const oldest = device.artwork.keys().next().value
            device.artwork.delete(oldest)
          }
          this._emit({ kind: 'artwork', deviceId: device.deviceId, trackId: msg.trackId, artwork: device.artwork.get(msg.trackId) })
        }
        break
      case 'ping':
        this._send(device.ws, { type: 'pong', v: PROTOCOL_V, t: msg.t, serverTime: Date.now() })
        break
      case 'pong': {
        const now = Date.now()
        const rtt = now - Number(msg.t)
        if (rtt >= 0 && rtt < 5_000) {
          device.rttMs = rtt
          // serverTime 产生于约 tSend + rtt/2(PC 钟)时刻 ⇒ 偏差 = serverTime − (t + rtt/2)
          const sample = Number(msg.serverTime) - (Number(msg.t) + rtt / 2)
          device.clockOffsetMs =
            device.rttMs === -1 ? sample : device.clockOffsetMs * (1 - OFFSET_EMA_ALPHA) + sample * OFFSET_EMA_ALPHA
        }
        break
      }
      default:
        this.logger.log(`[lan-server] unknown type "${msg.type}" from ${device.deviceId} (ignored)`)
    }
  }

  // ==================== 对 UI 的查询 ====================

  /** 设备列表与活跃设备的只读快照,供 UI 渲染。 */
  getSnapshot() {
    const devices = [...this.devices.values()].map((d) => ({
      deviceId: d.deviceId,
      deviceName: d.deviceName,
      appVersion: d.appVersion,
      rttMs: d.rttMs,
      clockOffsetMs: Math.round(d.clockOffsetMs),
      connectedAt: d.lastStateAt,
      hasState: !!d.lastState
    }))
    return {
      listening: !!this.wss,
      port: this.port,
      devices,
      activeDeviceId: this.activeDeviceId
    }
  }

  /** UI 手动切换活跃设备。 */
  setActiveDevice(deviceId) {
    if (!deviceId || this.devices.has(deviceId)) {
      this.activeDeviceId = deviceId
      this._emit({ kind: 'snapshot' })
    }
  }

  /** UI 请求设备重发封面/快照(发往活跃设备)。 */
  sendToActive(message) {
    const device = this.devices.get(this.activeDeviceId)
    if (device) this._send(device.ws, message)
  }

  /**
   * 插值当前播放进度(协议 §5)。
   * @param {string} [deviceId] 缺省用活跃设备
   * @param {number} [nowMs] 当前 PC 时钟毫秒;默认 Date.now(),测试可注入固定值
   * @returns {{positionMs:number, known:boolean, state:string, speed:number,
   *            durationMs:number, trackId:string, title:string, artist:string}|null}
   */
  getPosition(deviceId, nowMs) {
    const device = this.devices.get(deviceId || this.activeDeviceId)
    const st = device && device.lastState
    if (!st || !st.playback || !st.track) return null
    const now = Number(nowMs) || Date.now()
    const known = st.playback.positionKnown === true && Number(st.playback.positionMs) >= 0
    const durationMs = Number(st.track.durationMs) || 0
    let positionMs = Number(st.playback.positionMs)
    if (known) {
      const anchorPc = Number(st.sentAt) - device.clockOffsetMs // 手机钟 → PC 钟
      if (st.playback.state === 'playing') {
        positionMs += (now - anchorPc) * Number(st.playback.speed || 0)
      }
      positionMs = Math.max(0, positionMs)
      if (durationMs > 0) positionMs = Math.min(positionMs, durationMs)
      positionMs = Math.round(positionMs)
    }
    return {
      positionMs: known ? positionMs : -1,
      known,
      state: String(st.playback.state || 'none'),
      speed: Number(st.playback.speed) || 0,
      durationMs,
      trackId: String(st.track.id || ''),
      title: String(st.track.title || ''),
      artist: String(st.track.artist || '')
    }
  }

  /** 活跃设备的指定曲目封面(或最近一帧)。 */
  getArtwork(trackId) {
    const device = this.devices.get(this.activeDeviceId)
    if (!device) return null
    if (trackId && device.artwork.has(trackId)) return device.artwork.get(trackId)
    const last = [...device.artwork.values()].pop()
    return last || null
  }

  // ==================== 内部 ====================

  /** 活跃设备选择:playing 优先,其次最近快照(协议 §1)。 */
  _autoSelect() {
    let best = null
    for (const device of this.devices.values()) {
      if (!best) { best = device; continue }
      const bestPlaying = best.lastState && best.lastState.playback && best.lastState.playback.state === 'playing'
      const playing = device.lastState && device.lastState.playback && device.lastState.playback.state === 'playing'
      if (playing && !bestPlaying) { best = device; continue }
      if (playing === bestPlaying && device.lastStateAt > best.lastStateAt) best = device
    }
    return best ? best.deviceId : null
  }

  _startTimers() {
    this._timers.push(setInterval(() => this._watchdog(), WATCHDOG_INTERVAL_MS))
    this._timers.push(setInterval(() => this._pingAll(), PC_PING_INTERVAL_MS))
  }

  /** 20 秒无任何消息视为断线(协议 §1),主动断开等手机重连。 */
  _watchdog() {
    const now = Date.now()
    for (const device of [...this.devices.values()]) {
      if (now - device.lastMessageAt > DEVICE_IDLE_TIMEOUT_MS) {
        this.logger.log(`[lan-server] idle timeout: ${device.deviceName}`)
        this._close(device.ws)
      }
    }
  }

  _pingAll() {
    for (const device of this.devices.values()) {
      this._send(device.ws, { type: 'ping', v: PROTOCOL_V, t: Date.now() })
    }
  }

  _send(ws, obj) {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj))
  }

  _close(ws) {
    try { ws.close() } catch (_) { try { ws.terminate() } catch (_) {} }
  }

  _emit(event) {
    if (this.onEvent) {
      try { this.onEvent(event) } catch (err) { this.logger.error(`[lan-server] onEvent handler failed: ${err.message}`) }
    }
  }
}

module.exports = { LanServerCore, PROTOCOL_V }
