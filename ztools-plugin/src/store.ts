/**
 * UI 侧状态(store):订阅 preload 服务事件,聚合为响应式数据。
 * 刻意不引 Pinia —— 单页面、单数据源,reactive 足够且少一层构建依赖。
 */
import { reactive, computed, ref } from 'vue'
import type { ProtocolStateMessage, ServerEvent, LanSnapshot } from './env.d'
import { parseLrc } from './lyric/parse-lrc'
import type { LyricsLine } from './lyric/types'
import { LyricsManager } from './lyric/lyrics-manager'
import { OffsetStore, type OffsetScope } from './lyric/offsets'
import { createMemoryStorage, type KVStorage } from './lyric/cache'
import { lrclibProvider } from './lyric/providers/lrclib'
import { neteaseProvider } from './lyric/providers/netease'

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

// ==================== 歌词状态 ====================

/** 歌词展示状态;translationMap:timeMs → 翻译文本(与主歌词时间戳对齐) */
export const lyric = reactive({
  trackId: '',
  loading: false,
  source: '',
  lines: [] as LyricsLine[],
  translationMap: new Map<number, string>(),
  plain: '',
  notFound: false
})

/** 当前生效的手动偏移(毫秒):正值 = 歌词提前显示 */
export const userOffset = ref(0)

/** 歌词字号(像素),持久化 */
export const fontSize = ref(15)

function storageAdapter(): KVStorage {
  try {
    if (window.ztools?.dbStorage) return window.ztools.dbStorage
  } catch (_) { /* 无 ztools 环境(纯浏览器调试)退化为内存 */ }
  return createMemoryStorage()
}

const lyricManager = new LyricsManager({
  storage: storageAdapter(),
  // LRCLIB 主源在前,网易云为中文回退源;任一失败自动走下一个
  providers: [lrclibProvider, neteaseProvider]
})
export const offsetStore = new OffsetStore(storageAdapter())

/** 当前偏移的记录维度与本曲/此应用的原始记录值(UI 展示与按钮作用目标) */
export const offsetUi = reactive({
  scope: 'track' as OffsetScope,
  trackValue: 0,
  appValue: 0
})

let fetchSeq = 0

function readFontSize() {
  try {
    const saved = window.ztools?.dbStorage?.getItem('lyric.fontSize')
    if (typeof saved === 'number' && saved >= 11 && saved <= 30) fontSize.value = saved
  } catch (_) { /* 忽略 */ }
}

export function setFontSize(next: number) {
  fontSize.value = Math.min(30, Math.max(11, next))
  try {
    window.ztools?.dbStorage?.setItem('lyric.fontSize', fontSize.value)
  } catch (_) { /* 忽略 */ }
}

/**
 * 拉取并解析一首歌的歌词;切歌并发时旧的在途结果按序号丢弃。
 */
async function fetchLyrics(state: ProtocolStateMessage) {
  const trackId = state.track.id
  const appPackage = state.app?.package ?? ''
  lyric.trackId = trackId
  lyric.loading = true
  lyric.lines = []
  lyric.translationMap = new Map()
  lyric.plain = ''
  lyric.source = ''
  lyric.notFound = false

  // 偏移:track 记录优先,否则 app 记录
  userOffset.value = offsetStore.effective(trackId, appPackage)
  offsetUi.trackValue = offsetStore.get('track', trackId)
  offsetUi.appValue = offsetStore.get('app', appPackage)
  offsetUi.scope = offsetUi.trackValue !== 0 ? 'track' : 'track'

  const seq = ++fetchSeq
  const result = await lyricManager.fetchForTrack(
    {
      id: trackId,
      title: state.track.title ?? '',
      artist: state.track.artist ?? '',
      album: state.track.album ?? '',
      durationMs: state.track.durationMs ?? 0
    },
    appPackage
  )
  if (seq !== fetchSeq) return // 期间已切歌
  lyric.loading = false
  if (!result) {
    lyric.notFound = true
    return
  }
  lyric.source = result.source
  if (result.synced) {
    lyric.lines = parseLrc(result.synced).lines
    if (result.translation) {
      const translationLines = parseLrc(result.translation).lines
      lyric.translationMap = new Map(translationLines.map((line) => [line.timeMs, line.text]))
    }
  } else if (result.plain) {
    lyric.plain = result.plain
  } else {
    lyric.notFound = true
  }
}

/**
 * 应用手动偏移:按 offsetUi.scope 写入本曲或此应用维度,并刷新当前生效值。
 * @param deltaMs 正值 = 歌词提前
 */
export function applyOffset(deltaMs: number) {
  const state = activeState.value
  if (!state) return
  const trackId = state.track.id
  const appPackage = state.app?.package ?? ''
  if (deltaMs === 0) {
    offsetStore.resetTrack(trackId)
  } else {
    offsetStore.add(offsetUi.scope, offsetUi.scope === 'track' ? trackId : appPackage, deltaMs)
  }
  userOffset.value = offsetStore.effective(trackId, appPackage)
  offsetUi.trackValue = offsetStore.get('track', trackId)
  offsetUi.appValue = offsetStore.get('app', appPackage)
}

function handleEvent(event: ServerEvent) {
  switch (event.kind) {
    case 'snapshot':
      ui.snapshot = window.services.lanLyrics.getSnapshot()
      break
    case 'state': {
      if (event.deviceId && event.state) ui.statesByDevice[event.deviceId] = event.state
      ui.snapshot = window.services.lanLyrics.getSnapshot()
      // 只有活跃设备的曲目变化才触发歌词拉取
      if (
        event.deviceId &&
        event.deviceId === ui.snapshot?.activeDeviceId &&
        event.state &&
        event.state.track.id !== lyric.trackId
      ) {
        void fetchLyrics(event.state)
      }
      break
    }
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
 * @returns 清理函数(组件卸载时取消订阅)
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
  readFontSize()
  // 插件后启动(ZTools 重启后服务先于 UI 就绪)时,UI 需要主动补一次歌词
  const state = activeState.value
  if (state && state.track.id !== lyric.trackId) void fetchLyrics(state)
  return unsubscribe
}
