/**
 * LRCLIB provider(主来源,公开 API,https://lrclib.net)。
 * 策略:/api/get 精确查询 → /api/search 模糊查询(优先 track_name/artist_name 分字段,
 * 失败退回单个 q)→ 多候选本地挑选。查询变体按序尝试,命中即停;
 * 总请求数封顶(MAX_REQUESTS),避免对公益接口造成压力。
 * 稳定性:公益服务,可能限流;失败由上层静默回退,不影响主流程。
 */
import { fetchJson } from '../fetcher'
import { pickBestWithScore, type Candidate } from '../match'
import { buildQueryVariants } from '../query-variants'
import type { LyricProvider, LyricQuery, LyricResult, ProviderOutcome } from '../types'

const BASE = 'https://lrclib.net/api'
const TIMEOUT_MS = 5_000
/** 单次 search 调用的总请求数上限(get + 若干次 search) */
const MAX_REQUESTS = 4

interface LrcLibRecord extends Candidate {
  id: number
  trackName: string
  artistName: string
  albumName: string
  duration: number // 秒
  instrumental: boolean
  plainLyrics: string | null
  syncedLyrics: string | null
}

function toQuery(params: Record<string, string>): string {
  return new URLSearchParams(params).toString()
}

function recordToResult(record: LrcLibRecord): LyricResult | null {
  if (record.instrumental) return null // 纯音乐按未命中处理,让回退链继续
  const synced = record.syncedLyrics || null
  const plain = record.plainLyrics || null
  if (!synced && !plain) return null
  return { synced, plain, translation: null, source: 'lrclib' }
}

/** 搜索结果归一为统一候选;synced 一并带上,供打分做歌词内容反校验 */
function toCandidate(record: LrcLibRecord): Candidate {
  return {
    title: record.trackName,
    artist: record.artistName,
    album: record.albumName,
    durationMs: (record.duration || 0) * 1000,
    synced: record.syncedLyrics || null,
    translation: null,
    id: record.id,
    instrumental: record.instrumental,
    plainLyrics: record.plainLyrics,
    syncedLyrics: record.syncedLyrics
  }
}

export const lrclibProvider: LyricProvider = {
  name: 'lrclib',

  async search(query: LyricQuery, signal: AbortSignal): Promise<ProviderOutcome> {
    const variants = buildQueryVariants(query)
    if (variants.length === 0) return null

    const headers = {
      // LRCLIB 建议标注来源,方便对方联系;不含任何隐私信息
      'User-Agent': 'lan-lyrics-ztools-plugin/0.2'
    }
    let requests = 0

    // 1) 精确查询(变体 0 + 专辑 + 时长,参数齐全时)
    const primary = variants[0]
    const durationSec = query.durationMs > 0 ? Math.round(query.durationMs / 1000) : undefined
    const getParams: Record<string, string> = {
      track_name: primary.title,
      artist_name: primary.artist
    }
    if (query.album) getParams.album_name = query.album
    if (durationSec) getParams.duration = String(durationSec)

    requests++
    try {
      const record = (await fetchJson(`${BASE}/get?${toQuery(getParams)}`, { headers, signal })) as LrcLibRecord
      const result = recordToResult(record)
      if (result) return { status: 'found', result }
    } catch (err) {
      if (signal.aborted) throw err
      // 404 等:走模糊搜索
    }

    // 2) 模糊搜索:变体按序,每档先分字段再 q;命中(有候选)即停;总请求封顶
    for (const variant of variants) {
      const fieldParams: Record<string, string> = { track_name: variant.title }
      if (variant.artist) fieldParams.artist_name = variant.artist

      const attempts: Array<Record<string, string>> = [fieldParams]
      if (variant.artist) attempts.push({ q: `${variant.title} ${variant.artist}`.trim() })
      else attempts.push({ q: variant.title })

      for (const params of attempts) {
        if (requests >= MAX_REQUESTS) break
        requests++
        let records: LrcLibRecord[]
        try {
          records = (await fetchJson(`${BASE}/search?${toQuery(params)}`, { headers, signal })) as LrcLibRecord[]
        } catch (err) {
          if (signal.aborted) throw err
          continue // 单档搜索失败继续下一档
        }
        if (!Array.isArray(records) || records.length === 0) continue
        const candidates = records.map(toCandidate)
        const best = pickBestWithScore(candidates, query)
        if (!best) continue
        const result = recordToResult(best.candidate as LrcLibRecord)
        if (result) return { status: 'found', result, score: best.score }
      }
      if (requests >= MAX_REQUESTS) break
    }
    return { status: 'not_found' }
  }
}

export const LRCLIB_TIMEOUT = TIMEOUT_MS
