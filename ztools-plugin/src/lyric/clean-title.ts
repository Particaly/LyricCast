/**
 * 标题/歌手清洗:国内音乐 App 的媒体元数据经常把附注、版本号、"歌名 - 歌手"混进 title,
 * 直接拿去搜歌词命中率很低。这里做保守清洗 —— 只删确定是噪音的部分,宁可少删不可误删。
 */

/** 括号/方括号附注中几乎一定是版本噪音的关键词(不区分大小写) */
const JUNK_KEYWORDS = [
  'live',
  'feat',
  'ft\\.',
  'featuring',
  'remaster',
  'remastered',
  'deluxe',
  'acoustic',
  'cover',
  'version',
  'ver\\.',
  'edit',
  'remix',
  'mix',
  'official',
  'video',
  'audio',
  'mv',
  'instrumental',
  'karaoke',
  '伴奏',
  '现场',
  '翻唱',
  '翻自',
  '纯音乐',
  '重制版',
  '升级版',
  '录音室版',
  '加长版',
  '完整版',
  '修复版',
  '钢琴版',
  '吉他版'
]

const JUNK_IN_BRACKETS = new RegExp(
  `[（(\\[【][^】\\])}]*?(?:${JUNK_KEYWORDS.join('|')})[^】\\])}]*?[）)\\]】]`,
  'i'
)

const TRAILING_DASH_JUNK = new RegExp(
  `[-–—]\\s*(?:${JUNK_KEYWORDS.join('|')}).*$`,
  'i'
)

/** 归一化:小写、去空白与标点,用于比较与缓存键 */
export function normalizeText(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[\s'"“”‘’,、.。!！?？·~～\-_()（）\[\]【】]/g, '')
    .trim()
}

/**
 * 清洗歌名:
 * 1. 反复剥离含噪音关键词的括号附注(如 "(Live)"、"【Remastered 2011】");
 * 2. 剥离 " - Remastered" 类破折号后缀;
 * 3. 若已知歌手且 title 形如 "歌名 - 歌手"(右侧包含歌手名),去掉右侧;
 * 4. 折叠空白。
 *
 * @param raw 原始 title
 * @param artist 已知歌手(可为空;空时不做第 3 步,避免误删)
 */
export function cleanTitle(raw: string, artist = ''): string {
  let title = (raw || '').trim()
  // 括号噪音可能多层嵌套使用,最多剥 3 层
  for (let i = 0; i < 3; i++) {
    const next = title.replace(JUNK_IN_BRACKETS, '')
    if (next === title) break
    title = next
  }
  title = title.replace(TRAILING_DASH_JUNK, '')

  const artistNorm = normalizeText(artist)
  if (artistNorm) {
    const dashIndex = title.lastIndexOf(' - ')
    if (dashIndex > 0) {
      const right = normalizeText(title.slice(dashIndex + 3))
      const left = title.slice(0, dashIndex)
      // 右侧像歌手(包含歌手名且不太长)才删,防止误删"歌名 - 副标题"
      if (right && right.length <= 40 && right.includes(artistNorm)) {
        title = left
      }
    }
  }
  return title.replace(/\s{2,}/g, ' ').trim()
}

/**
 * 清洗歌手:去掉 "A feat. B" / "A、B" / "A / B" 中的合作者,只留主歌手,
 * 提高歌词库(普遍按主歌手索引)的命中率。
 */
export function cleanArtist(raw: string): string {
  let artist = (raw || '').trim()
  artist = artist.replace(/\s*(?:feat\.?|featuring|ft\.?|with)\s+.*$/i, '')
  artist = artist.split(/\s*[、/;；,]\s*/)[0] ?? artist
  return artist.trim()
}
