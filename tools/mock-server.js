#!/usr/bin/env node
/**
 * mock-server.js — 模拟 PC 端 ZTools 插件的 WebSocket 服务端,用于调试 Android 端 LAN 推送。
 *
 * 用法:
 *   cd tools && npm install
 *   node mock-server.js [--port 18765] [--token my-token]
 *
 * 行为(与 docs/protocol.md v1 一致):
 *   - 等待第一条消息为 hello,校验 token;错误则回 error(bad_token) 并断开;
 *   - hello 后回 hello_ack(serverTime);
 *   - state/artwork/ping 等消息打印摘要(截断 base64、不打印 token);
 *   - 收到 ping 回 pong(t 原样 + serverTime);
 *   - 支持 --send-request 每 10 秒向手机发一次 request(state),测试重发路径。
 */

const argv = process.argv.slice(2)
function argOf(name, fallback) {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback
}

const PORT = parseInt(argOf('--port', '18765'), 10)
const TOKEN = argOf('--token', 'test-token-0123456789')
const SEND_REQUEST = argv.includes('--send-request')

let { WebSocketServer } = require('ws')
const crypto = require('node:crypto')

const wss = new WebSocketServer({ port: PORT, path: '/relay' })
console.log(`[mock-server] listening on ws://0.0.0.0:${PORT}/relay (token: ${'*'.repeat(TOKEN.length)})`)

const sessions = new Set()

/** 安全打印:截断 base64 大字段,不泄漏 token。 */
function summarize(msg) {
  const clone = { ...msg }
  if (clone.token) clone.token = '<redacted>'
  if (clone.dataBase64) clone.dataBase64 = `<base64 ${clone.dataBase64.length} chars>`
  if (clone.track && clone.track.title) {
    clone.track = { ...clone.track }
  }
  return JSON.stringify(clone)
}

wss.on('connection', (ws, req) => {
  const peer = `${req.socket.remoteAddress}:${req.socket.remotePort}`
  console.log(`[mock-server] connection from ${peer}`)
  let authenticated = false
  sessions.add(ws)

  ws.on('close', () => {
    console.log(`[mock-server] ${peer} disconnected`)
    sessions.delete(ws)
  })
  ws.on('error', (err) => console.log(`[mock-server] ${peer} error: ${err.message}`))

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      console.log(`[mock-server] ${peer} binary frame (${data.length} bytes), ignoring`)
      return
    }
    const text = data.toString('utf8')
    if (text.length > 2 * 1024 * 1024) {
      console.log(`[mock-server] ${peer} message exceeds 2MB, closing`)
      ws.close()
      return
    }
    let msg
    try {
      msg = JSON.parse(text)
    } catch {
      send(ws, { type: 'error', v: 1, code: 'bad_message', message: 'invalid JSON' })
      ws.close()
      return
    }

    if (!authenticated) {
      if (msg.type !== 'hello') {
        send(ws, { type: 'error', v: 1, code: 'bad_message', message: 'expected hello first' })
        ws.close()
        return
      }
      if (msg.token !== TOKEN) {
        console.log(`[mock-server] ${peer} rejected: bad_token`)
        send(ws, { type: 'error', v: 1, code: 'bad_token', message: 'invalid token' })
        ws.close()
        return
      }
      authenticated = true
      console.log(`[mock-server] ${peer} hello ok: device=${msg.deviceName} v=${msg.v} app=${msg.appVersion}`)
      send(ws, { type: 'hello_ack', v: 1, serverTime: Date.now() })
      return
    }

    switch (msg.type) {
      case 'ping':
        send(ws, { type: 'pong', v: 1, t: msg.t, serverTime: Date.now() })
        break
      case 'pong':
        console.log(`[mock-server] ${peer} pong t=${msg.t}`)
        break
      case 'state':
        console.log(`[mock-server] ${peer} state: ${summarize(msg)}`)
        break
      case 'artwork':
        console.log(`[mock-server] ${peer} artwork: ${summarize(msg)}`)
        break
      case 'hello':
        console.log(`[mock-server] ${peer} duplicate hello ignored`)
        break
      default:
        console.log(`[mock-server] ${peer} unknown type "${msg.type}" (ignored, forward compat)`)
    }
  })
})

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj))
}

if (SEND_REQUEST) {
  setInterval(() => {
    for (const ws of sessions) send(ws, { type: 'request', v: 1, what: 'state' })
    console.log('[mock-server] broadcast request(state)')
  }, 10000).unref()
}

process.on('SIGINT', () => process.exit(0))
