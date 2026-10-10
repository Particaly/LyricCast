/**
 * 歌词编排:缓存 → providers 顺序回退 → 写缓存/负缓存;切歌取消在途请求。
 * 所有 provider 异常都被吞掉(隔离),一个来源挂了不影响下一个和主流程。
 */
import { RequestSupervisor } from './fetcher'
import { LyricCache, type KVStorage } from './cache'
import { cleanArtist, cleanTitle } from './clean-title'
import type { LyricProvider, LyricQuery, LyricResult } from './types'

export interface TrackForLyrics {
  id: string
  title: string
  artist: string
  album: string
  durationMs: number
}

export interface LyricsManagerOptions {
  storage: KVStorage
  providers: LyricProvider[]
  timeoutMs?: number
}

export class LyricsManager {
  private supervisor = new RequestSupervisor()
  private cache: LyricCache
  private providers: LyricProvider[]
  private timeoutMs: number

  constructor(options: LyricsManagerOptions) {
    this.cache = new LyricCache(options.storage)
    this.providers = options.providers
    this.timeoutMs = options.timeoutMs ?? 5_000
  }

  /**
   * 取一首歌的歌词(带缓存)。
   * @param track 手机推送的曲目快照
   * @param appPackage 来源 App(预留:按来源记忆偏移;不参与查询)
   * @returns 命中返回结果;确认无/负缓存新鲜期返回 null
   */
  async fetchForTrack(track: TrackForLyrics, appPackage = ''): Promise<LyricResult | null> {
    const query = this.buildQuery(track)
    if (!query.title) return null

    const cached = this.cache.get(query)
    if (cached) return cached
    if (this.cache.isNegativeFresh(query)) return null

    // 新歌:中止上一首的在途请求
    this.supervisor.cancelAll()
    const controller = this.supervisor.create(this.timeoutMs)

    for (const provider of this.providers) {
      try {
        const result = await provider.search(query, controller.signal)
        if (result && (result.synced || result.plain)) {
          this.cache.put(query, result)
          return result
        }
      } catch (err) {
        if (controller.signal.aborted) return null // 已切歌,放弃
        // 单个来源失败:记日志,继续下一个
        console.warn(`[lyrics] provider ${provider.name} failed:`, (err as Error)?.message)
      }
      if (controller.signal.aborted) return null
    }

    // 全部未命中:写负缓存(24h)
    this.cache.put(query, null)
    return null
  }

  /** 组装查询:标题/歌手先清洗,提高命中率 */
  private buildQuery(track: TrackForLyrics): LyricQuery {
    return {
      title: cleanTitle(track.title, track.artist),
      artist: cleanArtist(track.artist),
      album: track.album || '',
      durationMs: track.durationMs || 0
    }
  }
}
