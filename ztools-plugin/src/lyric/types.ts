/**
 * 歌词模块公共类型(docs/protocol.md 之外的本插件内部契约)。
 */
import type { VersionTag } from './clean-title'

/** 一次歌词查询的输入,来自手机推送的 track 快照 */
export interface LyricQuery {
  title: string
  artist: string
  album: string
  durationMs: number
  /** 原始未清洗标题(查询变体回退用) */
  rawTitle?: string
  /** 原始未清洗歌手(查询变体回退用) */
  rawArtist?: string
  /** 完整歌手列表(未清洗拆分,供集合重叠打分;缺省时从 artist 现场拆) */
  allArtists?: string[]
  /** 从原始标题提取的版本标签(live/remix 等,打分对称惩罚用) */
  versionTags?: VersionTag[]
}

/** Provider 返回的统一结果;synced/plain 至少一项非空才算命中 */
export interface LyricResult {
  /** 带时间轴的 LRC 原文;无则 null */
  synced: string | null
  /** 纯文本歌词;无则 null */
  plain: string | null
  /** 翻译 LRC(可选,时间轴与主歌词对齐) */
  translation?: string | null
  /** 来源标识,如 lrclib / netease */
  source: string
}

/** 歌词数据源接口:实现方自行处理搜索→详情两步,失败时抛异常 */
export interface LyricProvider {
  /** 来源标识(写入 LyricResult.source) */
  name: string
  /**
   * 查询歌词。
   * @param query 查询参数(已清洗)
   * @param signal 取消信号:切歌时中止在途请求
   * @returns 命中返回结果;确认无歌词返回 null;网络/接口错误抛异常
   */
  search(query: LyricQuery, signal: AbortSignal): Promise<LyricResult | null>
}

/** 解析后的一行歌词;timeMs 已应用 [offset:] 标签,未应用用户手动偏移 */
export interface LyricsLine {
  timeMs: number
  text: string
}
