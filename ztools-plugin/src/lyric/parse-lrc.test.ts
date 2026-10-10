import { describe, expect, it } from 'vitest'
import { findActiveLine, parseLrc } from './parse-lrc'

describe('parseLrc', () => {
  it('parses two-digit and three-digit millisecond stamps', () => {
    const parsed = parseLrc('[00:12.30]two-digit\n[01:05.123]three-digit')
    expect(parsed.lines.map((l) => l.timeMs)).toEqual([12300, 65123])
    expect(parsed.lines.map((l) => l.text)).toEqual(['two-digit', 'three-digit'])
  })

  it('supports multiple timestamps on one line', () => {
    const parsed = parseLrc('[00:10.00][01:20.50]chorus')
    expect(parsed.lines).toEqual([
      { timeMs: 10000, text: 'chorus' },
      { timeMs: 80500, text: 'chorus' }
    ])
  })

  it('applies [offset:] tag (positive shifts lyrics earlier)', () => {
    const parsed = parseLrc('[offset:+500]\n[00:10.00]line')
    expect(parsed.offsetTagMs).toBe(500)
    expect(parsed.lines[0]?.timeMs).toBe(9500)
    const negative = parseLrc('[offset:-200]\n[00:10.00]line')
    expect(negative.lines[0]?.timeMs).toBe(10200)
  })

  it('strips enhanced word timestamps and flags them', () => {
    const parsed = parseLrc('[00:12.00]<00:12.00>你<00:12.50>好')
    expect(parsed.isEnhanced).toBe(true)
    expect(parsed.lines[0]?.text).toBe('你好')
  })

  it('ignores metadata lines and sorts output', () => {
    const parsed = parseLrc('[ar:周杰伦]\n[ti:晴天]\n[00:20.00]b\n[00:10.00]a\n')
    expect(parsed.lines.map((l) => l.text)).toEqual(['a', 'b'])
  })

  it('dedupes identical timestamp+text pairs', () => {
    const parsed = parseLrc('[00:10.00]a\n[00:10.00]a')
    expect(parsed.lines.length).toBe(1)
  })

  it('returns empty lines for garbage input', () => {
    expect(parseLrc('').lines).toEqual([])
    expect(parseLrc('plain text only').lines).toEqual([])
  })
})

describe('findActiveLine', () => {
  const lines = parseLrc('[00:10.00]a\n[00:20.00]b\n[00:30.00]c').lines

  it('returns -1 before the first line', () => {
    expect(findActiveLine(lines, 0)).toBe(-1)
    expect(findActiveLine(lines, 9999)).toBe(-1)
  })

  it('returns the last line whose time <= position', () => {
    expect(findActiveLine(lines, 10000)).toBe(0)
    expect(findActiveLine(lines, 10001)).toBe(0)
    expect(findActiveLine(lines, 20000)).toBe(1)
    expect(findActiveLine(lines, 99999)).toBe(2)
  })
})
