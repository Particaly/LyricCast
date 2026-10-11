/**
 * LRCLIB provider(主来源,公开 API,https://lrclib.net)。
 * 策略:/api/get 精确查询(标题+歌手+专辑+时长)未命中 → /api/search 模糊查询 + 本地挑选。
 * 稳定性:公益服务,可能限流;失败由上层静默回退,不影响主流程。
 */
import { fetchJson } from '../fetcher'
import { pickBestCandidate, type Candidate } from '../match'
import type { LyricProvider, LyricQuery, LyricResult } from '../types'

const BASE = 'https://lrclib.net/api'
const TIMEOUT_MS = 5_000

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

export const lrclibProvider: LyricProvider = {
  name: 'lrclib',

  async search(query: LyricQuery, signal: AbortSignal): Promise<LyricResult | null> {
    // 1) 精确查询(参数齐全时)
    const durationSec = query.durationMs > 0 ? Math.round(query.durationMs / 1000) : undefined
    const getParams: Record<string, string> = {
      track_name: query.title,
      artist_name: query.artist
    }
    if (query.album) getParams.album_name = query.album
    if (durationSec) getParams.duration = String(durationSec)

    const headers = {
      // LRCLIB 建议标注来源,方便对方联系;不含任何隐私信息
      'User-Agent': 'lan-lyrics-ztools-plugin/0.1'
    }

    try {
      const record = (await fetchJson(`${BASE}/get?${toQuery(getParams)}`, {
        headers,
        signal
      })) as LrcLibRecord
      const result = recordToResult(record)
      if (result) return result
    } catch (err) {
      if (signal.aborted) throw err
      // 404 等:走模糊搜索
    }

    // 2) 模糊搜索 + 本地挑选;同名候选优先挑带时间轴的(plain-only 记录仅作兜底)
    const candidates = (await fetchJson(
      `${BASE}/search?${toQuery({ track_name: query.title, artist_name: query.artist })}`,
      { headers, signal }
    )) as LrcLibRecord[]
    if (!Array.isArray(candidates) || candidates.length === 0) return null

    const normalized = candidates.map((c) => ({
      ...c,
      title: c.trackName,
      artist: c.artistName,
      album: c.albumName,
      durationMs: (c.duration || 0) * 1000
    }))
    const withSynced = normalized.filter(
      (c) => typeof c.syncedLyrics === 'string' && c.syncedLyrics.trim()
    )
    const best = pickBestCandidate(withSynced, query) ?? pickBestCandidate(normalized, query)
    return best ? recordToResult(best as LrcLibRecord) : null
  }
}

export const LRCLIB_TIMEOUT = TIMEOUT_MS
