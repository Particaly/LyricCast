/**
 * UI 侧状态(store):订阅 preload 服务事件,聚合为响应式数据。
 * 刻意不引 Pinia —— 单页面、单数据源,reactive 足够且少一层构建依赖。
 */
import { reactive, computed } from 'vue'
import type { ProtocolStateMessage, ServerEvent, LanSnapshot } from './env.d'

export interface ArtworkEntry {
  mime: string
  dataBase64: string
}

interface UiState {
  snapshot: LanSnapshot | null
  /** 各设备最近一次协议 state 消息,活跃设备的取值驱动"正在播放"卡片 */
  statesByDevice: Record<string, ProtocolStateMessage>
  /** trackId → 封面 */
  artworks: Record<string, ArtworkEntry>
  serverError: string
  started: boolean
}

export const ui = reactive<UiState>({
  snapshot: null,
  statesByDevice: {},
  artworks: {},
  serverError: '',
  started: false
})

/** 活跃设备当前的 state 快照(切设备/切歌自动跟随) */
export const activeState = computed<ProtocolStateMessage | null>(
  () => (ui.snapshot?.activeDeviceId ? ui.statesByDevice[ui.snapshot.activeDeviceId] ?? null : null)
)

/** 活跃设备当前封面(按 trackId 关联;requestArtwork 可补拉) */
export const activeArtwork = computed<ArtworkEntry | null>(() => {
  const state = activeState.value
  const services = window.services
  if (!state) return null
  return (
    ui.artworks[state.track.id] ??
    (services ? services.lanLyrics.getArtwork(state.track.id) : null)
  )
})

function handleEvent(event: ServerEvent) {
  switch (event.kind) {
    case 'snapshot':
      ui.snapshot = window.services.lanLyrics.getSnapshot()
      break
    case 'state':
      if (event.deviceId && event.state) ui.statesByDevice[event.deviceId] = event.state
      ui.snapshot = window.services.lanLyrics.getSnapshot()
      break
    case 'artwork':
      if (event.trackId && event.artwork) ui.artworks[event.trackId] = event.artwork
      break
    case 'server-error':
      ui.serverError = event.message || '服务启动失败'
      break
  }
}

/**
 * 初始化:订阅事件 + 读取持久化配置启动服务(无配置则生成 Token 后启动)。
 * @returns 清理函数(组件卸载时取消订阅、停 rAF 由调用方自理)
 */
export async function initStore(): Promise<() => void> {
  const services = window.services
  const unsubscribe = services.lanLyrics.subscribe(handleEvent)
  const saved = services.lanLyrics.getSnapshot()
  if (!saved.token) {
    // 兜底:正常情况下 preload 已持久化过 Token
    services.lanLyrics.saveConfig({ token: services.lanLyrics.generateToken() })
  }
  const result = await services.lanLyrics.start()
  ui.started = result.ok
  ui.serverError = result.ok ? '' : result.error || '启动失败'
  ui.snapshot = services.lanLyrics.getSnapshot()
  return unsubscribe
}
