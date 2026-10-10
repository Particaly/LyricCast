/**
 * 网易云音乐 provider(中文曲库回退源)。
 *
 * ⚠️ 稳定性与合规:使用的是非官方公开接口(music.163.com 的 web API),
 *   可能随时变更、限流或需要风控参数;失败由上层静默回退,不影响 LRCLIB 主流程。
 *   仅作个人听歌的歌词展示用途,不缓存/转发任何音频内容。
 *
 * 流程:搜索(/api/search/get)→ 挑最佳候选 → 歌词(/api/song/lyric,含翻译)。
 */
import { fetchJson } from '../fetcher'
import { pickBestCandidate, type Candidate } from '../match'
import type { LyricProvider, LyricQuery, LyricResult } from '../types'

const TIMEOUT_MS = 5_000
const HEADERS = {
  Referer: 'https://music.163.com/',
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) lan-lyrics-plugin/0.1'
}

interface NeteaseSong {
  id: number
  name: string
  duration: number // 毫秒
  artists?: Array<{ name: string }>
  album?: { name: string }
}

interface NeteaseLyricResponse {
  lrc?: { lyric?: string }
  tlyric?: { lyric?: string }
}

function searchUrl(keyword: string): string {
  return `https://music.163.com/api/search/get?${new URLSearchParams({
    s: keyword,
    type: '1',
    limit: '8'
  })}`
}

export const neteaseProvider: LyricProvider = {
  name: 'netease',

  async search(query: LyricQuery, signal: AbortSignal): Promise<LyricResult | null> {
    const keyword = `${query.title} ${query.artist}`.trim()
    if (!keyword) return null

    const searchResult = (await fetchJson(searchUrl(keyword), {
      headers: HEADERS,
      signal
    })) as { result?: { songs?: NeteaseSong[] } }
    const songs = searchResult?.result?.songs ?? []
    if (songs.length === 0) return null

    const candidates = songs.map(
      (song): Candidate => ({
        title: song.name,
        artist: song.artists?.map((a) => a.name).join('/') ?? '',
        album: song.album?.name ?? '',
        durationMs: song.duration ?? 0,
        id: song.id
      })
    )
    const best = pickBestCandidate(candidates, query)
    if (!best || !best.id) return null

    const lyricResult = (await fetchJson(
      `https://music.163.com/api/song/lyric?${new URLSearchParams({
        id: String(best.id),
        lv: '1',
        kv: '1',
        tv: '-1'
      })}`,
      { headers: HEADERS, signal }
    )) as NeteaseLyricResponse

    const synced = lyricResult?.lrc?.lyric?.trim() || null
    const translation = lyricResult?.tlyric?.lyric?.trim() || null
    // 无时间轴也无纯文本 → 未命中
    if (!synced) return null
    // 网易云返回的即使"没有歌词"也是时间轴空壳(只有元数据行),交给解析层/展示层判断行数
    return { synced, plain: null, translation, source: 'netease' }
  }
}

export const NETEASE_TIMEOUT = TIMEOUT_MS
