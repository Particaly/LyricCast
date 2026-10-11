<script setup lang="ts">
/**
 * 桌面悬浮歌词胶囊(独立透明窗口的整页 UI)。
 * - 状态来自 desktop-state(主窗口推送),本地不做任何网络/服务调用;
 * - 歌词展示:ok 状态用推送的整首行文本自建统一行高的滚动轨道,裁剪窗口保持
 *   旧版 2 行视图(开翻译 = 当前句 + 译文;关翻译 = 当前句 + 下一句),窗口在
 *   胶囊内垂直居中;行推进经 CSS transform 过渡平滑上滑一行(与首页歌词同款
 *   手感),seek/切歌等跨行跳变直接落位;文本水平对齐(左/居中)可设置;
 *   其余状态回落主行/副行占位文案;
 * - 进度条按推送锚点 + speed 本地插值(rAF),推送频率无需高;
 * - 整个胶囊为拖动区(-webkit-app-region),顶部居中悬停浮现快捷工具栏
 *   (字号±/翻译/锁定/背景不透明度/配色/关闭);锁定后禁止拖动、工具栏只留解锁;
 * - 悬停 UI(描边/工具栏/缩放区)由主窗口轮询光标判定的 hovered 标志 js 驱动
 *   (胶囊是拖拽区,CSS :hover 不触发);描边在锁定态不显示;
 * - 悬停后胶囊边缘/四角变为缩放区(8 方位手势,对侧边固定),拖动调整宽高:
 *   位移按 screenX/Y 屏幕坐标度量(w/n 方向窗口自身在移动,clientX 位移 = 手势
 *   位移 + 窗口位移,正反馈会让胶囊越拖越抖);预览期间胶囊锚定到不动的对侧边
 *   (居中布局会随预览尺寸来回游移),本地即时预览并 ~33ms 节流回传主窗口
 *   updateSettings + setBounds,拖动中忽略推送值回弹,松手提交终值;
 * - 缩放把手可见:光标贴近对应边缘/角时点亮指示条,拖动中持续点亮;
 * - 背景不透明度拖动滑杆时本地即时预览,松手经主窗口持久化后再接管;0 为纯透明歌词
 *   模式,胶囊/工具栏底色与投影全部隐藏,仅留文字与进度;
 * - 主题 auto 跟随系统深浅色,light/dark 由设置强制。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { desktopAction, desktopState, type DesktopResizeDir } from '../desktop-state'

const systemDark = ref(true)
let media: MediaQueryList | null = null

function onSchemeChange(event: MediaQueryListEvent) {
  systemDark.value = event.matches
}

onMounted(() => {
  media = window.matchMedia('(prefers-color-scheme: dark)')
  systemDark.value = media.matches
  media.addEventListener?.('change', onSchemeChange)
})

onUnmounted(() => {
  media?.removeEventListener?.('change', onSchemeChange)
})

const isDark = computed(() => {
  const theme = desktopState.style.theme
  if (theme === 'dark') return true
  if (theme === 'light') return false
  return systemDark.value
})

const themeLabel = computed(() =>
  desktopState.style.theme === 'auto' ? '◐' : desktopState.style.theme === 'dark' ? '☾' : '☀'
)

// ===== 进度本地插值:锚点 + speed,playing 时按墙钟推进 =====
const progressPct = ref(0)
let rafId = 0
let lastUpdate = 0

function tick(now: number) {
  rafId = requestAnimationFrame(tick)
  if (now - lastUpdate < 100) return // 进度条 10fps 足够,省电
  lastUpdate = now
  const progress = desktopState.progress
  const durationMs = progress?.durationMs ?? 0
  if (!progress || durationMs <= 0) {
    progressPct.value = 0
    return
  }
  const elapsed = progress.playing ? Math.max(0, Date.now() - progress.atMs) : 0
  const position = Math.min(durationMs, Math.max(0, progress.positionMs + elapsed * progress.speed))
  progressPct.value = Math.min(100, (position / durationMs) * 100)
}
rafId = requestAnimationFrame(tick)
onUnmounted(() => cancelAnimationFrame(rafId))

// ===== 展示内容:状态 → 主行/副行占位文案(ok 状态由下方歌词轨道接管) =====
const mainText = computed(() => {
  switch (desktopState.status) {
    case 'ok':
      // 行数据正常时走歌词轨道;异常空数据兜底为音符占位
      return '♪'
    case 'loading':
      return '歌词搜索中…'
    case 'nolyrics':
      return '暂无歌词'
    case 'offline':
      return '连接已断开'
    default:
      return '等待播放…'
  }
})

const trackLine = computed(() => {
  const track = desktopState.track
  if (!track) return ''
  return [track.title, track.artist].filter(Boolean).join(' - ')
})

const subText = computed(() => {
  switch (desktopState.status) {
    case 'ok':
    case 'loading':
    case 'nolyrics':
      return trackLine.value
    case 'offline':
      return '重连后自动恢复'
    default:
      return '手机开启 LAN Push 后自动同步'
  }
})

const dimMain = computed(() => desktopState.status !== 'ok')
const fontSize = computed(() => desktopState.style.fontSize)
const locked = computed(() => !!desktopState.style.locked)
const translationOn = computed(() => !!desktopState.style.translation)
/** 主窗口轮询光标判定:光标在悬浮窗内(拖拽区收不到鼠标事件,CSS :hover 不可用) */
const hovered = computed(() => !!desktopState.hovered)

// ===== 歌词轨道:整首行文本 + 平行译文,统一行高,2 行窗口内平滑滚动 =====
/** 轨道是否接管展示:仅 ok 且携带行数据;其余状态回落主行/副行占位 */
const hasTrack = computed(() => desktopState.status === 'ok' && desktopState.lines.length > 0)

/** 当前行下标:非 ok 状态统一按 -1 处理(占位态不渲染轨道,仅保持偏移公式一致) */
const activeIndex = computed(() => (desktopState.status === 'ok' ? desktopState.activeIndex : -1))

/** 译文行展开高度:与 .trans.open 的 CSS 高度保持同源,供行高计算使用 */
const transHeight = computed(() => Math.ceil(Math.max(12, fontSize.value * 0.5) * 1.3))

/** 轨道统一行高 = 主行盒高(fs×1.22)+ 开翻译时的译文槽;
 * 全部行同高,当前行放大与译文展开都收在行内,不影响定位公式 */
const rowHeight = computed(() =>
  Math.ceil(fontSize.value * 1.22) + (translationOn.value ? transHeight.value : 0)
)

/** 当前行译文:翻译开关开启且有对应译文时展开在当前行下方(仅当前行) */
const activeTranslation = computed(() => {
  if (!translationOn.value) return ''
  const index = activeIndex.value
  if (index < 0) return ''
  return desktopState.translations[index] ?? ''
})

// 裁剪窗口保持旧版 2 行视图:开翻译 = 当前句 + 译文(1 个行高,译文槽收在行内),
// 关翻译 = 当前句 + 下一句(2 个行高);窗口在胶囊内垂直居中(与旧版几何一致),
// 轨道 offset = -index×行高,行推进经 CSS transform 过渡上滑一行,无需测量视口
const windowHeight = computed(() => (translationOn.value ? rowHeight.value : rowHeight.value * 2))

/** 轨道定位用的行下标:尚未唱到第一句时按 0 处理,第一行停在窗口主行位,进场不突兀 */
const trackIndex = computed(() => Math.max(0, activeIndex.value))

/** 文本水平对齐:左对齐(默认,旧版观感)/ 居中 */
const alignMode = computed(() => (desktopState.style.align === 'center' ? 'center' : 'left'))

const containerOffset = ref(0)
const animate = ref(true)

watch(
  [trackIndex, rowHeight, () => desktopState.linesVersion],
  ([index, rowH, version], previous) => {
    const previousIndex = previous ? previous[0] : -2
    // 三类直接落位(不播滚动动画):首次到位;行高变化(字号/翻译开关调整,过渡只会拖尾);
    // linesVersion 变化 = 歌词整体重建(切歌/重新匹配)。行推进 |Δ|≤3 播滚动动画
    const snap = !previous || rowH !== previous[1] || version !== previous[2]
    animate.value = !snap && Math.abs(index - previousIndex) <= 3
    containerOffset.value = -index * rowH
  },
  { immediate: true }
)

// ===== 背景不透明度:推送值兜底,滑杆拖动期间用本地值即时预览 =====
// 与设置页共用 0~100 区间;经消息回传主窗口持久化,推送值一有变化即撤销本地预览
// (提交回显时数值相同不会闪烁;主窗口外部改动则由推送值接管)
const pushedOpacity = computed(() => {
  const value = desktopState.style.opacity
  return typeof value === 'number' && Number.isFinite(value) ? value : 90
})
const localOpacity = ref<number | null>(null)
const pillOpacity = computed(() => localOpacity.value ?? pushedOpacity.value)

/** 工具栏底色透明度:非 0 时保持高不透明度保证控件可读;0(纯透明歌词)时整个胶囊无背景 */
const toolbarAlpha = computed(() => (pillOpacity.value <= 0 ? 0 : 0.95))

watch(pushedOpacity, () => {
  localOpacity.value = null
})

function onOpacityInput(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  if (Number.isFinite(value)) localOpacity.value = value
}

/** 松手/键盘调整后提交一次,避免拖动过程刷爆跨窗口消息队列 */
function onOpacityCommit(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  if (Number.isFinite(value)) desktopAction({ type: 'opacity', value })
}

// ===== 胶囊尺寸:推送值兜底,拖动边缘/角落时本地即时预览 =====
// 与 settings.ts 的 DESKTOP_SIZE_RANGE 保持同值(悬浮窗是哑渲染器,不加载 settings 模块)
const SIZE_LIMITS = { minWidth: 360, maxWidth: 1600, minHeight: 96, maxHeight: 400 }
/** .stage 四周透明留白,与 desktop-bridge 的 STAGE_PADDING 同值 */
const STAGE_PAD = 5
const RESIZE_DIRS: DesktopResizeDir[] = ['n', 's', 'e', 'w', 'nw', 'ne', 'sw', 'se']
const pushedSize = computed(() => {
  const width = desktopState.style.width
  const height = desktopState.style.height
  return {
    width: typeof width === 'number' && Number.isFinite(width) ? width : 870,
    height: typeof height === 'number' && Number.isFinite(height) ? height : 138
  }
})
const localSize = ref<{ width: number; height: number } | null>(null)
const pillSize = computed(() => localSize.value ?? pushedSize.value)
const resizing = ref(false)
/** 正在拖拽的方位;预览锚定与把手点亮都由它驱动 */
const resizeDir = ref<DesktopResizeDir | null>(null)
/** 拖动结束后的宽限期:主窗口回推的中间值(比本地终值旧)到达时不得回弹预览,
 * 收到与终值一致的 ack 或超时后撤销本地预览,外部改动(设置页)再接管 */
let resizeSettleAt = 0

watch(pushedSize, (pushed) => {
  if (resizing.value || !localSize.value) return
  if (pushed.width === localSize.value.width && pushed.height === localSize.value.height) {
    localSize.value = null // 主窗口已确认终值
  } else if (Date.now() > resizeSettleAt) {
    localSize.value = null // 宽限期外:视作设置页等外部改动,交给推送值
  }
})

function clampSize(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Math.round(value)))
}

/**
 * 缩放预览期间把胶囊锚定到「不动的对侧边」:窗口实际尺寸由主窗口异步跟进,
 * flex 居中布局会随预览宽高在旧窗口里来回游移;把固定边钉在 stage 留白(5px)上
 * 与落定布局完全一致,起手/收手无跳动。未在缩放时返回 undefined,保持居中。
 */
const pillAnchorStyle = computed<Record<string, string> | undefined>(() => {
  const dir = resizeDir.value
  if (!resizing.value || !dir) return undefined
  const style: Record<string, string> = { position: 'absolute' }
  let centerX = false
  let centerY = false
  if (dir.includes('e')) style.left = STAGE_PAD + 'px'
  else if (dir.includes('w')) style.right = STAGE_PAD + 'px'
  else centerX = true
  if (dir.includes('s')) style.top = STAGE_PAD + 'px'
  else if (dir.includes('n')) style.bottom = STAGE_PAD + 'px'
  else centerY = true
  const parts: string[] = []
  if (centerX) {
    style.left = '50%'
    parts.push('translateX(-50%)')
  }
  if (centerY) {
    style.top = '50%'
    parts.push('translateY(-50%)')
  }
  if (parts.length > 0) style.transform = parts.join(' ')
  return style
})

let lastResizeSentAt = 0

/** 边缘/角落拖拽缩放:按方向计算新宽高(对侧边固定),本地即时预览,~33ms 节流回传主窗口。
 * 位移必须用 screenX/Y 屏幕坐标:clientX 相对窗口视口,w/n 缩放时主窗口会同步移动
 * 窗口,视口原点跟着动,读数里混入窗口位移形成正反馈(拖得越远抖得越凶) */
function onResizeStart(event: PointerEvent, dir: DesktopResizeDir) {
  const zone = event.currentTarget
  if (locked.value || !(zone instanceof HTMLElement)) return
  event.preventDefault()
  zone.setPointerCapture(event.pointerId)
  const startScreenX = event.screenX
  const startScreenY = event.screenY
  const start = { ...pillSize.value }
  resizing.value = true
  resizeDir.value = dir

  const send = (size: { width: number; height: number }) => {
    desktopAction({ type: 'resize', size: { ...size }, dir })
  }
  const onMove = (moveEvent: PointerEvent) => {
    const dx = moveEvent.screenX - startScreenX
    const dy = moveEvent.screenY - startScreenY
    let width = start.width
    let height = start.height
    if (dir.includes('e')) width = start.width + dx
    if (dir.includes('w')) width = start.width - dx
    if (dir.includes('s')) height = start.height + dy
    if (dir.includes('n')) height = start.height - dy
    localSize.value = {
      width: clampSize(width, SIZE_LIMITS.minWidth, SIZE_LIMITS.maxWidth),
      height: clampSize(height, SIZE_LIMITS.minHeight, SIZE_LIMITS.maxHeight)
    }
    // 主窗口 100ms 消费一次并合并,33ms 入队足够跟手且不撑爆消息队列
    const now = Date.now()
    if (now - lastResizeSentAt > 33) {
      lastResizeSentAt = now
      send(localSize.value)
    }
  }
  const onEnd = () => {
    resizing.value = false
    resizeDir.value = null
    resizeSettleAt = Date.now() + 800
    zone.removeEventListener('pointermove', onMove)
    zone.removeEventListener('pointerup', onEnd)
    zone.removeEventListener('pointercancel', onEnd)
    if (localSize.value) send(localSize.value)
  }
  zone.addEventListener('pointermove', onMove)
  zone.addEventListener('pointerup', onEnd)
  zone.addEventListener('pointercancel', onEnd)
}
</script>

<template>
  <div
    class="stage"
    :class="isDark ? 'dark' : 'light'"
    :style="{
      '--fs': fontSize + 'px',
      '--pill-alpha': pillOpacity / 100,
      '--toolbar-alpha': toolbarAlpha,
      '--pill-w': pillSize.width + 'px',
      '--pill-h': pillSize.height + 'px'
    }"
  >
    <div class="pill" :class="{ locked, hovered, resizing }" :style="pillAnchorStyle">
      <div class="lines" :style="{ '--trans-h': transHeight + 'px', '--win-h': windowHeight + 'px' }">
        <!-- 歌词窗口:2 行视图(当前句 + 翻译/下一句)垂直居中,内部轨道随行推进平滑上滑 -->
        <div
          v-if="hasTrack"
          class="window"
          :class="alignMode === 'left' ? 'align-left' : 'align-center'"
        >
          <div
            class="scroll"
            :class="{ animate }"
            :style="{ transform: `translateY(${containerOffset}px)` }"
          >
            <div
              v-for="(line, index) in desktopState.lines"
              :key="index"
              class="row"
              :class="{ active: index === activeIndex }"
              :style="{ height: rowHeight + 'px' }"
            >
              <div class="text">{{ line }}</div>
              <div class="trans" :class="{ open: index === activeIndex && activeTranslation }">
                <span v-if="index === activeIndex && activeTranslation">{{ activeTranslation }}</span>
              </div>
            </div>
          </div>
        </div>
        <!-- 降级态占位(搜索中/无歌词/断开/等待播放):主行 + 副行 -->
        <div v-else class="pair">
          <div class="line-main" :class="{ dim: dimMain }">{{ mainText }}</div>
          <div class="line-sub" v-if="subText">{{ subText }}</div>
        </div>
      </div>
      <div class="bar">
        <div class="bar-fill" :style="{ width: progressPct + '%' }"></div>
      </div>
      <!-- 顶部快捷工具栏:绝对定位于胶囊上缘中央,悬停浮现;锁定后仅留解锁 -->
      <div class="toolbar">
        <template v-if="!locked">
          <button class="ctl" title="缩小字号" @click="desktopAction({ type: 'font', delta: -1 })">A−</button>
          <button class="ctl" title="放大字号" @click="desktopAction({ type: 'font', delta: 1 })">A+</button>
          <button
            class="ctl"
            :class="{ on: translationOn }"
            :title="translationOn ? '关闭翻译' : '开启翻译'"
            @click="desktopAction({ type: 'translation' })"
          >
            译
          </button>
          <button
            class="ctl"
            :title="`对齐:${alignMode === 'left' ? '左对齐' : '居中'}(点击切换)`"
            @click="desktopAction({ type: 'align' })"
          >
            {{ alignMode === 'left' ? '左' : '中' }}
          </button>
          <button
            class="ctl"
            :title="`配色:${desktopState.style.theme}`"
            @click="desktopAction({ type: 'theme' })"
          >
            {{ themeLabel }}
          </button>
          <button class="ctl wide" title="锁定歌词栏:禁止拖动与悬停操作" @click="desktopAction({ type: 'lock' })">
            锁定
          </button>
          <input
            class="opacity"
            type="range"
            min="0"
            max="100"
            step="5"
            :value="pillOpacity"
            title="背景不透明度(0 为全透明)"
            @input="onOpacityInput"
            @change="onOpacityCommit"
          />
          <button class="ctl" title="关闭桌面歌词" @click="desktopAction({ type: 'close' })">✕</button>
        </template>
        <button v-else class="ctl wide" title="解锁歌词栏" @click="desktopAction({ type: 'lock' })">解锁</button>
      </div>
      <!-- 边缘/角落缩放区:悬停时才从拖拽区挖出 no-drag 孔,8 方位对应 8 种 resize 手势;
           把手指示条悬停/拖动对应方位时点亮 -->
      <div
        v-for="dir in RESIZE_DIRS"
        :key="dir"
        class="resize-zone"
        :class="[dir, { active: resizing && resizeDir === dir }]"
        @pointerdown="onResizeStart($event, dir)"
      ></div>
    </div>
  </div>
</template>

<style scoped>
.stage {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 5px;
  box-sizing: border-box;
  --pill-radius: 12px;
}

.pill {
  position: relative;
  width: var(--pill-w, 870px);
  height: var(--pill-h, 138px);
  display: flex;
  align-items: center;
  padding: 14px 18px 18px;
  border-radius: var(--pill-radius);
  box-sizing: border-box;
  -webkit-app-region: drag;
  transition: background 0.25s, box-shadow 0.18s;
}

/* 锁定:整个胶囊退出拖动区,不出尺寸把手;悬停只出「解锁」按钮,不显示描边 */
.pill.locked {
  -webkit-app-region: no-drag;
}

/* 悬停描边:由主窗口轮询光判定的 hovered 标志驱动(CSS :hover 在拖拽区不触发);
   锁定态不显示;resize 拖动中即使光标越出窗口也保持描边 */
.stage.dark .pill.hovered:not(.locked),
.stage.dark .pill.resizing:not(.locked) {
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.3), 0 8px 32px rgba(0, 0, 0, calc(var(--pill-alpha, 0.9) * 0.35));
}

.stage.light .pill.hovered:not(.locked),
.stage.light .pill.resizing:not(.locked) {
  box-shadow: 0 0 0 1px rgba(15, 20, 30, 0.26), 0 8px 32px rgba(0, 0, 0, calc(var(--pill-alpha, 0.9) * 0.18));
}

/* 深底浅字:暗色胶囊,压得住任何壁纸;背景浓度由 --pill-alpha 驱动,投影随浓度同步淡出 */
.stage.dark .pill {
  background: rgba(17, 19, 26, var(--pill-alpha, 0.9));
  color: #ffffff;
  box-shadow: 0 8px 32px rgba(0, 0, 0, calc(var(--pill-alpha, 0.9) * 0.35));
}

/* 浅底深字 */
.stage.light .pill {
  background: rgba(251, 252, 254, var(--pill-alpha, 0.9));
  color: #1a1c22;
  box-shadow: 0 8px 32px rgba(0, 0, 0, calc(var(--pill-alpha, 0.9) * 0.18));
}

/* 歌词视口:占满胶囊内高(align-self 覆盖 .pill 的 align-items:center),
   裁剪窗口在其中垂直居中,与旧版双行观感一致 */
.lines {
  flex: 1;
  min-width: 0;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

/* 歌词窗口:高度 = 开翻译 1 行 / 关翻译 2 行(--win-h),窗口外行一律裁掉;
   上下 5px 渐隐软化行进出窗口边缘的硬切割 */
.window {
  height: var(--win-h, 64px);
  overflow: hidden;
  -webkit-mask-image: linear-gradient(180deg, transparent 0, #000 5px, #000 calc(100% - 5px), transparent 100%);
  mask-image: linear-gradient(180deg, transparent 0, #000 5px, #000 calc(100% - 5px), transparent 100%);
}

/* 降级态占位(主行 + 副行),保持与旧版一致的居中双行观感 */
.pair {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 1px;
}

.scroll {
  will-change: transform;
}

.scroll.animate {
  transition: transform 0.55s cubic-bezier(0.22, 1, 0.36, 1);
}

/* 统一行高轨道:行内垂直居中;当前行文字放大加亮,推进时随轨道一起上滑
   (字号过渡让「下一句」在滑入主行位的过程中长大,与首页同款 morph) */
.row {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

/* 对齐方式:左对齐(旧版观感)/ 居中,作用于行内文字与译文 */
.window.align-left .row {
  align-items: flex-start;
}

.window.align-left .text {
  text-align: left;
}

.window.align-center .row {
  align-items: center;
}

.window.align-center .text {
  text-align: center;
}

.row .text {
  max-width: 100%;
  font-size: calc(var(--fs) * 0.5);
  line-height: 1.22;
  font-weight: 500;
  opacity: 0.62;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: font-size 0.3s, opacity 0.3s;
}

.row.active .text {
  font-size: var(--fs);
  font-weight: 700;
  opacity: 1;
}

.stage.dark .row.active .text {
  text-shadow: 0 1px 10px rgba(0, 0, 0, 0.45);
}

/* 译文槽:仅当前行展开,高度收在统一行高内,不改变行盒(与首页同款手法) */
.trans {
  height: 0;
  opacity: 0;
  overflow: hidden;
  font-size: max(12px, calc(var(--fs) * 0.5));
  line-height: 1.3;
  transition: height 0.25s, opacity 0.25s;
}

.trans.open {
  height: var(--trans-h, 17px);
  opacity: 0.62;
}

.line-main {
  font-size: var(--fs);
  font-weight: 700;
  line-height: 1.22;
  letter-spacing: 0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: font-size 0.2s;
}

.stage.dark .line-main {
  text-shadow: 0 1px 10px rgba(0, 0, 0, 0.45);
}

.line-main.dim {
  font-size: calc(var(--fs) * 0.62);
  font-weight: 600;
  opacity: 0.75;
}

.line-sub {
  font-size: max(12px, calc(var(--fs) * 0.5));
  line-height: 1.3;
  opacity: 0.62;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 顶部快捷工具栏:绝对定位于胶囊上缘中央,悬停浮现避免常驻遮挡歌词 */
.toolbar {
  position: absolute;
  top: 5px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 7px;
  border-radius: 11px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.18s, background 0.25s, border-color 0.25s;
  -webkit-app-region: no-drag;
  z-index: 3;
}

/* 工具栏与描边同源:hovered 标志驱动浮现 */
.pill.hovered .toolbar,
.pill.resizing .toolbar {
  opacity: 1;
  pointer-events: auto;
}

/* 工具栏底色跟随 --toolbar-alpha(非 0 时恒为高不透明度,保证控件可读;0 时整个胶囊无背景) */
.stage.dark .toolbar {
  background: rgba(17, 19, 26, var(--toolbar-alpha, 0.95));
  border: 1px solid rgba(255, 255, 255, calc(var(--toolbar-alpha, 0.95) * 0.13));
}

.stage.light .toolbar {
  background: rgba(251, 252, 254, var(--toolbar-alpha, 0.95));
  border: 1px solid rgba(15, 20, 30, calc(var(--toolbar-alpha, 0.95) * 0.1));
}

.ctl {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: 1px solid rgba(125, 135, 155, 0.25);
  background: rgba(125, 135, 155, 0.14);
  color: inherit;
  font-size: 12px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
  white-space: nowrap;
}

.ctl.wide {
  width: auto;
  padding: 0 8px;
}

/* 翻译开启态高亮 */
.ctl.on {
  background: rgba(79, 156, 249, 0.3);
  border-color: rgba(79, 156, 249, 0.6);
}

/* 不透明度滑杆:拖动即时预览,松手提交 */
.toolbar .opacity {
  width: 78px;
  accent-color: #4f9cf9;
  cursor: pointer;
}

/* 边缘/角落缩放区:平时不裁剪拖拽区(整窗可拖动);hovered 后才挖出 no-drag 孔
   接管指针,光标随方位变化;四角 18px、四边 10px,工具栏(顶层)不受影响 */
.resize-zone {
  position: absolute;
  z-index: 2;
  pointer-events: none;
}

.pill.hovered .resize-zone,
.pill.resizing .resize-zone {
  pointer-events: auto;
  -webkit-app-region: no-drag;
}

/* 锁定后禁用缩放交互(规则置于 hovered 之后以覆盖) */
.pill.locked .resize-zone {
  pointer-events: none;
}

/* 把手指示条:把手不是隐形的——光标贴近对应边缘/角即点亮,拖动中持续点亮
   (.active 不依赖 :hover,配合 pointer capture 光标越界也不熄灭) */
.resize-zone::after {
  content: '';
  position: absolute;
  border-radius: 3px;
  opacity: 0;
  transition: opacity 0.15s;
  pointer-events: none;
}

.resize-zone:hover::after,
.resize-zone.active::after {
  opacity: 1;
}

.stage.dark .resize-zone::after {
  background: rgba(255, 255, 255, 0.5);
}

.stage.light .resize-zone::after {
  background: rgba(15, 20, 30, 0.38);
}

/* 拖动中的方位用主题强调色,与进度条同源 */
.stage.dark .resize-zone.active::after,
.stage.light .resize-zone.active::after {
  background: #4f9cf9;
}

.resize-zone.n {
  left: 14px;
  right: 14px;
  top: 0;
  height: 10px;
  cursor: ns-resize;
}

.resize-zone.n::after {
  left: 50%;
  top: 2px;
  width: 46px;
  height: 3px;
  transform: translateX(-50%);
}

.resize-zone.s {
  left: 14px;
  right: 14px;
  bottom: 0;
  height: 10px;
  cursor: ns-resize;
}

.resize-zone.s::after {
  left: 50%;
  bottom: 2px;
  width: 46px;
  height: 3px;
  transform: translateX(-50%);
}

.resize-zone.w {
  top: 14px;
  bottom: 14px;
  left: 0;
  width: 10px;
  cursor: ew-resize;
}

.resize-zone.w::after {
  left: 2px;
  top: 50%;
  width: 3px;
  height: 46px;
  transform: translateY(-50%);
}

.resize-zone.e {
  top: 14px;
  bottom: 14px;
  right: 0;
  width: 10px;
  cursor: ew-resize;
}

.resize-zone.e::after {
  right: 2px;
  top: 50%;
  width: 3px;
  height: 46px;
  transform: translateY(-50%);
}

.resize-zone.nw {
  left: 0;
  top: 0;
  width: 18px;
  height: 18px;
  cursor: nwse-resize;
  z-index: 4;
}

.resize-zone.nw::after {
  left: 5px;
  top: 5px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.resize-zone.ne {
  right: 0;
  top: 0;
  width: 18px;
  height: 18px;
  cursor: nesw-resize;
  z-index: 4;
}

.resize-zone.ne::after {
  right: 5px;
  top: 5px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.resize-zone.sw {
  left: 0;
  bottom: 0;
  width: 18px;
  height: 18px;
  cursor: nesw-resize;
  z-index: 4;
}

.resize-zone.sw::after {
  left: 5px;
  bottom: 5px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.resize-zone.se {
  right: 0;
  bottom: 0;
  width: 18px;
  height: 18px;
  cursor: nwse-resize;
  z-index: 4;
}

.resize-zone.se::after {
  right: 5px;
  bottom: 5px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.bar {
  position: absolute;
  left: 20px;
  right: 20px;
  bottom: 9px;
  height: 3px;
  border-radius: 2px;
  overflow: hidden;
}

.stage.dark .bar {
  background: rgba(255, 255, 255, 0.14);
}

.stage.light .bar {
  background: rgba(20, 30, 50, 0.12);
}

.bar-fill {
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, #4f9cf9, #8fc7ff);
}
</style>
