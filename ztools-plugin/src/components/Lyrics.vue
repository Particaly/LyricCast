<script setup lang="ts">
/**
 * 歌词展示:以 rAF 插值位置驱动当前行高亮与平滑滚动(不依赖网络消息到达频率)。
 * - 逐行渲染 + 容器 transform:行数在几百行内,无需虚拟滚动;
 * - 大跨度跳变(seek/切歌)时瞬时定位,常规推进时用 CSS 过渡平滑滚动;
 * - 翻译显示在滚动区下方的固定条(不破坏固定行高的滚动计算);
 * - 纯文本降级、无歌词降级;
 * - 手动偏移:+= 歌词提前,-= 歌词延后,按本曲/此应用记忆。
 */
import { computed, ref, watch } from 'vue'
import { applyOffset, fontSize, lyric, offsetUi, setFontSize, userOffset } from '../store'
import { findActiveLine } from '../lyric/parse-lrc'

const props = defineProps<{
  /** 插值后的当前播放位置(毫秒);进度未知时传 -1 */
  positionMs: number
}>()

const LINE_HEIGHT = 40
const VIEW_HEIGHT = 200

/** 应用偏移后用于查找当前行的位置:offset 正 = 歌词提前 */
const searchPosition = computed(() =>
  props.positionMs >= 0 ? props.positionMs + userOffset.value : Number.NaN
)

const activeIndex = computed(() => {
  if (!Number.isFinite(searchPosition.value) || lyric.lines.length === 0) return -1
  return findActiveLine(lyric.lines, searchPosition.value)
})

const containerOffset = ref(0)
const animate = ref(true)

// 索引变化时滚动;跳变 > 3 行视为 seek/切歌,瞬时定位
watch(activeIndex, (index, previous) => {
  if (index < 0) {
    containerOffset.value = 0
    return
  }
  const jump = Math.abs(index - (previous ?? -2))
  animate.value = jump <= 3
  containerOffset.value = VIEW_HEIGHT / 2 - LINE_HEIGHT / 2 - index * LINE_HEIGHT
})

const hasSynced = computed(() => lyric.lines.length > 0)

/** 当前行翻译(固定条展示) */
const activeTranslation = computed(() => {
  const index = activeIndex.value
  if (index < 0) return ''
  const line = lyric.lines[index]
  return line ? lyric.translationMap.get(line.timeMs) ?? '' : ''
})

function formatOffset(ms: number): string {
  return ms === 0 ? '0' : `${ms > 0 ? '+' : ''}${ms}ms`
}

function adjustOffset(delta: number) {
  applyOffset(delta)
}

function resetOffset() {
  applyOffset(0)
}

function setScope(scope: 'track' | 'app') {
  offsetUi.scope = scope
}

function decreaseFont() {
  setFontSize(fontSize.value - 1)
}

function increaseFont() {
  setFontSize(fontSize.value + 1)
}
</script>

<template>
  <section class="card">
    <h2>歌词</h2>

    <!-- 头部:来源 + 偏移控制 + 字号 -->
    <div class="lyric-header" v-if="hasSynced || lyric.plain">
      <span class="muted small">来源 {{ lyric.source }}<template v-if="userOffset !== 0"> · 偏移 {{ formatOffset(userOffset) }}</template></span>
      <div class="controls">
        <template v-if="hasSynced">
          <button @click="adjustOffset(-500)" title="歌词延后 500ms">-500</button>
          <button @click="adjustOffset(-100)" title="歌词延后 100ms">-100</button>
          <button @click="adjustOffset(100)" title="歌词提前 100ms">+100</button>
          <button @click="adjustOffset(500)" title="歌词提前 500ms">+500</button>
          <button v-if="userOffset !== 0" @click="resetOffset">复位</button>
        </template>
        <button @click="decreaseFont">A-</button>
        <button @click="increaseFont">A+</button>
      </div>
    </div>
    <div class="lyric-header" v-if="hasSynced">
      <span class="muted small">校准记忆:</span>
      <label class="muted small">
        <input type="radio" value="track" :checked="offsetUi.scope === 'track'" @change="setScope('track')" /> 本曲
      </label>
      <label class="muted small">
        <input type="radio" value="app" :checked="offsetUi.scope === 'app'" @change="setScope('app')" /> 此应用
      </label>
      <span class="muted small">(本曲 {{ formatOffset(offsetUi.trackValue) }} / 此应用 {{ formatOffset(offsetUi.appValue) }})</span>
    </div>

    <!-- 逐行滚动歌词 -->
    <template v-if="hasSynced">
      <div class="lyric-view" :style="{ height: VIEW_HEIGHT + 'px' }">
        <div
          class="lyric-scroll"
          :class="{ animate }"
          :style="{ transform: `translateY(${containerOffset}px)` }"
        >
          <div
            v-for="(line, index) in lyric.lines"
            :key="index"
            class="lyric-line"
            :class="{ active: index === activeIndex }"
            :style="{ fontSize: fontSize + 'px', lineHeight: LINE_HEIGHT + 'px' }"
          >
            {{ line.text || '♪' }}
          </div>
        </div>
        <!-- 上下渐隐遮罩 -->
        <div class="fade top"></div>
        <div class="fade bottom"></div>
      </div>
      <div class="translation-bar" :style="{ minHeight: '18px' }">
        <span v-if="activeTranslation" class="muted">{{ activeTranslation }}</span>
      </div>
    </template>

    <!-- 纯文本歌词(无时间轴) -->
    <div class="plain" v-else-if="lyric.plain" :style="{ fontSize: fontSize + 'px' }">{{ lyric.plain }}</div>

    <!-- 状态占位 -->
    <div class="muted status" v-else-if="lyric.loading">歌词搜索中…</div>
    <div class="muted status" v-else-if="lyric.notFound">未找到歌词(已缓存结果,次日自动重试)</div>
    <div class="muted status" v-else>等待播放…</div>
  </section>
</template>

<style scoped>
.lyric-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.controls {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.controls button {
  padding: 2px 8px;
  font-size: 11px;
}

.lyric-view {
  position: relative;
  overflow: hidden;
}

.lyric-scroll {
  will-change: transform;
}

.lyric-scroll.animate {
  transition: transform 0.3s ease;
}

.lyric-line {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-2);
  transition: color 0.25s;
}

.lyric-line.active {
  color: var(--text-1);
  font-weight: 600;
}

.fade {
  position: absolute;
  left: 0;
  right: 0;
  height: 36px;
  pointer-events: none;
}

.fade.top {
  top: 0;
  background: linear-gradient(var(--bg-2), transparent);
}

.fade.bottom {
  bottom: 0;
  background: linear-gradient(transparent, var(--bg-2));
}

.translation-bar {
  margin-top: 4px;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.plain {
  white-space: pre-wrap;
  max-height: 240px;
  overflow-y: auto;
  line-height: 1.6;
}

.status {
  padding: 16px 0;
  text-align: center;
}
</style>
