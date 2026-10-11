/**
 * Better Lyrics provider 测试:以**真实公共实例录制的 TTML 样例**做转换层单测
 * (2026-10-11 实测 https://lyrics-api.boidu.dev/getLyrics?s=Never+Gonna+Give+You+Up&a=Rick+Astley;
 * 裁剪至前 2 个 <p>)。连通性与形态已人工验证:200 {ttml} / 404 {error} / 401 需 API key;
 * 测试走 fixture,不做真实网络。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { betterLyricsProvider, parseBetterLyricsExport, parseTtmlTime, ttmlToLrc } from './betterlyrics'
import { parseLrc } from '../parse-lrc'
import { settings } from '../../settings'

/** 录制的 TTML(截取 tt 头与前两行;itunes:timing="Word" 音节级) */
const TTML_SAMPLE =
  '<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" ' +
  'xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word" xml:lang="en"><body><div>' +
  '<p begin="18.893" end="22.228" itunes:key="L1" ttm:agent="v1">' +
  '<span begin="18.893" end="19.255">We\'re</span> <span begin="19.255" end="19.538">no</span> ' +
  '<span begin="19.538" end="20.071">strangers</span> <span begin="20.071" end="20.338">to</span> ' +
  '<span begin="20.338" end="22.228">love</span></p>' +
  '<p begin="22.826" end="27.010" itunes:key="L2" ttm:agent="v1">' +
  '<span begin="22.826" end="23.231">You</span> <span begin="23.231" end="23.482">know</span> ' +
  '<span begin="23.482" end="23.765">the</span> <span begin="23.765" end="24.489">rules</span> ' +
  '<span begin="24.581" end="24.831">and</span> <span begin="24.831" end="25.349">so</span> ' +
  '<span begin="25.349" end="25.615">do</span> <span begin="25.615" end="27.010">I</span></p>' +
  '</div></body></tt>'

describe('parseTtmlTime', () => {
  it('支持秒.毫秒与 mm:ss.mmm 两种写法', () => {
    expect(parseTtmlTime('18.893')).toBe(18_893)
    expect(parseTtmlTime('0:18.893')).toBe(18_893)
    expect(parseTtmlTime('1:02.500')).toBe(62_500)
    expect(parseTtmlTime('1:00:02.5')).toBe(3_602_500)
  })
  it('空串/非法输入返回 NaN', () => {
    expect(Number.isNaN(parseTtmlTime(''))).toBe(true)
    expect(Number.isNaN(parseTtmlTime('abc'))).toBe(true)
  })
})

describe('ttmlToLrc', () => {
  it('逐字 span 转增强 LRC(行戳 + 词戳)', () => {
    const lrc = ttmlToLrc(TTML_SAMPLE) ?? ''
    const lines = lrc.split('\n')
    expect(lines[0]).toBe("[00:18.89]<00:18.89>We're <00:19.25>no <00:19.53>strangers <00:20.07>to <00:20.33>love")
    expect(lines[1]).toBe('[00:22.82]<00:22.82>You <00:23.23>know <00:23.48>the <00:23.76>rules <00:24.58>and <00:24.83>so <00:25.34>do <00:25.61>I')
  })

  it('转换结果可被 parseLrc 解析(isEnhanced,词标签剥离后为整句)', () => {
    const parsed = parseLrc(ttmlToLrc(TTML_SAMPLE) ?? '')
    expect(parsed.isEnhanced).toBe(true)
    // 厘秒粒度:18.893s → [00:18.89] → 回读 18890ms(±10ms 内)
    expect(parsed.lines[0]?.timeMs).toBe(18_890)
    expect(parsed.lines[0]?.text).toBe("We're no strangers to love")
    expect(parsed.lines[1]?.text).toBe('You know the rules and so do I')
  })

  it('无 span 的 <p> 退化为行级文本', () => {
    const lrc = ttmlToLrc('<tt><body><div><p begin="5.000" end="8.000">纯文本一行</p></div></body></tt>')
    expect(lrc).toBe('[00:05.00] 纯文本一行')
  })

  it('无 begin 的 <p> 与纯元数据返回 null', () => {
    expect(ttmlToLrc('<tt><head><metadata>stuff</metadata></head></tt>')).toBeNull()
    expect(ttmlToLrc('<tt><body><div><p>没有时间戳</p></div></body></tt>')).toBeNull()
    expect(ttmlToLrc('')).toBeNull()
  })
})

describe('betterLyricsProvider', () => {
  it('声明来源名与 fetch 状态映射(不发真实网络)', async () => {
    expect(betterLyricsProvider.name).toBe('betterlyrics')
    expect(betterLyricsProvider.searchCandidates).toBeUndefined()
    const query = { title: '晴天', artist: '周杰伦', album: '', durationMs: 269_000 }
    // 沙箱断网 → fetchJson 抛错 → error 结果(编排层不写负缓存)
    const outcome = await betterLyricsProvider.search(query, new AbortController().signal).catch((err) => {
      expect(String(err)).toContain('fetch failed')
      return null
    })
    void outcome
  })
})

/** 与浏览器插件导出格式一致(内容脱敏)的样例 */
const EXPORT_SAMPLE =
  '{"version":1,"keyId":"d7dc55060995dc76fbef2fee5f8d300eb5c05d388d47941b0bca6d024e1d6639",' +
  '"publicKey":{"crv":"P-256","kty":"EC","x":"x-x","y":"y-y"},"privateKey":{"crv":"P-256","d":"d-d","kty":"EC"},"exportedAt":1791710665270}'

describe('parseBetterLyricsExport', () => {
  it('识别导出 JSON 并提取 keyId', () => {
    expect(parseBetterLyricsExport(EXPORT_SAMPLE)).toBe(
      'd7dc55060995dc76fbef2fee5f8d300eb5c05d388d47941b0bca6d024e1d6639'
    )
  })

  it('纯 key 字符串/非法 JSON/缺字段返回 null', () => {
    expect(parseBetterLyricsExport('my-plain-api-key')).toBeNull()
    expect(parseBetterLyricsExport('{not json')).toBeNull()
    expect(parseBetterLyricsExport('{"version":1,"keyId":"tooshort"}')).toBeNull()
    expect(parseBetterLyricsExport('{"version":1,"keyId":"' + 'a'.repeat(64) + '"}')).toBeNull() // 无 privateKey
    expect(parseBetterLyricsExport('')).toBeNull()
  })
})

describe('X-API-Key 请求头(任务:导入密钥)', () => {
  const QUERY = { title: '晴天', artist: '周杰伦', album: '', durationMs: 269_000 }

  afterEach(() => {
    vi.unstubAllGlobals()
    settings.betterLyricsApiKey = ''
  })

  async function captureHeaders(): Promise<Record<string, string> | null> {
    let captured: Record<string, string> | null = null
    vi.stubGlobal('fetch', vi.fn(async (_url, init) => {
      captured = Object.fromEntries(new Headers(init?.headers).entries()) as Record<string, string>
      return new Response(JSON.stringify({ ttml: TTML_SAMPLE }), { status: 200 })
    }))
    await betterLyricsProvider.search(QUERY, new AbortController().signal)
    return captured
  }

  it('设置密钥后请求带 X-API-Key', async () => {
    settings.betterLyricsApiKey = 'my-test-key-123'
    const headers = await captureHeaders()
    expect(headers?.['x-api-key']).toBe('my-test-key-123')
  })

  it('未设置密钥时不带头(公共实例缓存命中也免 key)', async () => {
    settings.betterLyricsApiKey = ''
    const headers = await captureHeaders()
    expect(headers?.['x-api-key']).toBeUndefined()
  })

  it('带无效 key 时 401 仍按未命中(不抛错)', async () => {
    settings.betterLyricsApiKey = 'invalid-key'
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'Invalid API key' }), { status: 401 })))
    const outcome = await betterLyricsProvider.search(QUERY, new AbortController().signal)
    expect(outcome.status).toBe('not_found')
  })
})
