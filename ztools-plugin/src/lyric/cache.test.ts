import { describe, expect, it } from 'vitest'
import { createMemoryStorage, LyricCache, cacheKey } from './cache'
import { OffsetStore } from './offsets'
import type { LyricQuery, LyricResult } from './types'

const query: LyricQuery = { title: '晴天', artist: '周杰伦', album: '叶惠美', durationMs: 269000 }
const result: LyricResult = { synced: '[00:01.00]hi', plain: null, translation: null, source: 'lrclib' }

describe('cacheKey', () => {
  it('is insensitive to case/whitespace and buckets duration by second', () => {
    const a = cacheKey({ title: ' 晴天 ', artist: '周杰伦', album: '', durationMs: 269400 })
    const b = cacheKey({ title: '晴天', artist: '周杰伦', album: 'other', durationMs: 269000 })
    expect(a).toBe(b)
  })
})

describe('LyricCache', () => {
  it('stores and returns results', () => {
    const cache = new LyricCache(createMemoryStorage())
    expect(cache.get(query)).toBeNull()
    cache.put(query, result)
    expect(cache.get(query)?.source).toBe('lrclib')
  })

  it('treats null as a negative entry that expires after 24h', () => {
    let now = 1_000_000
    const cache = new LyricCache(createMemoryStorage(), () => now)
    cache.put(query, null)
    expect(cache.isNegativeFresh(query)).toBe(true)
    now += 23 * 3600 * 1000
    expect(cache.isNegativeFresh(query)).toBe(true)
    now += 2 * 3600 * 1000
    expect(cache.isNegativeFresh(query)).toBe(false)
  })

  it('positive result overrides negative and vice versa', () => {
    const cache = new LyricCache(createMemoryStorage())
    cache.put(query, null)
    expect(cache.isNegativeFresh(query)).toBe(true)
    cache.put(query, result)
    expect(cache.isNegativeFresh(query)).toBe(false)
    expect(cache.get(query)).not.toBeNull()
  })
})

describe('OffsetStore', () => {
  it('track offset takes precedence over app offset', () => {
    const store = new OffsetStore(createMemoryStorage())
    store.add('app', 'com.netease.cloudmusic', 300)
    expect(store.effective('track-1', 'com.netease.cloudmusic')).toBe(300)
    store.add('track', 'track-1', -100)
    expect(store.effective('track-1', 'com.netease.cloudmusic')).toBe(-100)
  })

  it('accumulates deltas and zero clears the entry', () => {
    const store = new OffsetStore(createMemoryStorage())
    expect(store.add('track', 't', 100)).toBe(100)
    expect(store.add('track', 't', 500)).toBe(600)
    expect(store.add('track', 't', -600)).toBe(0)
    expect(store.effective('t', 'app')).toBe(0)
  })
})
