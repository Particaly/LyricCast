import { describe, expect, it } from 'vitest'
import { pickBestCandidate, scoreCandidate, similarity } from './match'
import type { LyricQuery } from './types'

const query: LyricQuery = {
  title: '晴天',
  artist: '周杰伦',
  album: '叶惠美',
  durationMs: 269000
}

describe('similarity', () => {
  it('is 1 for identical normalized text', () => {
    expect(similarity('晴天', ' 晴天 ')).toBe(1)
  })
  it('is 0 for empty input', () => {
    expect(similarity('', '晴天')).toBe(0)
  })
  it('is partial for overlap', () => {
    // '晴天live'(6 字符) vs '晴天'(2):Dice = 2·2/8 = 0.5,超集拿不到满分
    const value = similarity('晴天 Live', '晴天')
    expect(value).toBeGreaterThanOrEqual(0.5)
    expect(value).toBeLessThan(1)
  })
})

describe('scoreCandidate', () => {
  it('rewards duration within ±3s heavily', () => {
    const close = scoreCandidate({ title: '晴天', artist: '周杰伦', durationMs: 270000 }, query)
    const far = scoreCandidate({ title: '晴天', artist: '周杰伦', durationMs: 300000 }, query)
    expect(close).toBeGreaterThan(far)
  })

  it('punishes very different durations', () => {
    const exactTitleButFarDuration = scoreCandidate({ title: '晴天', durationMs: 90_000 }, query)
    expect(exactTitleButFarDuration).toBeLessThan(
      scoreCandidate({ title: '晴天', artist: '周杰伦', durationMs: 269500 }, query)
    )
  })
})

describe('pickBestCandidate', () => {
  it('picks the matching candidate among noise', () => {
    const best = pickBestCandidate(
      [
        { title: '晴天 (Live)', artist: '周杰伦', durationMs: 320000 },
        { title: '晴天', artist: '周杰伦', durationMs: 269500 },
        { title: '夜曲', artist: '周杰伦', durationMs: 226000 }
      ],
      query
    )
    expect(best?.durationMs).toBe(269500)
  })

  it('returns null when no title is similar enough', () => {
    const best = pickBestCandidate([{ title: '完全无关的歌', artist: '别人', durationMs: 269000 }], query)
    expect(best).toBeNull()
  })

  it('returns null for empty list', () => {
    expect(pickBestCandidate([], query)).toBeNull()
  })
})
