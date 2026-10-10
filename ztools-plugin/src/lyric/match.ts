/**
 * 候选挑选:从歌词库返回的多条搜索结果里挑出与手机推送曲目最匹配的一条。
 * 评分维度(与任务书一致):时长接近(±3s 优先)、标题相似、歌手相似。
 */
import type { LyricQuery } from './types'
import { normalizeText } from './clean-title'

export interface Candidate {
  title?: string
  artist?: string
  album?: string
  /** 与音乐库无关的附加字段由泛型承载 */
  durationMs?: number
  [key: string]: unknown
}

/** 最长公共子序列长度 —— 对"插入/删字"容错,比 Levenshtein 更适合歌名比对 */
function lcsLength(a: string, b: string): number {
  if (!a || !b) return 0
  let prev = new Array<number>(b.length + 1).fill(0)
  for (let i = 1; i <= a.length; i++) {
    const curr = [0]
    for (let j = 1; j <= b.length; j++) {
      curr[j] = a[i - 1] === b[j - 1] ? (prev[j - 1] ?? 0) + 1 : Math.max(prev[j] ?? 0, curr[j - 1] ?? 0)
    }
    prev = curr
  }
  return prev[b.length] ?? 0
}

/** Dice 系数相似度 0..1:2·LCS/(lenA+lenB);完全相等 1,一方是另一方超集也拿不到满分 */
export function similarity(a: string, b: string): number {
  const na = normalizeText(a)
  const nb = normalizeText(b)
  if (!na || !nb) return 0
  if (na === nb) return 1
  const lcs = lcsLength(na, nb)
  return Math.min(1, (2 * lcs) / (na.length + nb.length))
}

/**
 * 挑选最佳候选。
 * @returns 最佳候选;分数过低(标题都对不上)时返回 null
 */
export function pickBestCandidate<T extends Candidate>(candidates: T[], query: LyricQuery): T | null {
  let best: T | null = null
  let bestScore = -Infinity
  for (const candidate of candidates) {
    const score = scoreCandidate(candidate, query)
    if (score > bestScore) {
      bestScore = score
      best = candidate
    }
  }
  // 阈值:标题相似度是硬门槛(≥0.4 才可信),时长/歌手是加分项
  if (!best) return null
  const titleSim = similarity(best.title ?? '', query.title)
  return titleSim >= 0.4 ? best : null
}

/** 单条候选打分;导出仅为测试 */
export function scoreCandidate(candidate: Candidate, query: LyricQuery): number {
  let score = 0
  const titleSim = similarity(candidate.title ?? '', query.title)
  score += titleSim * 4
  if (titleSim === 1) score += 1.5

  const artistSim = similarity(candidate.artist ?? '', query.artist)
  score += artistSim * 2

  if (query.durationMs > 0 && typeof candidate.durationMs === 'number' && candidate.durationMs > 0) {
    const delta = Math.abs(candidate.durationMs - query.durationMs)
    if (delta <= 3000) score += 3
    else if (delta <= 5000) score += 1
    else if (delta > 15000) score -= 1 // 时长差太远,大概率不是同一版本
  }
  return score
}
