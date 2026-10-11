/**
 * synced 优先匹配策略测试:
 * - isUsableSynced / lrcToPlain(伪时间轴降级的两个纯函数);
 * - LyricsManager 两轮回退:先跨来源找有效时间轴,全部没有才退纯文本。
 */
import { describe, expect, it } from 'vitest'
import { isUsableSynced, lrcToPlain } from './parse-lrc'
import { LyricsManager } from './lyrics-manager'
import { createMemoryStorage } from './cache'
import type { LyricProvider, LyricResult } from './types'

const SYNCED = '[00:01.00]第一句\n[00:12.00]第二句\n[00:30.00]第三句'
const PLAIN = '第一句\n第二句'

describe('isUsableSynced', () => {
  it('accepts a real timeline', () => {
    expect(isUsableSynced(SYNCED)).toBe(true)
  })

  it('rejects plain text without any stamps', () => {
    expect(isUsableSynced(PLAIN)).toBe(false)
    expect(isUsableSynced('')).toBe(false)
  })

  it('rejects zero-only or collapsed timelines (unsynced shell)', () => {
    expect(isUsableSynced('[00:00.00]第一句\n[00:00.00]第二句')).toBe(false)
    expect(isUsableSynced('[00:01.00]第一句\n[00:02.00]第二句')).toBe(false) // 跨度 < 5s
    expect(isUsableSynced('[00:01.00]唯一一句')).toBe(false)
  })
})

describe('lrcToPlain', () => {
  it('strips time tags and metadata lines', () => {
    const text = lrcToPlain('[ar:周杰伦]\n[ti:晴天]\n[00:01.00]第一句\n[00:12.00]第二句')
    expect(text).toBe('第一句\n第二句')
  })

  it('keeps repeated lines that are not adjacent', () => {
    const text = lrcToPlain('[00:01.00]副歌\n[00:10.00]主歌\n[00:20.00]副歌')
    expect(text).toBe('副歌\n主歌\n副歌')
  })

  it('drops adjacent duplicates from multi-stamp lines', () => {
    const text = lrcToPlain('[00:01.00][00:05.00]重复句\n[00:10.00]下一句')
    expect(text).toBe('重复句\n下一句')
  })
})

function provider(name: string, result: LyricResult | null): LyricProvider {
  return { name, search: () => Promise.resolve(result) }
}

const TRACK = { id: 't1', title: '晴天', artist: '周杰伦', album: '叶惠美', durationMs: 269_000 }

describe('LyricsManager synced-first fallback', () => {
  it('returns the first provider result that has a usable timeline', async () => {
    const plainFirst = new LyricsManager({
      storage: createMemoryStorage(),
      providers: [
        provider('plain-src', { synced: null, plain: PLAIN, source: 'plain-src' }),
        provider('synced-src', { synced: SYNCED, plain: null, source: 'synced-src' })
      ]
    })
    const result = await plainFirst.fetchForTrack(TRACK, '')
    expect(result?.source).toBe('synced-src')
    expect(result?.synced).toBe(SYNCED)
  })

  it('degrades an unsynced shell to plain and keeps looking for a real timeline', async () => {
    const shell = '[00:00.00]第一句\n[00:00.00]第二句' // 网易云对无时间轴歌曲的空壳
    const manager = new LyricsManager({
      storage: createMemoryStorage(),
      providers: [
        provider('shell-src', { synced: shell, plain: null, source: 'shell-src' }),
        provider('synced-src', { synced: SYNCED, plain: null, source: 'synced-src' })
      ]
    })
    const result = await manager.fetchForTrack(TRACK, '')
    expect(result?.source).toBe('synced-src')
    expect(result?.synced).toBe(SYNCED)
  })

  it('falls back to the first plain result when no provider has a timeline', async () => {
    const manager = new LyricsManager({
      storage: createMemoryStorage(),
      providers: [
        // 只有时间戳没有文本:降级后连纯文本都没有,应被整体跳过
        provider('empty-shell-src', { synced: '[00:00.00]', plain: null, source: 'empty-shell-src' }),
        provider('plain-src', { synced: null, plain: PLAIN, source: 'plain-src' })
      ]
    })
    const result = await manager.fetchForTrack(TRACK, '')
    expect(result?.source).toBe('plain-src')
    expect(result?.plain).toBe(PLAIN)
    expect(result?.synced).toBeNull()
  })

  it('keeps the degraded plain form stable across cache hits', async () => {
    const storage = createMemoryStorage()
    const manager = new LyricsManager({
      storage,
      providers: [provider('shell-src', { synced: '[00:00.00]纯音乐,请欣赏', plain: null, source: 'shell-src' })]
    })
    const first = await manager.fetchForTrack(TRACK, '')
    const second = await manager.fetchForTrack(TRACK, '')
    expect(first?.synced).toBeNull()
    expect(first?.plain).toBe('纯音乐,请欣赏')
    expect(second).toEqual(first)
  })

  it('returns null and writes a negative cache when providers find nothing', async () => {
    const manager = new LyricsManager({
      storage: createMemoryStorage(),
      providers: [provider('empty-src', null)]
    })
    expect(await manager.fetchForTrack(TRACK, '')).toBeNull()
  })
})
