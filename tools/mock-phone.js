#!/usr/bin/env node
/**
 * mock-phone.js — 模拟手机端按 docs/protocol.md v1 推送,用于不接真机时调试插件/mock-server。
 *
 * 用法:
 *   cd tools && npm install
 *   node mock-phone.js [--host 127.0.0.1] [--port 18765] [--token xxx] [--speedup 4]
 *
 * 时间线(默认总长约 30 秒,--speedup N 表示时间压缩 N 倍):
 *   t=0    hello → 收 hello_ack
 *   t=1    track_change:晴天/周杰伦 playing position=0 speed=1,附一张 1x1 JPEG 封面
 *   t=10   seek 到 120s
 *   t=15   playback_change:paused
 *   t=18   playback_change:playing(继续)
 *   t=24   track_change:不变 - 心跳由 playing 状态自动周期发送(见下)
 *   t=27   切歌: Berkeley (feat. ...) playing,无封面
 * 之后保持 playing 直到 Ctrl+C;每 15 秒 ping 一次 PC,并自动应答 PC 的 ping。
 */

const argv = process.argv.slice(2)
function argOf(name, fallback) {
  const index = argv.indexOf(name)
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback
}

const HOST = argOf('--host', '127.0.0.1')
const PORT = parseInt(argOf('--port', '18765'), 10)
const TOKEN = argOf('--token', 'please-change-this-token')
const SPEEDUP = Math.max(1, parseFloat(argOf('--speedup', '4')))

const WebSocket = require('ws')
const crypto = require('node:crypto')

// 1x1 红色像素 JPEG,验证封面链路足够了
const TINY_JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a' +
    'HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA' +
    'AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==',
  'base64'
)

let seq = 0
let ws

function send(obj) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj))
}

function stateMessage(reason, track, playback, artworkAvailable, artworkHash) {
  seq += 1
  return {
    type: 'state',
    v: 1,
    seq,
    sentAt: Date.now(),
    reason,
    app: { package: 'com.netease.cloudmusic', name: '网易云音乐' },
    playback,
    track,
    artwork: { available: artworkAvailable, hash: artworkHash || '' }
  }
}

const qingtian = {
  id: crypto.createHash('sha1').update('晴天|周杰伦|叶惠美|269000').digest('hex'),
  title: '晴天',
  artist: '周杰伦',
  album: '叶惠美',
  albumArtist: '周杰伦',
  durationMs: 269000,
  genre: 'Pop',
  year: 2003,
  trackNumber: 3,
  mediaId: '186016'
}

const berkeley = {
  id: crypto.createHash('sha1').update('Berkeley|neil young|Harvest|152000').digest('hex'),
  title: 'Berkeley',
  artist: 'neil young',
  album: 'Harvest',
  albumArtist: 'neil young',
  durationMs: 152000,
  genre: '',
  year: 1972,
  trackNumber: 1,
  mediaId: ''
}

const playing = (positionMs, speed = 1) => ({
  state: 'playing',
  positionMs: Math.round(positionMs),
  positionKnown: true,
  speed
})
const paused = (positionMs) => ({ state: 'paused', positionMs: Math.round(positionMs), positionKnown: true, speed: 0 })

const timeline = []
function at(seconds, label, fn) {
  timeline.push({ at: seconds * 1000 / SPEEDUP, label, fn })
}

at(1, 'track_change 晴天 playing pos=0', () => {
  send(stateMessage('track_change', qingtian, playing(0), true, ''))
  send({ type: 'artwork', v: 1, trackId: qingtian.id, mime: 'image/jpeg', dataBase64: TINY_JPEG.toString('base64') })
})
at(10, 'seek → 120000', () => send(stateMessage('seek', qingtian, playing(120000), true, '')))
at(15, 'playback_change paused @126s', () =>
  send(stateMessage('playback_change', qingtian, paused(126000), true, ''))
)
at(18, 'playback_change playing @126s', () =>
  send(stateMessage('playback_change', qingtian, playing(126000), true, ''))
)
at(27, 'track_change Berkeley playing pos=0', () => {
  send(stateMessage('track_change', berkeley, playing(0), false))
})

/** playing 期间模拟系统继续推进位置(心跳快照),8 秒一次 */
setInterval(() => {
  if (ws && ws.readyState === ws.OPEN && current && current.playback.state === 'playing') {
    const elapsed = Date.now() - currentSentAt
    const advanced = Math.min(current.track.durationMs, current.playback.positionMs + elapsed * current.playback.speed)
    send(stateMessage('heartbeat', current.track, playing(advanced), artworkAvailableFor(current.track.id)))
  }
}, 8000 / SPEEDUP)

let current = null
let currentSentAt = 0
const artworkByTrack = {}

function artworkAvailableFor(trackId) {
  return !!artworkByTrack[trackId]
}

function connect() {
  ws = new WebSocket(`ws://${HOST}:${PORT}/relay`)
  ws.on('open', () => {
    console.log(`[mock-phone] connected to ${HOST}:${PORT}, sending hello`)
    send({
      type: 'hello',
      v: 1,
      token: TOKEN,
      deviceId: 'mock-device-001',
      deviceName: 'Mock Pixel 8',
      appVersion: '1.1.0-mock'
    })
  })
  ws.on('message', (data) => {
    const msg = JSON.parse(data.toString())
    if (msg.type === 'hello_ack') {
      console.log('[mock-phone] hello_ack received, starting timeline')
      for (const item of timeline) setTimeout(() => { console.log(`[mock-phone] ${item.label}`); item.fn() }, item.at)
    } else if (msg.type === 'ping') {
      send({ type: 'pong', v: 1, t: msg.t, serverTime: Date.now() })
    } else if (msg.type === 'error') {
      console.log(`[mock-phone] server error: ${msg.code} ${msg.message || ''}`)
      if (msg.code === 'bad_token') process.exit(1)
    } else if (msg.type === 'request') {
      console.log(`[mock-phone] got request(${msg.what})`)
      if (msg.what === 'artwork' && artworkByTrack[current?.id]) {
        send({ type: 'artwork', v: 1, trackId: current.id, mime: 'image/jpeg', dataBase64: artworkByTrack[current.id].toString('base64') })
      } else if (msg.what === 'state' && current) {
        send(stateMessage('heartbeat', current.track, current.playback, artworkAvailableFor(current.id)))
      }
    }
  })
  ws.on('close', () => {
    console.log('[mock-phone] disconnected, retrying in 3s')
    setTimeout(connect, 3000)
  })
  ws.on('error', (err) => console.log(`[mock-phone] error: ${err.message}`))
}

// 包装:任何 state 都更新 current 锚点,保证后续心跳从最新位置继续外推
const rawSend = send
send = function patchedSend(obj) {
  if (obj.type === 'state') {
    current = { track: obj.track, playback: obj.playback }
    currentSentAt = Date.now()
  }
  if (obj.type === 'artwork') artworkByTrack[obj.trackId] = TINY_JPEG
  rawSend(obj)
}

// 每 15 秒主动 ping PC(保活 + 自测 RTT)
setInterval(() => send({ type: 'ping', v: 1, t: Date.now() }), 15000)

connect()
process.on('SIGINT', () => process.exit(0))
