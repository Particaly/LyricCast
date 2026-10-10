/**
 * 歌词模块公共类型(docs/protocol.md 之外的本插件内部契约)。
 */

/** 一次歌词查询的输入,来自手机推送的 track 快照 */
export interface LyricQuery {
  title: string
  artist: string
  album: string
  durationMs: number
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
