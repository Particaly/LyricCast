/**
 * 歌词编排:缓存 → providers 顺序回退 → 写缓存/负缓存;切歌取消在途请求。
 * 所有 provider 异常都被吞掉(隔离),一个来源挂了不影响下一个和主流程。
 */
import { RequestSupervisor } from './fetcher'
import { LyricCache, type KVStorage } from './cache'
import { cleanArtist, cleanTitleDetailed, parseArtists } from './clean-title'
import { isUsableSynced, lrcToPlain } from './parse-lrc'
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
   *
   * 匹配策略(尽量拿到带有效时间轴的歌词):
   * 1. 依次问每个 provider,谁先给出**有效时间轴**(isUsableSynced 把关)就直接采用;
   * 2. 全部没有时间轴时,返回过程中记住的第一份纯文本兜底;
   * 3. 伪时间轴(网易云对无时间轴歌曲返回的空壳)在 normalize 中降级为纯文本,
   *    所以下游拿到的 synced 一定是可用的。
   *
   * @param track 手机推送的曲目快照
   * @param appPackage 来源 App(预留:按来源记忆偏移;不参与查询)
   * @returns 命中返回结果;确认无/负缓存新鲜期返回 null
   */
  async fetchForTrack(track: TrackForLyrics, appPackage = ''): Promise<LyricResult | null> {
    const query = this.buildQuery(track)
    if (!query.title) return null

    const cached = this.cache.get(query)
    if (cached) return this.normalize(cached) ?? null
    if (this.cache.isNegativeFresh(query)) return null

    // 新歌:中止上一首的在途请求
    this.supervisor.cancelAll()
    const controller = this.supervisor.create(this.timeoutMs)

    let plainFallback: LyricResult | null = null
    for (const provider of this.providers) {
      let result: LyricResult | null = null
      try {
        result = await provider.search(query, controller.signal)
      } catch (err) {
        if (controller.signal.aborted) return null // 已切歌,放弃
        // 单个来源失败:记日志,继续下一个
        console.warn(`[lyrics] provider ${provider.name} failed:`, (err as Error)?.message)
      }
      if (controller.signal.aborted) return null

      const normalized = this.normalize(result)
      if (!normalized) continue
      if (normalized.synced) {
        this.cache.put(query, normalized)
        return normalized
      }
      if (!plainFallback) plainFallback = normalized
    }

    if (plainFallback) {
      this.cache.put(query, plainFallback)
      return plainFallback
    }

    // 全部未命中:写负缓存(24h)
    this.cache.put(query, null)
    return null
  }

  /**
   * 结果规范化:确保 synced 字段一定是有效时间轴。
   * 无效时间轴(少于 2 行 / 全零时间戳 / 跨度过短)降级为纯文本;
   * 降级后连纯文本都凑不出的结果按未命中处理。
   */
  private normalize(result: LyricResult | null): LyricResult | null {
    if (!result) return null
    if (!result.synced && !result.plain) return null
    if (result.synced && !isUsableSynced(result.synced)) {
      const plain = result.plain || lrcToPlain(result.synced)
      if (!plain) return null
      return { ...result, synced: null, plain }
    }
    return result
  }

  /**
   * 组装查询:标题/歌手先清洗提高命中率,同时保留原始值与版本标签:
   * rawTitle/rawArtist 供 provider 的查询变体回退,versionTags 供打分做对称版本惩罚。
   */
  private buildQuery(track: TrackForLyrics): LyricQuery {
    const cleaned = cleanTitleDetailed(track.title, track.artist)
    return {
      title: cleaned.title,
      artist: cleanArtist(track.artist),
      album: track.album || '',
      durationMs: track.durationMs || 0,
      rawTitle: track.title,
      rawArtist: track.artist,
      allArtists: parseArtists(track.artist),
      versionTags: [...cleaned.tags]
    }
  }
}
