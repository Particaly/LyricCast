/**
 * 桌面悬浮歌词的状态容器(仅悬浮窗页面使用)。
 *
 * 主窗口通过 executeJavaScript 调用 applyDesktopState 推送状态;
 * 本窗口的按钮动作进消息队列,由主窗口轮询 drainDesktopMessages 消费。
 * 故意与主窗口的 store 隔离:悬浮窗是哑渲染器,拿不到 preload 服务。
 */
import { reactive } from 'vue'
import type { DesktopPush } from './desktop-bridge'

export type DesktopStatus = DesktopPush['status']

export interface DesktopState {
  /** 主窗口轮询判定:光标是否落在悬浮窗内(拖拽区收不到鼠标事件,悬停 UI 由此驱动) */
  hovered: boolean
  style: {
    fontSize: number
    theme: 'auto' | 'light' | 'dark'
    translation: boolean
    /** 锁定:禁止拖动、工具栏只留解锁按钮 */
    locked: boolean
    /** 背景不透明度(%) */
    opacity: number
    /** 胶囊宽度(px) */
    width: number
    /** 胶囊高度(px) */
    height: number
  }
  status: DesktopStatus
  track: { title: string; artist: string } | null
  line: string | null
  next: string | null
  translation: string | null
  progress: {
    atMs: number
    positionMs: number
    speed: number
    playing: boolean
    durationMs: number
  } | null
}

export const desktopState = reactive<DesktopState>({
  hovered: false,
  style: { fontSize: 26, theme: 'auto', translation: true, locked: false, opacity: 90, width: 870, height: 138 },
  status: 'noplayback',
  track: null,
  line: null,
  next: null,
  translation: null,
  progress: null
})

/** 主窗口推送入口(由 desktop-main.ts 挂到 window.__applyDesktopState) */
export function applyDesktopState(payload: DesktopPush): void {
  if (!payload || typeof payload !== 'object') return
  desktopState.hovered = !!payload.hovered
  if (payload.style) Object.assign(desktopState.style, payload.style)
  desktopState.status = payload.status ?? 'noplayback'
  desktopState.track = payload.track ?? null
  desktopState.line = payload.line ?? null
  desktopState.next = payload.next ?? null
  desktopState.translation = payload.translation ?? null
  desktopState.progress = payload.progress ?? null
}

/** 悬浮窗缩放方位:n/s/e/w 为四边,nw/ne/sw/se 为四角 */
export type DesktopResizeDir = 'n' | 's' | 'e' | 'w' | 'nw' | 'ne' | 'sw' | 'se'

/** 悬浮窗工具栏动作消息;delta 仅 font,value 仅 opacity,size/dir 仅 resize 使用 */
export interface DesktopActionMessage {
  type: 'font' | 'theme' | 'close' | 'translation' | 'lock' | 'opacity' | 'resize'
  delta?: number
  value?: number
  /** resize:目标胶囊尺寸(px,窗口 = 胶囊 + 四周各 5px 留白) */
  size?: { width: number; height: number }
  /** resize:被拖拽的边缘/角方向,主窗口据此移动窗口保持对侧边固定 */
  dir?: DesktopResizeDir
}

const messageQueue: DesktopActionMessage[] = []

/** 主窗口轮询取走积压的按钮消息(取走即清空) */
export function drainDesktopMessages(): DesktopActionMessage[] {
  return messageQueue.splice(0)
}

/** 悬浮窗工具栏动作统一入口,由主窗口轮询消费后落回 settings */
export function desktopAction(message: DesktopActionMessage): void {
  messageQueue.push(message)
}
