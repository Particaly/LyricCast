/**
 * 歌词缓存:键为 规范化 title|artist|duration(秒),存 ztools.dbStorage。
 *
 * - 命中:直接返回,不打网络;
 * - 负缓存(查过但没有):带 24h 过期,避免每首冷门歌反复请求;
 * - 存储适配器注入,便于单测(Map 假存储)。
 */
import { normalizeText } from './clean-title'
import type { LyricQuery, LyricResult } from './types'

/** 与 ztools.dbStorage 同形的最小存储接口 */
export interface KVStorage {
  getItem(key: string): any
  setItem(key: string, value: any): void
  removeItem(key: string): void
}

const NEGATIVE_TTL_MS = 24 * 60 * 60 * 1000
const PREFIX = 'lyric.v1.'

interface PositiveEntry {
  type: 'result'
  savedAt: number
  result: LyricResult
}

interface NegativeEntry {
  type: 'negative'
  savedAt: number
}

export function cacheKey(query: LyricQuery): string {
  const durationSec = query.durationMs > 0 ? Math.round(query.durationMs / 1000) : 0
  return `${PREFIX}${normalizeText(query.title)}|${normalizeText(query.artist)}|${durationSec}`
}

export class LyricCache {
  private readonly now: () => number

  /**
   * @param storage 持久化实现(ztools.dbStorage 或测试用 Map 适配器)
   * @param now 时间源,负缓存过期判定用;测试可注入
   */
  constructor(private storage: KVStorage, now: () => number = Date.now) {
    this.now = now
  }

  /** 命中返回结果;未命中或负缓存新鲜期内返回 null 但不区分,调用方用 isNegativeMiss 判断 */
  get(query: LyricQuery): LyricResult | null {
    const entry = this.read(cacheKey(query))
    return entry && entry.type === 'result' ? entry.result : null
  }

  /**
   * 是否处于"最近查过且没有"的负缓存新鲜期(此时不再发请求)。
   * 过期的负缓存会被视为可重试。
   */
  isNegativeFresh(query: LyricQuery): boolean {
    const entry = this.read(cacheKey(query))
    if (!entry || entry.type !== 'negative') return false
    return this.now() - entry.savedAt < NEGATIVE_TTL_MS
  }

  /** 写入结果;result 为 null 表示"确认没有",写负缓存 */
  put(query: LyricQuery, result: LyricResult | null): void {
    const key = cacheKey(query)
    if (result) {
      const entry: PositiveEntry = { type: 'result', savedAt: this.now(), result }
      this.storage.setItem(key, entry)
    } else {
      const entry: NegativeEntry = { type: 'negative', savedAt: this.now() }
      this.storage.setItem(key, entry)
    }
  }

  private read(key: string): PositiveEntry | NegativeEntry | null {
    try {
      const entry = this.storage.getItem(key)
      if (entry && (entry.type === 'result' || entry.type === 'negative')) return entry
    } catch (_) { /* 存储损坏时当未命中 */ }
    return null
  }
}

/** Map 假存储(测试与无 ztools 环境兜底) */
export function createMemoryStorage(): KVStorage {
  const map = new Map<string, any>()
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key)
  }
}
