<script setup lang="ts">
/**
 * 设置页:桌面歌词 / 歌词显示与校准 / 连接(设备、配对二维码、端口 Token)/ 关于。
 * 所有可调项走 settings.updateSettings 持久化;连接配置仍由 preload 的 lanLyrics 服务管理。
 */
import { computed, ref } from 'vue'
import qrcode from 'qrcode-generator'
import { activeState, applyOffset, offsetUi, userOffset, ui } from '../store'
import {
  DESKTOP_FONT_RANGE,
  DESKTOP_OPACITY_RANGE,
  MAIN_FONT_RANGE,
  settings,
  updateSettings,
  type DesktopTheme
} from '../settings'
import { resetDesktopPosition } from '../desktop-bridge'
import { buildPairingPayload } from '../qr/pair-qr'

// ===== 桌面歌词 =====
const THEME_OPTIONS: Array<{ value: DesktopTheme; label: string }> = [
  { value: 'auto', label: '跟随系统' },
  { value: 'dark', label: '深底浅字' },
  { value: 'light', label: '浅底深字' }
]

function bumpFontSize(key: 'desktopFontSize' | 'mainFontSize', delta: number) {
  const range = key === 'desktopFontSize' ? DESKTOP_FONT_RANGE : MAIN_FONT_RANGE
  const current = settings[key]
  updateSettings({ [key]: Math.min(range.max, Math.max(range.min, current + delta)) })
}

/** 桌面歌词背景不透明度(滑杆松手提交,updateSettings 内部会裁剪到合法区间) */
function onDesktopOpacity(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  if (Number.isFinite(value)) updateSettings({ desktopOpacity: value })
}

// ===== 歌词校准(从歌词视图迁来:本曲 / 此应用两维度记忆) =====
const calibrating = computed(() => !!activeState.value)

function formatOffset(ms: number): string {
  return ms === 0 ? '0' : `${ms > 0 ? '+' : ''}${ms}ms`
}

function setScope(scope: 'track' | 'app') {
  offsetUi.scope = scope
}

// ===== 连接 =====
const hasDevices = computed(() => (ui.snapshot?.devices.length ?? 0) > 0)

const connectionText = computed(() => {
  if (ui.serverError) return `服务错误:${ui.serverError}`
  if (!ui.snapshot?.listening) return '服务未启动'
  if (!hasDevices.value) return '等待手机连接…'
  return `已连接 ${ui.snapshot.devices.length} 台设备`
})

const primaryAddress = computed(() => {
  const addresses = ui.snapshot?.addresses ?? []
  // 优先 192.168.*(家庭局域网最常见),否则取第一个
  const preferred = addresses.find((a) => a.startsWith('192.168.'))
  return preferred ?? addresses[0] ?? '未获取到局域网 IP'
})

const rttText = computed(() => {
  const device = ui.snapshot?.devices.find((d) => d.deviceId === ui.snapshot?.activeDeviceId)
  return device && device.rttMs >= 0 ? `延迟 ${device.rttMs}ms` : ''
})

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

// ===== 配对二维码(docs/protocol.md §7)=====
const showQr = ref(false)

/** 当前配置对应的二维码 SVG(scalable,尺寸由 CSS 控制);内容超长等异常时为空串 */
const qrSvg = computed(() => {
  if (!showQr.value || !ui.snapshot) return ''
  const payload = buildPairingPayload(ui.snapshot.addresses, ui.snapshot.port, ui.snapshot.token)
  try {
    const qr = qrcode(0, 'M')
    qr.addData(payload)
    qr.make()
    return qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true })
  } catch {
    return ''
  }
})

// ===== 端口 / Token =====
const editingPort = ref('')
const editingToken = ref('')
const settingsSavedTip = ref('')

function beginEditConnection() {
  if (!ui.snapshot) return
  editingPort.value = String(ui.snapshot.port)
  editingToken.value = ui.snapshot.token
  settingsSavedTip.value = ''
}

async function saveConnection() {
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

function regenerateToken() {
  editingToken.value = window.services.lanLyrics.generateToken()
}
</script>

<template>
  <div class="settings-view">
    <!-- ===== 桌面歌词 ===== -->
    <section class="card">
      <h2>桌面歌词</h2>
      <div class="setting-row">
        <div class="setting-label">
          <div class="setting-title">悬浮歌词窗口</div>
          <div class="setting-hint">屏幕上方悬浮显示当前句,可拖动;随播放进度自动滚动</div>
        </div>
        <label class="switch">
          <input
            type="checkbox"
            :checked="settings.desktopEnabled"
            @change="updateSettings({ desktopEnabled: ($event.target as HTMLInputElement).checked })"
          />
          <span class="track"></span>
        </label>
      </div>
      <div v-if="ui.desktopError" class="error-tip">{{ ui.desktopError }}</div>

      <template v-if="settings.desktopEnabled">
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">字号</div>
            <div class="setting-hint">悬浮窗内的当前句大小</div>
          </div>
          <div class="stepper">
            <button @click="bumpFontSize('desktopFontSize', -2)">A−</button>
            <span class="mono">{{ settings.desktopFontSize }}px</span>
            <button @click="bumpFontSize('desktopFontSize', 2)">A+</button>
          </div>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">配色</div>
            <div class="setting-hint">也可在悬浮窗上悬停切换</div>
          </div>
          <div class="segment">
            <button
              v-for="option in THEME_OPTIONS"
              :key="option.value"
              :class="{ on: settings.desktopTheme === option.value }"
              @click="updateSettings({ desktopTheme: option.value })"
            >
              {{ option.label }}
            </button>
          </div>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">显示翻译</div>
            <div class="setting-hint">无译文时预告下一句歌词</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.desktopTranslation"
              @change="updateSettings({ desktopTranslation: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">背景不透明度</div>
            <div class="setting-hint">悬浮窗背景浓淡,0 为全透明(仅显示文字与进度)</div>
          </div>
          <div class="stepper">
            <input
              type="range"
              :min="DESKTOP_OPACITY_RANGE.min"
              :max="DESKTOP_OPACITY_RANGE.max"
              step="5"
              :value="settings.desktopOpacity"
              @change="onDesktopOpacity"
            />
            <span class="mono">{{ settings.desktopOpacity }}%</span>
          </div>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">锁定悬浮窗</div>
            <div class="setting-hint">禁止拖动并隐藏工具栏防误触;解锁可在悬停悬浮窗后点「解锁」,或回到这里</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.desktopLocked"
              @change="updateSettings({ desktopLocked: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">位置</div>
            <div class="setting-hint">拖动悬浮窗即可调整,自动记忆</div>
          </div>
          <button @click="resetDesktopPosition">恢复默认位置</button>
        </div>
      </template>
    </section>

    <!-- ===== 歌词显示 ===== -->
    <section class="card">
      <h2>歌词显示</h2>
      <div class="setting-row">
        <div class="setting-label">
          <div class="setting-title">主界面字号</div>
          <div class="setting-hint">「歌词」页滚动歌词的基准字号</div>
        </div>
        <div class="stepper">
          <button @click="bumpFontSize('mainFontSize', -1)">A−</button>
          <span class="mono">{{ settings.mainFontSize }}px</span>
          <button @click="bumpFontSize('mainFontSize', 1)">A+</button>
        </div>
      </div>
      <div class="setting-row">
        <div class="setting-label">
          <div class="setting-title">显示翻译</div>
          <div class="setting-hint">当前句下方展示对应译文(来源提供翻译时)</div>
        </div>
        <label class="switch">
          <input
            type="checkbox"
            :checked="settings.translation"
            @change="updateSettings({ translation: ($event.target as HTMLInputElement).checked })"
          />
          <span class="track"></span>
        </label>
      </div>

      <template v-if="calibrating">
        <div class="divider"></div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">歌词来源</div>
            <div class="setting-hint">按查询语言自动排序(中文优先中文源);出错的来源会自动熔断 5 分钟</div>
          </div>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">LRCLIB(主源)</div>
            <div class="setting-hint">公开歌词库,英文/多语种覆盖最好</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.providerLrclib"
              @change="updateSettings({ providerLrclib: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">网易云音乐(中文回退)</div>
            <div class="setting-hint">非官方接口,可能随时变动;失败自动回退不影响其他来源</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.providerNetease"
              @change="updateSettings({ providerNetease: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">酷狗音乐(中文补充)</div>
            <div class="setting-hint">非官方接口,只有普通 LRC(无独立翻译);失败自动回退</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.providerKugou"
              @change="updateSettings({ providerKugou: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">lrcmux(聚合源)</div>
            <div class="setting-hint">聚合 LRCLIB/Musixmatch/酷狗/Genius 等,LRCLIB 搜不到时兜底;无候选面板</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.providerLrcmux"
              @change="updateSettings({ providerLrcmux: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">Better Lyrics(TTML 逐字)</div>
            <div class="setting-hint">海外众包歌词 API,热歌命中率高;未缓存的新歌可能要求 API key 而跳过</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.providerBetterLyrics"
              @change="updateSettings({ providerBetterLyrics: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>
        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">并行搜索所有来源</div>
            <div class="setting-hint">同时请求各来源,高置信结果立即返回;关闭则按顺序逐个尝试</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="settings.parallelProviders"
              @change="updateSettings({ parallelProviders: ($event.target as HTMLInputElement).checked })"
            />
            <span class="track"></span>
          </label>
        </div>

        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-title">歌词校准</div>
            <div class="setting-hint">
              歌词与进度对不齐时微调;当前生效 {{ formatOffset(userOffset) }}
              (本曲 {{ formatOffset(offsetUi.trackValue) }} / 此应用
              {{ formatOffset(offsetUi.appValue) }})
            </div>
          </div>
        </div>
        <div class="setting-row">
          <div class="segment">
            <button :class="{ on: offsetUi.scope === 'track' }" @click="setScope('track')">
              记住本曲
            </button>
            <button :class="{ on: offsetUi.scope === 'app' }" @click="setScope('app')">
              记住此应用
            </button>
          </div>
          <div class="controls">
            <button @click="applyOffset(-500)" title="歌词延后 500ms">−500</button>
            <button @click="applyOffset(-100)" title="歌词延后 100ms">−100</button>
            <button @click="applyOffset(100)" title="歌词提前 100ms">+100</button>
            <button @click="applyOffset(500)" title="歌词提前 500ms">+500</button>
            <button v-if="userOffset !== 0" @click="applyOffset(0)">复位</button>
          </div>
        </div>
      </template>
    </section>

    <!-- ===== 连接 ===== -->
    <section class="card">
      <h2>连接</h2>
      <div class="setting-row">
        <div class="setting-label">
          <div class="setting-title">{{ connectionText }}</div>
          <div class="setting-hint mono">{{ primaryAddress }}:{{ ui.snapshot?.port ?? 18765 }}</div>
        </div>
        <span v-if="rttText" class="muted small">{{ rttText }}</span>
      </div>

      <div v-if="ui.snapshot && ui.snapshot.devices.length > 1" class="setting-row">
        <div class="setting-label">
          <div class="setting-title">活跃设备</div>
          <div class="setting-hint">歌词跟随选中的设备</div>
        </div>
        <select :value="ui.snapshot.activeDeviceId ?? ''" @change="setActiveDevice">
          <option v-for="device in ui.snapshot.devices" :key="device.deviceId" :value="device.deviceId">
            {{ device.deviceName }}
          </option>
        </select>
      </div>
      <div v-else-if="hasDevices" class="setting-row">
        <div class="setting-label">
          <div class="setting-title">{{ ui.snapshot?.devices[0].deviceName }}</div>
          <div class="setting-hint">v{{ ui.snapshot?.devices[0].appVersion }}</div>
        </div>
      </div>

      <div class="button-row">
        <button @click="showQr = !showQr">{{ showQr ? '隐藏二维码' : '配对二维码' }}</button>
        <button @click="copyConfig">复制配置</button>
        <button @click="beginEditConnection">端口 / Token</button>
      </div>

      <div v-if="showQr" class="qr-block">
        <div class="qr" v-html="qrSvg"></div>
        <div class="muted hint">
          手机 App → LAN Push → <b>Scan QR to Connect</b>,扫码自动填入地址与 Token。<br />
          二维码含配对 Token,请勿截图外传。
        </div>
      </div>

      <template v-if="editingToken || editingPort">
        <div class="divider"></div>
        <div class="edit-grid">
          <label>端口 <input v-model="editingPort" class="mono" style="width: 80px" /></label>
          <label>Token <input v-model="editingToken" class="mono" style="width: 210px" /></label>
        </div>
        <div class="button-row">
          <button @click="regenerateToken">重新生成 Token</button>
          <button class="primary" @click="saveConnection">保存并重启服务</button>
          <span v-if="settingsSavedTip" class="muted small">{{ settingsSavedTip }}</span>
        </div>
      </template>
      <div class="muted hint">
        无摄像头/扫码不便时,也可在手机 App「Android Relay → LAN Push」中手动填入地址与 Token
      </div>
    </section>

    <!-- ===== 关于 ===== -->
    <section class="card">
      <h2>关于</h2>
      <div class="muted small about-line">
        LAN 歌词 v0.3.0 · 手机 <b>android_relay</b> 推送播放状态,PC 侧匹配并滚动展示歌词<br />
        歌词来源:LRCLIB(主源)+ 网易云(中文回退,非官方接口);优先使用带时间轴的逐行歌词,
        匹配不到时降级为纯文本
      </div>
    </section>
  </div>
</template>

<style scoped>
.settings-view {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 14px 14px;
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
}

.setting-label {
  min-width: 0;
}

.setting-title {
  font-size: 13px;
  font-weight: 500;
}

.setting-hint {
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-2);
}

.error-tip {
  margin: 6px 0;
  padding: 6px 10px;
  border-radius: 7px;
  background: rgba(245, 108, 108, 0.12);
  color: var(--err);
  font-size: 12px;
}

.divider {
  height: 1px;
  background: var(--border);
  margin: 8px 0;
}

/* 开关 */
.switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
  flex-shrink: 0;
  cursor: pointer;
}

.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch .track {
  position: absolute;
  inset: 0;
  border-radius: 11px;
  background: var(--bg-3);
  border: 1px solid var(--border);
  transition: background 0.2s, border-color 0.2s;
}

.switch .track::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  transition: transform 0.2s;
}

.switch input:checked + .track {
  background: var(--accent);
  border-color: var(--accent);
}

.switch input:checked + .track::after {
  transform: translateX(18px);
}

/* 分段选择 */
.segment {
  display: inline-flex;
  background: var(--bg-3);
  border-radius: 9px;
  padding: 2px;
  gap: 2px;
  flex-shrink: 0;
}

.segment button {
  border: none;
  background: transparent;
  padding: 4px 10px;
  border-radius: 7px;
  font-size: 11.5px;
  color: var(--text-2);
  cursor: pointer;
  white-space: nowrap;
}

.segment button.on {
  background: var(--bg-2);
  color: var(--text-1);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
}

/* 步进器 */
.stepper {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.stepper button {
  padding: 3px 10px;
  font-size: 11px;
}

.stepper span {
  min-width: 44px;
  text-align: center;
  font-size: 12px;
}

.controls {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}

.controls button {
  padding: 3px 9px;
  font-size: 11px;
}

.button-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 8px 0 4px;
}

.edit-grid {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin: 6px 0;
  font-size: 12px;
}

.edit-grid label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.qr-block {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 8px 0;
  padding: 10px;
  background: var(--bg-3);
  border-radius: 9px;
}

/* 二维码必须深色模块 + 浅色底,白底保证扫码识别率 */
.qr {
  background: #fff;
  padding: 6px;
  border-radius: 6px;
  line-height: 0;
  flex-shrink: 0;
}

.qr :deep(svg) {
  width: 132px;
  height: 132px;
  display: block;
}

.mono {
  font-family: ui-monospace, Consolas, monospace;
}

.muted {
  color: var(--text-2);
}

.hint {
  font-size: 11px;
  line-height: 1.6;
}

.small {
  font-size: 11px;
}

.about-line {
  line-height: 1.8;
}
</style>
