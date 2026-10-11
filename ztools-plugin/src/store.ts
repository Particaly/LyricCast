/**
 * UI 侧状态(store):订阅 preload 服务事件,聚合为响应式数据。
 * 刻意不引 Pinia —— 单页面、单数据源,reactive 足够且少一层构建依赖。
 *
 * v1.1:各设备状态升级为 ProgressAnchor(progress.ts,含接收时刻与断线墓碑字段),
 * 进度推算/降级判定在 App.vue 用纯函数完成;断线后保留最后一首歌的信息(标灰)。
 */
import { reactive, computed, ref } from 'vue'
import type { LyricProvider } from './lyric/types'
import type { ProtocolStateMessage, ServerEvent, LanSnapshot } from './env.d'
import type { ProgressAnchor } from './progress'
import { parseLrc } from './lyric/parse-lrc'
import type { LyricsLine } from './lyric/types'
import { LyricsManager } from './lyric/lyrics-manager'
import { OffsetStore, type OffsetScope } from './lyric/offsets'
import { createMemoryStorage, type KVStorage } from './lyric/cache'
import { lrclibProvider } from './lyric/providers/lrclib'
import { neteaseProvider } from './lyric/providers/netease'
import { loadSettings } from './settings'

export interface ArtworkEntry {
  mime: string
  dataBase64: string
}

interface UiState {
  snapshot: LanSnapshot | null
  /** 各设备最近一次协议 state/snapshot 的锚点(进度插值 + 存活判定),见 progress.ts */
  anchorsByDevice: Record<string, ProgressAnchor>
  /** trackId → 封面 */
  artworks: Record<string, ArtworkEntry>
  serverError: string
  /** 桌面歌词悬浮窗创建失败原因(空 = 正常) */
  desktopError: string
  started: boolean
}

export const ui = reactive<UiState>({
  snapshot: null,
  anchorsByDevice: {},
  artworks: {},
  serverError: '',
  desktopError: '',
  started: false
})

/** 活跃设备当前锚点(切设备/切歌自动跟随;断线宽限期内服务端仍保留该设备) */
export const activeAnchor = computed<ProgressAnchor | null>(() => {
  const id = ui.snapshot?.activeDeviceId
  return id ? ui.anchorsByDevice[id] ?? null : null
})

/**
 * 断线且宽限期结束(服务端清理墓碑,activeDeviceId 变空)后,继续展示最后一首歌。
 * 仅在 activeAnchor 为空时兜底,UI 据此标灰。
 */
export const lastActiveAnchor = ref<ProgressAnchor | null>(null)

/** 展示用锚点:活跃优先,断线兜底其次 */
export const displayAnchor = computed<ProgressAnchor | null>(
  () => activeAnchor.value ?? lastActiveAnchor.value
)

/** 活跃设备的 state 消息(歌词/元数据沿用) */
export const activeState = computed<ProtocolStateMessage | null>(() => activeAnchor.value?.state ?? null)

/** 活跃设备当前封面(按 trackId 关联;requestArtwork 可补拉) */
export const activeArtwork = computed<ArtworkEntry | null>(() => {
  const state = displayAnchor.value?.state
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

function storageAdapter(): KVStorage {
  try {
    if (window.ztools?.dbStorage) return window.ztools.dbStorage
  } catch (_) { /* 无 ztools 环境(纯浏览器调试)退化为内存 */ }
  return createMemoryStorage()
}

/** dev 浏览器预览(dev-mock.ts)注入的本地歌词源;ZTools 内不存在 */
const devProvider = (window as any)?.__devLyricProvider as LyricProvider | undefined

const lyricManager = new LyricsManager({
  storage: storageAdapter(),
  // LRCLIB 主源在前,网易云为中文回退源;任一失败自动走下一个。
  // 优先取带有效时间轴的结果(synced-first 两轮回退见 lyrics-manager.ts)。
  providers: devProvider ? [devProvider, lrclibProvider, neteaseProvider] : [lrclibProvider, neteaseProvider]
})
export const offsetStore = new OffsetStore(storageAdapter())

/** 当前偏移的记录维度与本曲/此应用的原始记录值(UI 展示与按钮作用目标) */
export const offsetUi = reactive({
  scope: 'track' as OffsetScope,
  trackValue: 0,
  appValue: 0
})

let fetchSeq = 0

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
      // 先刷新设备列表,再写锚点,保证 activeDeviceId 比对用的是新快照
      ui.snapshot = window.services.lanLyrics.getSnapshot()
      if (event.deviceId && event.state) {
        // 全量覆盖锚点(协议 §2.8);snapshot/state 载荷同构,断线标志一并复位
        ui.anchorsByDevice[event.deviceId] = {
          state: event.state,
          recvAt: event.meta?.recvAt ?? Date.now(),
          lastAppMessageAt: event.meta?.lastAppMessageAt ?? Date.now(),
          disconnectedAt: null
        }
        if (event.deviceId === ui.snapshot?.activeDeviceId) {
          lastActiveAnchor.value = ui.anchorsByDevice[event.deviceId]
        }
      }
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
    case 'link':
      // 链路断开:保留锚点进入宽限期(progress.ts 按墙钟降级),不清空展示
      if (event.deviceId && ui.anchorsByDevice[event.deviceId]) {
        ui.anchorsByDevice[event.deviceId].disconnectedAt = event.disconnectedAt ?? Date.now()
      }
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
  loadSettings()
  // 插件后启动(ZTools 重启后服务先于 UI 就绪)时,UI 需要主动补一次歌词
  const state = activeState.value
  if (state && state.track.id !== lyric.trackId) void fetchLyrics(state)
  // UI 重载(重新进入插件)而手机已连接且暂停时,可能长时间没有新事件,
  // 主动请求一次全量快照补齐显示(旧版手机以 state 应答,同样有效)
  services.lanLyrics.requestState()
  return unsubscribe
}
