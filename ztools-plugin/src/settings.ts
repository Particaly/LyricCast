/**
 * 用户设置:单一 reactive 对象 + 整块 JSON 持久化(ztools.dbStorage)。
 *
 * 与 port/token(preload 侧的 lanlyrics.config)分离:那边是"服务配置",
 * 这边是"展示偏好"。持久化粒度为整个对象,键值量级小,避免散落十几个 key。
 */
import { reactive } from 'vue'
import { createMemoryStorage, type KVStorage } from './lyric/cache'

export type DesktopTheme = 'auto' | 'light' | 'dark'

export interface Settings {
  /** 桌面悬浮歌词开关(独立置顶透明窗口) */
  desktopEnabled: boolean
  /** 桌面歌词主行字号(px) */
  desktopFontSize: number
  /** 桌面歌词配色:跟随系统 / 浅底深字 / 深底浅字 */
  desktopTheme: DesktopTheme
  /** 桌面歌词是否在当前行下方显示翻译(无翻译时显示下一句) */
  desktopTranslation: boolean
  /** 桌面歌词是否锁定:禁止拖动、隐藏悬浮工具栏(仅留解锁按钮),防误触 */
  desktopLocked: boolean
  /** 桌面歌词背景不透明度(%,0~100;0 为全透明,仅显示文字与进度) */
  desktopOpacity: number
  /** 桌面歌词胶囊宽度(px;悬浮窗宽 = 胶囊宽 + 左右各 5px 透明留白) */
  desktopWidth: number
  /** 桌面歌词胶囊高度(px;悬浮窗高 = 胶囊高 + 上下各 5px 透明留白) */
  desktopHeight: number
  /** 主界面滚动歌词字号(px) */
  mainFontSize: number
  /** 主界面歌词是否显示翻译 */
  translation: boolean
}

export const SETTINGS_KEY = 'lanlyrics.settings.v1'
const LEGACY_FONT_KEY = 'lyric.fontSize'

export const DESKTOP_FONT_RANGE = { min: 16, max: 42 }
export const DESKTOP_OPACITY_RANGE = { min: 0, max: 100 }
/** 胶囊尺寸范围;下限保证顶部工具栏放得下,悬浮窗是哑渲染器,范围在 DesktopLyrics.vue 内有同值副本 */
export const DESKTOP_SIZE_RANGE = { minWidth: 360, maxWidth: 1600, minHeight: 96, maxHeight: 400 }
export const MAIN_FONT_RANGE = { min: 12, max: 30 }

const DEFAULTS: Settings = {
  desktopEnabled: false,
  desktopFontSize: 26,
  desktopTheme: 'auto',
  desktopTranslation: true,
  desktopLocked: false,
  desktopOpacity: 90,
  desktopWidth: 870,
  desktopHeight: 138,
  mainFontSize: 16,
  translation: true
}

function storage(): KVStorage {
  try {
    if (window.ztools?.dbStorage) return window.ztools.dbStorage
  } catch (_) { /* 无 ztools 环境(纯浏览器调试)退化为内存 */ }
  return createMemoryStorage()
}

/** 校验并合并持久化值:字段缺失/越界/类型不对时回落默认,保证结构永远完整 */
function sanitize(raw: unknown): Settings {
  const input = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>
  const clamp = (value: unknown, min: number, max: number, fallback: number): number => {
    const n = typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : NaN
    return Number.isNaN(n) ? fallback : Math.min(max, Math.max(min, n))
  }
  return {
    desktopEnabled: typeof input.desktopEnabled === 'boolean' ? input.desktopEnabled : DEFAULTS.desktopEnabled,
    desktopFontSize: clamp(input.desktopFontSize, DESKTOP_FONT_RANGE.min, DESKTOP_FONT_RANGE.max, DEFAULTS.desktopFontSize),
    desktopTheme:
      input.desktopTheme === 'light' || input.desktopTheme === 'dark' || input.desktopTheme === 'auto'
        ? input.desktopTheme
        : DEFAULTS.desktopTheme,
    desktopTranslation: typeof input.desktopTranslation === 'boolean' ? input.desktopTranslation : DEFAULTS.desktopTranslation,
    desktopLocked: typeof input.desktopLocked === 'boolean' ? input.desktopLocked : DEFAULTS.desktopLocked,
    desktopOpacity: clamp(input.desktopOpacity, DESKTOP_OPACITY_RANGE.min, DESKTOP_OPACITY_RANGE.max, DEFAULTS.desktopOpacity),
    desktopWidth: clamp(input.desktopWidth, DESKTOP_SIZE_RANGE.minWidth, DESKTOP_SIZE_RANGE.maxWidth, DEFAULTS.desktopWidth),
    desktopHeight: clamp(input.desktopHeight, DESKTOP_SIZE_RANGE.minHeight, DESKTOP_SIZE_RANGE.maxHeight, DEFAULTS.desktopHeight),
    mainFontSize: clamp(input.mainFontSize, MAIN_FONT_RANGE.min, MAIN_FONT_RANGE.max, DEFAULTS.mainFontSize),
    translation: typeof input.translation === 'boolean' ? input.translation : DEFAULTS.translation
  }
}

export const settings = reactive<Settings>({ ...DEFAULTS })

/** 从持久化载入(应用启动时调用一次);旧版散写的 lyric.fontSize 迁移为 mainFontSize */
export function loadSettings(): void {
  const store = storage()
  let raw: unknown = null
  try {
    raw = store.getItem(SETTINGS_KEY)
  } catch (_) { /* 存储损坏按默认 */ }
  if (raw === null || raw === undefined) {
    try {
      const legacy = store.getItem(LEGACY_FONT_KEY)
      if (typeof legacy === 'number') settings.mainFontSize = legacy
    } catch (_) { /* 忽略 */ }
    return
  }
  Object.assign(settings, sanitize(typeof raw === 'string' ? safeParse(raw) : raw))
}

/** 写入任意字段子集并持久化;越界值裁剪到合法区间 */
export function updateSettings(patch: Partial<Settings>): void {
  Object.assign(settings, sanitize({ ...settings, ...patch }))
  try {
    storage().setItem(SETTINGS_KEY, JSON.stringify(settings))
  } catch (_) { /* 持久化失败不影响本次会话 */ }
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch (_) {
    return null
  }
}
