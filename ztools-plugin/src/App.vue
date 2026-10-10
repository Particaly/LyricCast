<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { activeArtwork, activeState, initStore, ui } from './store'

/** 协议状态 → 中文展示 */
const STATE_TEXT: Record<string, string> = {
  playing: '播放中',
  paused: '已暂停',
  buffering: '缓冲中',
  stopped: '已停止',
  none: '无播放'
}

const cleanupRef = ref<(() => void) | null>(null)
const position = ref<{ positionMs: number; known: boolean; state: string } | null>(null)
let rafId = 0
let lastUiUpdate = 0

const snapshot = computed(() => ui.snapshot)
const hasDevices = computed(() => (ui.snapshot?.devices.length ?? 0) > 0)

const primaryAddress = computed(() => {
  const addresses = ui.snapshot?.addresses ?? []
  // 优先 192.168.*(家庭局域网最常见),否则取第一个
  const preferred = addresses.find((a) => a.startsWith('192.168.'))
  return preferred ?? addresses[0] ?? '未获取到局域网 IP'
})

const statusText = computed(() => {
  if (ui.serverError) return `服务错误:${ui.serverError}`
  if (!ui.snapshot?.listening) return '服务未启动'
  if (!hasDevices.value) return '等待手机连接…'
  return `已连接 ${ui.snapshot.devices.length} 台设备`
})

const statusDot = computed(() => {
  if (ui.serverError || !ui.snapshot?.listening) return 'err'
  return hasDevices.value ? 'ok' : 'warn'
})

const rttText = computed(() => {
  const device = ui.snapshot?.devices.find((d) => d.deviceId === ui.snapshot?.activeDeviceId)
  return device && device.rttMs >= 0 ? `延迟 ${device.rttMs}ms` : '测量延迟中…'
})

const stateBadge = computed(() => {
  const state = activeState.value?.playback.state ?? 'none'
  return `${STATE_TEXT[state] ?? state}`
})

const durationMs = computed(() => activeState.value?.track.durationMs ?? 0)
const positionMs = computed(() => position.value?.positionMs ?? 0)
const progressPct = computed(() =>
  durationMs.value > 0 ? Math.min(100, (positionMs.value / durationMs.value) * 100) : 0
)

/** 进度未知时 UI 显示 --:-- 而不是伪造 0:00 */
const positionKnown = computed(() => position.value?.known ?? false)

const artworkUrl = computed(() => {
  const artwork = activeArtwork.value
  return artwork ? `data:${artwork.mime};base64,${artwork.dataBase64}` : ''
})

function formatTime(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

/** rAF 驱动进度显示;~20fps 足够,降低无谓重绘 */
function tick(now: number) {
  rafId = requestAnimationFrame(tick)
  if (now - lastUiUpdate < 50) return
  lastUiUpdate = now
  const next = window.services.lanLyrics.getPosition()
  if (next) position.value = { positionMs: next.positionMs, known: next.known, state: next.state }
}

function setActiveDevice(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  window.services.lanLyrics.setActiveDevice(value)
}

function copyConfig() {
  const snapshotValue = ui.snapshot
  if (!snapshotValue) return
  const text = `PC 地址:${primaryAddress.value}\n端口:${snapshotValue.port}\nToken:${snapshotValue.token}`
  if (window.ztools?.copyText) {
    window.ztools.copyText(text)
  } else {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
}

// ===== 设置区(端口 / Token)=====
const editingPort = ref('')
const editingToken = ref('')
const showSettings = ref(false)
const settingsSavedTip = ref('')

function toggleSettings() {
  showSettings.value = !showSettings.value
  if (showSettings.value && snapshot.value) {
    editingPort.value = String(snapshot.value.port)
    editingToken.value = snapshot.value.token
    settingsSavedTip.value = ''
  }
}

function regenerateToken() {
  editingToken.value = window.services.lanLyrics.generateToken()
}

async function saveSettings() {
  const port = parseInt(editingPort.value, 10) || 18765
  const token = editingToken.value.trim()
  if (token.length < 16) {
    settingsSavedTip.value = 'Token 至少 16 位'
    return
  }
  const result = await window.services.lanLyrics.start({ port, token })
  settingsSavedTip.value = result.ok ? '已保存并重启服务' : `重启失败:${result.error}`
  if (result.ok) ui.snapshot = window.services.lanLyrics.getSnapshot()
}

function requestArtwork() {
  window.services.lanLyrics.requestArtwork()
}

onMounted(async () => {
  cleanupRef.value = await initStore()
  rafId = requestAnimationFrame(tick)
})

onUnmounted(() => {
  cancelAnimationFrame(rafId)
  cleanupRef.value?.()
})
</script>

<template>
  <div class="app">
    <header class="header">
      <h1>LAN 歌词</h1>
      <span class="status">
        <span class="dot" :class="statusDot"></span>{{ statusText }}
      </span>
    </header>

    <!-- 连接信息 -->
    <section class="card">
      <h2>手机接入</h2>
      <div class="row">
        <span class="mono">{{ primaryAddress }}:{{ snapshot?.port ?? 18765 }}</span>
        <button @click="copyConfig">复制配置</button>
        <button @click="toggleSettings">{{ showSettings ? '收起设置' : '端口 / Token' }}</button>
      </div>
      <div v-if="showSettings" class="settings">
        <label>端口 <input v-model="editingPort" class="mono" style="width: 80px" /></label>
        <label>Token <input v-model="editingToken" class="mono" style="width: 210px" /></label>
        <button @click="regenerateToken">重新生成</button>
        <button class="primary" @click="saveSettings">保存并重启服务</button>
        <span v-if="settingsSavedTip" class="muted">{{ settingsSavedTip }}</span>
      </div>
      <div class="row muted hint">
        在手机 App「Android Relay → LAN Push」中填入以上地址与 Token
      </div>
    </section>

    <!-- 设备 -->
    <section class="card" v-if="snapshot && snapshot.devices.length > 0">
      <h2>设备</h2>
      <div v-if="snapshot.devices.length > 1" class="row">
        <select :value="snapshot.activeDeviceId ?? ''" @change="setActiveDevice">
          <option v-for="device in snapshot.devices" :key="device.deviceId" :value="device.deviceId">
            {{ device.deviceName }}
          </option>
        </select>
        <span class="muted">{{ rttText }}</span>
      </div>
      <div v-else class="row">
        <span>{{ snapshot.devices[0].deviceName }}</span>
        <span class="muted">{{ rttText }}</span>
        <span class="muted">v{{ snapshot.devices[0].appVersion }}</span>
      </div>
    </section>

    <!-- 正在播放 -->
    <section class="card" v-if="activeState">
      <h2>正在播放</h2>
      <div class="now-playing">
        <img v-if="artworkUrl" class="artwork" :src="artworkUrl" alt="封面" />
        <div v-else class="artwork placeholder" @click="requestArtwork" title="点击重试获取封面">♪</div>
        <div class="meta">
          <div class="title">{{ activeState.track.title || '(未知标题)' }}</div>
          <div class="artist">{{ activeState.track.artist || '(未知歌手)' }}</div>
          <div class="album muted" v-if="activeState.track.album">{{ activeState.track.album }}</div>
          <div class="row muted small">
            <span>{{ activeState.app.name || activeState.app.package }}</span>
            <span class="badge">{{ stateBadge }}</span>
          </div>
        </div>
      </div>
      <div class="progress-row">
        <span class="mono time">{{ positionKnown ? formatTime(positionMs) : '--:--' }}</span>
        <div class="progress">
          <div class="progress-fill" :style="{ width: progressPct + '%' }"></div>
        </div>
        <span class="mono time">{{ formatTime(durationMs) }}</span>
      </div>
    </section>
    <section class="card" v-else>
      <h2>正在播放</h2>
      <div class="muted">暂无播放信息 —— 手机端开启 LAN Push 并连接后自动显示</div>
    </section>
  </div>
</template>

<style scoped>
.app {
  padding: 12px 14px;
  max-width: 480px;
}

.header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.header h1 {
  font-size: 16px;
  margin: 0;
}

.status {
  font-size: 12px;
  color: var(--text-2);
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0;
  flex-wrap: wrap;
}

.mono {
  font-family: ui-monospace, Consolas, monospace;
}

.hint {
  font-size: 11px;
}

.small {
  font-size: 11px;
}

.settings {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 8px 0;
  padding: 8px;
  background: var(--bg-3);
  border-radius: 7px;
}

.now-playing {
  display: flex;
  gap: 12px;
  align-items: center;
}

.artwork {
  width: 72px;
  height: 72px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
}

.artwork.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-3);
  color: var(--text-2);
  font-size: 28px;
  cursor: pointer;
}

.meta {
  min-width: 0;
}

.title {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.artist {
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.badge {
  border: 1px solid var(--border);
  border-radius: 5px;
  padding: 0 6px;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.time {
  font-size: 11px;
  color: var(--text-2);
  min-width: 34px;
}

.progress {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  background: var(--bg-3);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--accent);
  border-radius: 2px;
  transition: width 0.1s linear;
}
</style>
