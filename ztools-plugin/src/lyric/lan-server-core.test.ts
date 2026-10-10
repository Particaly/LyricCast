/**
 * PC 端插值计算测试:直接驱动 preload/lan-server-core.js(CommonJS),
 * 用 getPosition 的注入时钟做确定性断言(协议 §5)。
 */
import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const { LanServerCore } = require(path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../src-ztools/preload/lan-server-core.js'
))

/** 不真正监听端口的实例:直接注入设备状态测试纯逻辑 */
function makeCore() {
  const core = new LanServerCore({ port: 0, token: 'x'.repeat(16), logger: { log() {}, error() {} } })
  const device = {
    ws: { readyState: 1 },
    peer: 'test',
    deviceId: 'd1',
    deviceName: 'Test Phone',
    appVersion: '1',
    authed: true,
    lastState: null,
    lastStateAt: 0,
    lastMessageAt: Date.now(),
    rttMs: -1,
    clockOffsetMs: 0,
    artwork: new Map()
  }
  core.devices.set('d1', device)
  core.activeDeviceId = 'd1'
  return { core, device }
}

const baseState = {
  type: 'state',
  v: 1,
  seq: 1,
  sentAt: 100_000,
  reason: 'track_change',
  app: { package: 'p', name: 'P' },
  playback: { state: 'playing', positionMs: 10_000, positionKnown: true, speed: 1 },
  track: { id: 't1', title: 'Song', artist: 'Artist', album: '', albumArtist: '', durationMs: 200_000 },
  artwork: { available: false }
}

describe('LanServerCore.getPosition', () => {
  beforeEach(() => {})

  it('interpolates from sentAt corrected by clock offset', () => {
    const { core, device } = makeCore()
    device.lastState = { ...baseState }
    // 手机钟比 PC 钟快 500ms:sentAt(手机钟)=100_000 对应 PC 钟 99_500
    device.clockOffsetMs = 500
    // PC 钟 100_500 时:位置 = 10_000 + (100_500 − 99_500) × 1 = 11_000
    expect(core.getPosition('d1', 100_500)?.positionMs).toBe(11_000)
  })

  it('respects playback speed', () => {
    const { core, device } = makeCore()
    device.lastState = { ...baseState, playback: { state: 'playing', positionMs: 10_000, positionKnown: true, speed: 2 } }
    expect(core.getPosition('d1', 101_000)?.positionMs).toBe(12_000)
  })

  it('freezes when paused and clamps to duration', () => {
    const { core, device } = makeCore()
    device.lastState = { ...baseState, playback: { state: 'paused', positionMs: 10_000, positionKnown: true, speed: 0 } }
    expect(core.getPosition('d1', 200_000)?.positionMs).toBe(10_000)

    device.lastState = { ...baseState, playback: { state: 'playing', positionMs: 199_000, positionKnown: true, speed: 2 } }
    expect(core.getPosition('d1', 200_000)?.positionMs).toBe(200_000)
  })

  it('never fakes unknown position', () => {
    const { core, device } = makeCore()
    device.lastState = { ...baseState, playback: { state: 'playing', positionMs: -1, positionKnown: false, speed: 1 } }
    const result = core.getPosition('d1', 200_000)
    expect(result?.known).toBe(false)
    expect(result?.positionMs).toBe(-1)
  })

  it('returns null without any state', () => {
    const { core } = makeCore()
    expect(core.getPosition('d1', 100_000)).toBeNull()
  })

  it('auto-selects a playing device over a paused one', () => {
    const { core, device } = makeCore()
    const pausedDevice = { ...device, deviceId: 'd2', lastStateAt: 999 }
    pausedDevice.lastState = { ...baseState, playback: { state: 'paused', positionMs: 1, positionKnown: true, speed: 0 } }
    device.lastState = { ...baseState, playback: { state: 'playing', positionMs: 1, positionKnown: true, speed: 1 } }
    device.lastStateAt = 1
    core.devices.set('d2', pausedDevice)
    const selected = (core as any)._autoSelect()
    expect(selected).toBe('d1')
  })
})
