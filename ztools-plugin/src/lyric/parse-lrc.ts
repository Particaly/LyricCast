/**
 * LRC 解析:支持多时间戳行、[offset:] 全局偏移、毫秒 2~3 位;
 * 增强型逐字时间戳(<mm:ss.xx>)本期只剥离不渲染(标记 isEnhanced,待验证渲染需求)。
 */
import type { LyricsLine } from './types'

export interface ParsedLyrics {
  /** 按 timeMs 升序;含空文本行(间奏),调用方自行处理 */
  lines: LyricsLine[]
  /** [offset:] 标签原始值(毫秒);未提供为 0。已应用到 lines[].timeMs = 原始 − offset */
  offsetTagMs: number
  /** 检测到增强型逐字时间戳 */
  isEnhanced: boolean
}

const TIME_TAG = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g
const WORD_TAG = /<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g
const OFFSET_TAG = /^\s*\[offset:\s*([+-]?\d+)\s*\]/im

/**
 * 解析 LRC 文本。非法输入(空串/无时间戳)返回 lines=[],调用方按"解析失败"处理。
 *
 * @param lrc LRC 原文
 */
export function parseLrc(lrc: string): ParsedLyrics {
  const result: ParsedLyrics = { lines: [], offsetTagMs: 0, isEnhanced: false }
  if (!lrc) return result

  const offsetMatch = OFFSET_TAG.exec(lrc)
  if (offsetMatch) result.offsetTagMs = parseInt(offsetMatch[1], 10)

  const seen = new Set<string>()
  for (const rawLine of lrc.split(/\r?\n/)) {
    const line = rawLine.replace(WORD_TAG, '')
    if (line !== rawLine) result.isEnhanced = true

    TIME_TAG.lastIndex = 0
    const stamps: number[] = []
    let match: RegExpExecArray | null
    let lastTagEnd = 0
    while ((match = TIME_TAG.exec(line)) !== null) {
      const minutes = parseInt(match[1], 10)
      const seconds = parseInt(match[2], 10)
      const fractionRaw = match[3] ?? '0'
      // 两位=百分之一秒,三位=毫秒
      const fraction =
        fractionRaw.length === 1
          ? parseInt(fractionRaw, 10) * 100
          : fractionRaw.length === 2
            ? parseInt(fractionRaw, 10) * 10
            : parseInt(fractionRaw, 10)
      stamps.push(minutes * 60_000 + seconds * 1_000 + fraction)
      lastTagEnd = TIME_TAG.lastIndex
    }
    if (stamps.length === 0) continue // 元数据行/空行

    const text = line.slice(lastTagEnd).trim()
    for (const raw of stamps) {
      const timeMs = raw - result.offsetTagMs
      const key = `${timeMs}|${text}`
      if (seen.has(key)) continue
      seen.add(key)
      result.lines.push({ timeMs, text })
    }
  }
  result.lines.sort((a, b) => a.timeMs - b.timeMs)
  return result
}

/**
 * 二分查找"正在唱的一行":最后一个 timeMs <= position 的行;position 早于第一行时返回 -1。
 * @param lines 必须已按 timeMs 升序(parseLrc 保证)
 */
export function findActiveLine(lines: LyricsLine[], positionMs: number): number {
  let low = 0
  let high = lines.length - 1
  let found = -1
  while (low <= high) {
    const mid = (low + high) >> 1
    if ((lines[mid]?.timeMs ?? 0) <= positionMs) {
      found = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return found
}
