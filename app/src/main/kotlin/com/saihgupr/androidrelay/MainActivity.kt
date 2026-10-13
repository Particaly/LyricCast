package com.saihgupr.androidrelay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import org.json.JSONObject

/**
 * 主界面(2026-10 改版):状态优先的单页结构。
 *
 * 职责分界:本类只做"配置读写 + 状态展示 + 流程引导",推送与重连在
 * MediaSessionListenerService/LanPushManager,前台保活在 RelayForegroundService。
 * 状态来源:LanConnectionState(onResume 注册 / onPause 注销),英雄卡、顶栏胶囊、
 * LAN 卡标题圆点与"正在推送"卡都由它驱动;通知监听权限引导卡按系统设置条件显示。
 * 「后台保活」卡(参考 Gotify)提供四项自检与分 ROM 引导,逻辑在 KeepAliveGuide。
 * 页底「退出」入口走 AppExit 完整退出:停全部通道并落盘退出标记,自动补拉全部关门,
 * 恢复只能通过「重新启动推送」或重新保存配置这类显式动作。
 */
class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 扫码配对:QrScanActivity 返回 PC 地址(含多网卡备选)/端口/Token 后,
     * 优选与手机同网段的地址填入 LAN 表单并立即保存重启服务
     * (registerForActivityResult 必须在 onCreate 前注册,故为字段)。
     */
    private val pairScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        val host = data.getStringExtra(QrScanActivity.EXTRA_HOST) ?: return@registerForActivityResult
        val port = data.getIntExtra(QrScanActivity.EXTRA_PORT, LanSettings.DEFAULT_PORT)
        val token = data.getStringExtra(QrScanActivity.EXTRA_TOKEN) ?: return@registerForActivityResult
        val addresses = data.getStringArrayListExtra(QrScanActivity.EXTRA_ADDRESSES)
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(host)
        applyQrPairing(addresses, port, token)
    }

    /**
     * Android 13+ 通知权限:拒绝时前台服务照常运行(仅状态栏不显示),不阻塞配对流程。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.notify_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    // LAN 测试连接的一次性客户端与状态(均在主线程访问)
    private var lanTestClient: LanPushClient? = null
    private var lanTesting = false
    private var lanTestTimeoutPending = false

    // LAN 连接状态与曲目摘要的监听句柄(onResume 注册 / onPause 注销)
    private var lanStateListener: ((Boolean, String) -> Unit)? = null
    private var trackListener: ((LanConnectionState.NowPlaying?) -> Unit)? = null

    // 英雄卡状态点呼吸动画(懒创建,仅已连接时播放)
    private var heroPulse: Animation? = null

    // 最近一次连接状态(英雄卡大字/详情行/扫码按钮文案共用;applyConnectionState 写入)
    private var lastStatus: ConnectionUiStatus = ConnectionUiStatus.DISABLED

    // 英雄卡描边:bg_card_hero 为运行时改色的专属 drawable(mutate 后不污染共享缓存)
    private var heroStroke: GradientDrawable? = null

    // 扫码配对成功后需自动展开 LAN 折叠卡,保存展开态引用
    private lateinit var lanBody: View
    private lateinit var lanChevron: View

    // 「后台保活」折叠卡的引用与当前 ROM(onCreate 中初始化,onResume 复用)
    private lateinit var keepAliveBody: View
    private lateinit var keepAliveChevron: View
    private lateinit var keepAliveFlavor: RomFlavor

    // 保存配置后的 rebind 兜底定时(见 scheduleRebindFallback)
    private val rebindFallback = Runnable { ensureListenerServiceRestarted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        heroStroke = findViewById<View>(R.id.hero_card).background.mutate() as GradientDrawable

        // 页脚版本号按 PackageManager 实际版本动态填入,避免发版漏改静态文案
        findViewById<TextView>(R.id.footer_version_text).text =
            getString(R.string.footer_version, currentVersionName())

        val permissionButton = findViewById<Button>(R.id.permission_button)

        permissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ADB 授权命令默认收起,点击"无法跳转?用 ADB 授权"展开
        findViewById<TextView>(R.id.adb_toggle).setOnClickListener {
            val adb = findViewById<TextView>(R.id.adb_instructions)
            adb.visibility = if (adb.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        setupLanSection()
        setupKeepAliveSection()
        setupExitSection()
        setupCollapsibleSections()
        updateStatus()
        // 应用启动时若已配对(LAN 开关打开且有地址),尝试拉起前台保活服务
        // (完整退出态下此调用为空操作,退出后重开应用不会再自动后台运行)
        maybeStartForegroundService()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // 退出/恢复按钮的文案跟随持久化标记(后台期间可能经通知栏「退出」变过状态)
        refreshExitSection()
        // 配置已启用但服务侧仍停在"未启用"时补发一次 rebind(从系统设置授权返回、
        // 或上次保存后的 rebind 被系统忽略的场景,见 ensureListenerServiceRestarted)
        ensureListenerServiceRestarted()
        // 保活自检是实时系统状态(电池/通知),回到前台时重查(含从系统设置页返回的场景)
        if (::keepAliveFlavor.isInitialized) updateKeepAliveStatus()
        // 前台保活服务可能因 Android 12+ 后台启动限制(Listener rebind 常在后台)或
        // ROM 拉起失败而缺席,且该失败完全静默;回到前台是合法启动时机,按存活标记
        // 幂等补拉一次,避免"有连接、无优先级、无通知"的裸奔态。
        if (!RelayForegroundService.isRunning) maybeStartForegroundService()
        // 实时展示 LAN 连接状态与当前曲目(由服务内的 LanPushManager 写入)
        val stateListener = { connected: Boolean, text: String ->
            runOnUiThread { applyConnectionState(connected, text) }
        }
        lanStateListener = stateListener
        LanConnectionState.addListener(stateListener)
        val tListener = { track: LanConnectionState.NowPlaying? ->
            runOnUiThread { applyTrack(track) }
        }
        trackListener = tListener
        LanConnectionState.addTrackListener(tListener)
        // 先在主线程直接应用当前快照,再交给监听器跟进后续变化
        val (connected, text) = LanConnectionState.snapshot()
        applyConnectionState(connected, text)
        applyTrack(LanConnectionState.trackSnapshot())
    }

    override fun onPause() {
        super.onPause()
        lanStateListener?.let { LanConnectionState.removeListener(it) }
        lanStateListener = null
        trackListener?.let { LanConnectionState.removeTrackListener(it) }
        trackListener = null
        findViewById<View>(R.id.hero_halo).clearAnimation()
    }

    // ==================== 状态展示 ====================

    /**
     * 把 LAN 连接状态应用到界面各处:英雄卡(大字短标签 + 详情行 + 彩色描边 +
     * 呼吸光环)、顶栏状态胶囊、LAN 卡标题圆点与卡内行内状态文本。
     * 大字只放短语(已连接/重连中…),原始状态文本(含断开原因)降级进详情行,
     * 避免长句撑爆 20sp 标题;未启用按真实原因分化:通知使用权未授权 → "需开启
     * 通知使用权",已配置但开关关闭 → "同步已关闭",未配置 → "尚未配对"。
     */
    private fun applyConnectionState(connected: Boolean, statusText: String) {
        // 退出态以持久化标记为准(进程重启后 LanConnectionState 是空白快照,
        // 不能依赖服务写入的"已退出"文本),直接覆盖连接状态展示
        val status = if (AppExit.isExited(this)) ConnectionUiStatus.EXITED
        else ConnectionUi.from(connected, statusText, NotificationAccess.granted(this))
        lastStatus = status
        val color = statusColor(status)

        heroStroke?.setStroke(dp(1), color)
        findViewById<View>(R.id.hero_dot).backgroundTintList = ColorStateList.valueOf(color)
        findViewById<View>(R.id.hero_halo).backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, HALO_ALPHA))
        findViewById<View>(R.id.status_dot).backgroundTintList = ColorStateList.valueOf(color)
        val heroHalo = findViewById<View>(R.id.hero_halo)
        heroHalo.clearAnimation()
        if (status == ConnectionUiStatus.CONNECTED) heroHalo.startAnimation(pulse())

        val hostConfigured = LanSettings.host(this).isNotBlank()
        findViewById<TextView>(R.id.hero_status_text).text = when (status) {
            ConnectionUiStatus.EXITED -> getString(R.string.status_exited)
            ConnectionUiStatus.DISABLED ->
                getString(
                    if (hostConfigured) R.string.status_disabled else R.string.status_not_paired
                )
            ConnectionUiStatus.NEEDS_ACCESS -> getString(R.string.status_need_access)
            else -> status.shortLabel
        }
        findViewById<TextView>(R.id.status_capsule_text).apply {
            text = when (status) {
                ConnectionUiStatus.EXITED -> getString(R.string.status_capsule_exited)
                ConnectionUiStatus.DISABLED ->
                    getString(
                        if (hostConfigured) R.string.status_disabled else R.string.status_capsule_unpaired
                    )
                ConnectionUiStatus.NEEDS_ACCESS -> getString(R.string.status_capsule_need_access)
                else -> status.shortLabel
            }
            setTextColor(color)
        }

        findViewById<View>(R.id.lan_header_dot).visibility =
            if (status == ConnectionUiStatus.CONNECTED) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.lan_status_text).apply {
            text = statusText
            setTextColor(getColor(if (connected) R.color.status_ok else R.color.text_secondary))
        }

        updateHeroTarget()
    }

    /**
     * 英雄卡详情行:按状态给出最有用的那句话 —— 已连接显示目标地址;
     * 重连/连接中透传原始状态文本(带断开原因);认证失败给排查指引;
     * 未启用按真实原因分化:未授权通知使用权给授权指引、开关关闭给恢复提示、
     * 已启用但服务还没起来给等待提示。
     * 顺带刷新扫码按钮文案:已配对后从"扫码配对"切换为"重新扫码配对"。
     * 保存设置后需手动调用一次刷新。
     */
    private fun updateHeroTarget() {
        val host = LanSettings.host(this)
        val targetText = findViewById<TextView>(R.id.hero_target_text)
        targetText.text = when {
            lastStatus == ConnectionUiStatus.EXITED -> getString(R.string.status_exited_hint)
            lastStatus == ConnectionUiStatus.CONNECTED && host.isNotBlank() ->
                getString(R.string.lan_target_text, host, LanSettings.port(this))
            host.isBlank() -> getString(R.string.status_not_paired_hint)
            lastStatus == ConnectionUiStatus.NEEDS_ACCESS ->
                getString(R.string.hero_detail_need_access)
            !LanSettings.isEnabled(this) ->
                getString(R.string.lan_target_paused_text, host, LanSettings.port(this))
            lastStatus == ConnectionUiStatus.DISABLED ->
                getString(R.string.hero_detail_waiting_service)
            lastStatus == ConnectionUiStatus.AUTH_FAILED ->
                getString(R.string.hero_detail_auth_failed)
            else -> LanConnectionState.snapshot().second
        }
        findViewById<Button>(R.id.lan_scan_button).setText(
            if (host.isBlank()) R.string.pair_button else R.string.pair_button_again
        )
    }

    /** "正在推送"卡:有选中会话时展示曲目与播放态(状态胶囊染色),无会话回到空态引导。 */
    private fun applyTrack(track: LanConnectionState.NowPlaying?) {
        val row = findViewById<View>(R.id.now_playing_row)
        val empty = findViewById<TextView>(R.id.now_playing_empty)
        if (track == null) {
            row.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        row.visibility = View.VISIBLE
        empty.visibility = View.GONE
        findViewById<TextView>(R.id.now_playing_title).text = track.title
        findViewById<TextView>(R.id.now_playing_artist).text = track.artist
        val (colorRes, textRes) = if (track.playing) {
            R.color.status_ok to R.string.now_playing_playing
        } else {
            R.color.text_hint to R.string.now_playing_paused
        }
        val color = getColor(colorRes)
        findViewById<TextView>(R.id.now_playing_state).apply {
            text = getString(textRes)
            setTextColor(color)
            // 胶囊底色:同色 12% 透明度(SRC_IN 保留透明度),与文字同色系
            backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, CHIP_ALPHA))
        }
    }

    /**
     * 连接状态 → 语义色(绿=已连接,橙=连接/重连中/待授权,红=认证失败,灰=未启用/已退出)。
     */
    private fun statusColor(status: ConnectionUiStatus): Int = getColor(
        when (status) {
            ConnectionUiStatus.CONNECTED -> R.color.status_ok
            ConnectionUiStatus.CONNECTING, ConnectionUiStatus.RECONNECTING,
            ConnectionUiStatus.NEEDS_ACCESS -> R.color.status_warning
            ConnectionUiStatus.AUTH_FAILED -> R.color.status_error
            ConnectionUiStatus.DISABLED, ConnectionUiStatus.EXITED -> R.color.text_hint
        }
    )

    /** 已连接时英雄卡光环的呼吸动画(缩放往复);懒创建,可安全重复 start。 */
    private fun pulse(): Animation {
        heroPulse?.let { return it }
        return ScaleAnimation(
            1f, 1.35f, 1f, 1.35f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 900
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }.also { heroPulse = it }
    }

    /** 通知监听权限引导卡:仅未授权时显示,授权后整卡消失。 */
    private fun updateStatus() {
        val isEnabled = NotificationAccess.granted(this)
        findViewById<View>(R.id.guide_card).visibility = if (isEnabled) View.GONE else View.VISIBLE
    }

    // ==================== 折叠卡 ====================

    /**
     * 组装折叠设置卡:点击标题行切换 body 可见性并旋转箭头。
     * 首次使用(未配置电脑地址)时自动展开 LAN 卡降低上手成本。
     */
    private fun setupCollapsibleSections() {
        lanBody = findViewById(R.id.lan_body)
        lanChevron = findViewById(R.id.lan_chevron)
        bindCollapsible(
            findViewById(R.id.lan_header), lanBody, lanChevron,
            expanded = LanSettings.host(this).isBlank()
        )
    }

    /** 绑定单个折叠卡:初态 + 点击展开/收起(箭头 0°=收起指向右,90°=展开指向下)。 */
    private fun bindCollapsible(header: View, body: View, chevron: View, expanded: Boolean) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 90f else 0f
        header.setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (expand) 90f else 0f).setDuration(150).start()
        }
    }

    /** 扫码配对落地时自动展开 LAN 卡,让用户看到已填入的配置与连接进度。 */
    private fun expandLanSection() {
        lanBody.visibility = View.VISIBLE
        lanChevron.animate().rotation(90f).setDuration(150).start()
    }

    // ==================== 局域网推送设置区 ====================

    /**
     * 组装 LAN 推送卡:读取配置、重新生成 Token、保存重连、一次性连接测试。
     * LAN 设置独立存放("lan_config"),保存后通过 requestRebind 重启服务生效。
     */
    private fun setupLanSection() {
        val lanPrefs = LanSettings.prefs(this)
        val enabledSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_enabled_switch)
        val hostEdit = findViewById<android.widget.EditText>(R.id.lan_host_edit)
        val portEdit = findViewById<android.widget.EditText>(R.id.lan_port_edit)
        val tokenEdit = findViewById<android.widget.EditText>(R.id.lan_token_edit)
        val regenButton = findViewById<Button>(R.id.lan_regen_token_button)
        val artworkSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_artwork_switch)
        val diagnosticSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_diagnostic_switch)
        val blacklistEdit = findViewById<android.widget.EditText>(R.id.lan_blacklist_edit)
        val testButton = findViewById<Button>(R.id.lan_test_button)
        val saveButton = findViewById<Button>(R.id.lan_save_button)

        enabledSwitch.isChecked = lanPrefs.getBoolean("lan_enabled", false)
        hostEdit.setText(lanPrefs.getString("lan_host", ""))
        val savedPort = lanPrefs.getInt("lan_port", LanSettings.DEFAULT_PORT)
        portEdit.setText(savedPort.toString())
        tokenEdit.setText(LanSettings.token(this))
        artworkSwitch.isChecked = lanPrefs.getBoolean("lan_send_artwork", true)
        diagnosticSwitch.isChecked = LanSettings.isDiagnosticLoggingEnabled(this)
        blacklistEdit.setText(lanPrefs.getString("lan_app_blacklist", ""))

        findViewById<Button>(R.id.lan_scan_button).setOnClickListener {
            pairScanLauncher.launch(QrScanActivity.intent(this))
        }

        regenButton.setOnClickListener {
            tokenEdit.setText(LanSettings.generateToken())
        }

        saveButton.setOnClickListener { saveLanSettings() }

        testButton.setOnClickListener { runLanConnectionTest() }
    }

    /**
     * 扫码成功的落地动作:优选与手机同网段的地址填入表单(Payload 按 PC 网卡
     * 枚举顺序编入 IP,虚拟网卡常排在真实网卡前面),其余地址存为备选供重连轮换;
     * 打开 LAN 开关并保存重启服务,与手动"保存并重连"完全同一条路径。
     * 配对成功后顺势完成:展开 LAN 卡 → 前台服务启动 → 通知权限 → 保活引导。
     */
    private fun applyQrPairing(addresses: List<String>, port: Int, token: String) {
        val host = PairAddressPicker.pick(addresses, PairAddressPicker.localIpv4())
            ?: addresses.first()
        findViewById<android.widget.EditText>(R.id.lan_host_edit).setText(host)
        findViewById<android.widget.EditText>(R.id.lan_port_edit).setText(port.toString())
        findViewById<android.widget.EditText>(R.id.lan_token_edit).setText(token)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_enabled_switch).isChecked = true
        // 先落备选地址(同时写入首选),再走统一的保存重启路径
        LanSettings.savePairingHosts(this, host, addresses)
        saveLanSettings()
        expandLanSection()
        findViewById<TextView>(R.id.lan_test_result_text).text =
            getString(R.string.lan_pair_success, "$host:$port")
        maybeRequestNotificationPermission()
        maybeRunKeepAliveOnboarding()
    }

    /**
     * 读取 LAN 表单并持久化,随后 requestRebind 重启监听服务使配置立即生效
     * (保存按钮与扫码导入共用;服务 onCreate 会按开关建立/不建立连接)。
     * 「保存并重连」是显式的恢复动作:同时清除完整退出标记。
     */
    private fun saveLanSettings() {
        val lanPrefs = LanSettings.prefs(this)
        val enabledSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_enabled_switch)
        val hostEdit = findViewById<android.widget.EditText>(R.id.lan_host_edit)
        val portEdit = findViewById<android.widget.EditText>(R.id.lan_port_edit)
        val tokenEdit = findViewById<android.widget.EditText>(R.id.lan_token_edit)
        val artworkSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_artwork_switch)
        val diagnosticSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_diagnostic_switch)
        val blacklistEdit = findViewById<android.widget.EditText>(R.id.lan_blacklist_edit)

        val port = portEdit.text.toString().trim().toIntOrNull() ?: LanSettings.DEFAULT_PORT
        // 手动改了 PC 地址时清掉扫码带来的备选列表,避免轮换到失效的旧地址
        val newHost = hostEdit.text.toString().trim()
        if (newHost != LanSettings.host(this)) LanSettings.clearHostAlternates(this)
        lanPrefs.edit()
            .putBoolean("lan_enabled", enabledSwitch.isChecked)
            .putString("lan_host", newHost)
            .putInt("lan_port", port)
            .putString("lan_token", tokenEdit.text.toString().trim())
            .putBoolean("lan_send_artwork", artworkSwitch.isChecked)
            .putBoolean(LanSettings.KEY_DIAGNOSTIC_LOG, diagnosticSwitch.isChecked)
            .putString("lan_app_blacklist", blacklistEdit.text.toString().trim())
            .apply()

        // 「保存并重连」属显式恢复动作:清除完整退出标记,随后的重启服务才不会被关门
        AppExit.setExited(this, false)
        refreshExitSection()

        // 重启监听服务,使新配置立即生效;4 秒后复查一次,兜底系统忽略/延迟 rebind
        restartListenerService()
        scheduleRebindFallback()
        Toast.makeText(this, R.string.lan_saved_toast, Toast.LENGTH_SHORT).show()

        // 英雄卡目标地址行跟随新配置;连接状态等服务的重启事件回报后自然刷新
        updateHeroTarget()

        // 关闭 LAN 开关时同步停掉前台保活,避免通知栏残留"未配对"状态
        if (!enabledSwitch.isChecked) {
            stopService(Intent(this, RelayForegroundService::class.java))
        } else {
            maybeStartForegroundService()
        }
    }

    // ==================== 监听服务重启兜底 ====================

    /**
     * 让监听服务按最新配置(重)启,双保险:
     * - requestRebind(API 24+):服务已被系统绑定时,请求销毁重建;
     * - startService:服务**未被系统绑定**时 requestRebind 是静默无效的(部分 ROM
     *   还会直接忽略 rebind 请求),显式拉起实例让 onCreate/onStartCommand 立即
     *   按最新配置建立连接 —— 扫码配对后无需重启 App 即可连上。
     * 两路对已运行实例都安全(重复 start 幂等,onStartCommand 按当前配置重连)。
     */
    private fun restartListenerService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, MediaSessionListenerService::class.java)
            )
        }
        try {
            startService(Intent(this, MediaSessionListenerService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Start listener service failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * rebind 兜底:配置已启用但服务侧仍停在"未启用"(保存后的 rebind 被系统忽略/
     * 延迟,或授权刚恢复但系统尚未重绑)时,补发一次 rebind 把监听服务拉起来。
     * 保护条件:未授权时重试无效(系统不绑定监听服务,界面已按"需通知使用权"引导);
     * 状态已离开"未启用"(连接中/已连接/重连中/认证失败)说明服务已带新配置运行,
     * 再发 rebind 反而会打断正常连接。
     */
    private fun ensureListenerServiceRestarted() {
        // 完整退出态下不补发 rebind,保持"退出不再启动"语义
        if (AppExit.isExited(this)) return
        if (!LanSettings.isEnabled(this) || LanSettings.host(this).isBlank()) return
        if (!NotificationAccess.granted(this)) return
        if (!LanConnectionState.snapshot().second.startsWith("未启用")) return
        restartListenerService()
    }

    /** 保存配置后的延时复查(给系统 rebind 留出销毁重建的窗口);重复保存时重置计时。 */
    private fun scheduleRebindFallback() {
        mainHandler.removeCallbacks(rebindFallback)
        mainHandler.postDelayed(rebindFallback, REBIND_FALLBACK_DELAY_MS)
    }

    /**
     * 一次性连接测试:按当前输入(不先保存)发起握手,等待 hello_ack / error,
     * 6 秒超时;测试实例不自动重连,结束后立即释放。
     */
    private fun runLanConnectionTest() {
        if (lanTesting) return
        val hostEdit = findViewById<android.widget.EditText>(R.id.lan_host_edit)
        val portEdit = findViewById<android.widget.EditText>(R.id.lan_port_edit)
        val tokenEdit = findViewById<android.widget.EditText>(R.id.lan_token_edit)
        val resultText = findViewById<TextView>(R.id.lan_test_result_text)

        val host = hostEdit.text.toString().trim()
        val port = portEdit.text.toString().trim().toIntOrNull() ?: LanSettings.DEFAULT_PORT
        val token = tokenEdit.text.toString().trim().ifBlank { LanSettings.token(this) }
        if (host.isBlank()) {
            resultText.text = getString(R.string.lan_test_need_host)
            resultText.setTextColor(getColor(R.color.status_error))
            return
        }

        lanTesting = true
        lanTestTimeoutPending = true
        resultText.text = getString(R.string.lan_test_connecting, host, port)
        resultText.setTextColor(getColor(R.color.text_secondary))

        mainHandler.postDelayed({
            lanTestTimeoutPending = false
            if (lanTesting) {
                finishLanTest(getString(R.string.lan_test_timeout, "$host:$port"), true)
            }
        }, 6000)

        lanTestClient = LanPushClient(
            this,
            listOf(host),
            port,
            object : LanPushClient.Listener {
                override fun onConnected() {
                    val hello = JSONObject().apply {
                        put("type", "hello")
                        put("v", 1)
                        put("token", token)
                        put("deviceId", "ui-test")
                        put("deviceName", android.os.Build.MODEL ?: "Android")
                        put("appVersion", "test")
                    }
                    lanTestClient?.send(hello.toString())
                }

                override fun onDisconnected(reason: String) {
                    if (lanTestTimeoutPending) return // 让超时分支给出更友好的提示
                    finishLanTest(getString(R.string.lan_test_failed, reason), true)
                }

                override fun onMessage(message: JSONObject) {
                    if (lanTestTimeoutPending) {
                        lanTestTimeoutPending = false
                        mainHandler.removeCallbacksAndMessages(null)
                    }
                    when (message.optString("type")) {
                        "hello_ack" -> finishLanTest(getString(R.string.lan_test_ok), false)
                        "error" -> finishLanTest(
                            getString(R.string.lan_test_rejected, message.optString("code")),
                            true
                        )
                    }
                }
            },
            autoReconnect = false
        ).also { it.connect() }
    }

    /** 结束一次测试:展示结果、释放测试客户端。 */
    private fun finishLanTest(message: String, isError: Boolean) {
        lanTesting = false
        lanTestClient?.shutdown()
        lanTestClient = null
        val resultText = findViewById<TextView>(R.id.lan_test_result_text)
        resultText.text = message
        resultText.setTextColor(getColor(if (isError) R.color.status_error else R.color.status_ok))
    }

    // ==================== 前台服务与权限引导 ====================

    /** LAN 已启用且配置了地址时,前台启动保活服务;失败仅提示,不阻塞设置流程。
     *  完整退出态下为空操作(退出后重开应用、回到前台都不会再自动后台运行)。 */
    private fun maybeStartForegroundService() {
        if (AppExit.isExited(this)) return
        if (!LanSettings.isEnabled(this) || LanSettings.host(this).isBlank()) return
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this, RelayForegroundService.startIntent(this)
            )
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.fg_start_failed, e.javaClass.simpleName), Toast.LENGTH_SHORT)
                .show()
        }
    }

    /** Android 13+ 申请 POST_NOTIFICATIONS;其他版本无操作。 */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ==================== 后台保活 ====================

    /**
     * 组装「后台保活」折叠卡(参考 Gotify 的保活引导):按当前 ROM 填入指引文案、
     * 绑定三项跳转(电池优化弹窗 / ROM 自启动页 / 本应用通知设置)与两个用户确认开关
     * (自启动、多任务加锁没有系统 API 可查,只能由用户勾选)。
     * 原生 ROM 下自启动项退化为"无需设置"、加锁整行隐藏,避免展示做不到的步骤。
     */
    private fun setupKeepAliveSection() {
        val flavor = RomFlavor.detect(Build.MANUFACTURER, Build.BRAND)
        keepAliveFlavor = flavor
        keepAliveBody = findViewById(R.id.ka_body)
        keepAliveChevron = findViewById(R.id.ka_chevron)
        bindCollapsible(
            findViewById(R.id.ka_header), keepAliveBody, keepAliveChevron,
            expanded = false
        )

        findViewById<TextView>(R.id.ka_rom_label).text =
            getString(R.string.ka_rom_label, flavor.label)
        findViewById<TextView>(R.id.ka_desc_autostart).setText(flavor.autostartStepsRes)
        findViewById<TextView>(R.id.ka_desc_recents).setText(flavor.lockStepsRes)

        findViewById<Button>(R.id.ka_btn_battery).setOnClickListener {
            if (!KeepAliveIntents.requestIgnoreBatteryOptimizations(this)) {
                Toast.makeText(this, R.string.battery_dialog_manual_fallback, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.ka_btn_autostart).setOnClickListener {
            if (!KeepAliveIntents.openAutostart(this, flavor)) {
                Toast.makeText(this, R.string.ka_autostart_no_entry, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.ka_btn_notification).setOnClickListener {
            KeepAliveIntents.openAppNotificationSettings(this)
        }

        bindConfirmSwitch(
            R.id.ka_switch_autostart, KeepAliveChecks.autostartConfirmed(this)
        ) { KeepAliveChecks.setAutostartConfirmed(this, it) }
        bindConfirmSwitch(
            R.id.ka_switch_recents, KeepAliveChecks.recentsConfirmed(this)
        ) { KeepAliveChecks.setRecentsConfirmed(this, it) }

        if (!flavor.needsAutostart) {
            findViewById<View>(R.id.ka_btn_autostart).visibility = View.GONE
            findViewById<View>(R.id.ka_switch_autostart).visibility = View.GONE
        }
        if (!flavor.needsLockHint) {
            findViewById<View>(R.id.ka_item_recents).visibility = View.GONE
        }
        updateKeepAliveStatus()
    }

    /** 用户确认开关的统一绑定:写入持久化后立即刷新自检,不依赖 onResume 才变色。 */
    private fun bindConfirmSwitch(id: Int, initial: Boolean, setter: (Boolean) -> Unit) {
        val switch = findViewById<androidx.appcompat.widget.SwitchCompat>(id)
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, checked ->
            setter(checked)
            updateKeepAliveStatus()
        }
    }

    /** 当前四项保活自检的快照(卡片展示与配对后的引导弹窗共用同一判定)。 */
    private fun currentKeepAliveCheck(): KeepAliveCheck = KeepAliveCheck.evaluate(
        batteryOk = KeepAliveChecks.batteryOk(this),
        notificationOk = KeepAliveChecks.notificationOk(this),
        autostartConfirmed = KeepAliveChecks.autostartConfirmed(this),
        recentsConfirmed = KeepAliveChecks.recentsConfirmed(this),
        flavor = keepAliveFlavor,
    )

    /**
     * 自检刷新:状态点(绿=已完成/橙=待完成/灰=无需设置)、单项状态文案、
     * 页头汇总("x/4 项待完成")与总结行。onResume、开关勾选、跳转设置返回后都会重跑。
     */
    private fun updateKeepAliveStatus() {
        val check = currentKeepAliveCheck()
        applyKeepAliveItem(R.id.ka_dot_battery, R.id.ka_state_battery, check.batteryOk, needed = true)
        applyKeepAliveItem(
            R.id.ka_dot_autostart, R.id.ka_state_autostart,
            check.autostartOk, keepAliveFlavor.needsAutostart
        )
        applyKeepAliveItem(
            R.id.ka_dot_recents, R.id.ka_state_recents,
            check.recentsOk, keepAliveFlavor.needsLockHint
        )
        applyKeepAliveItem(
            R.id.ka_dot_notification, R.id.ka_state_notification,
            check.notificationOk, needed = true
        )

        val pending = check.pending()
        findViewById<View>(R.id.ka_header_dot).backgroundTintList =
            ColorStateList.valueOf(
                getColor(if (check.allDone) R.color.status_ok else R.color.status_warning)
            )
        findViewById<TextView>(R.id.ka_header_subtitle).text =
            if (check.allDone) getString(R.string.ka_header_all_done)
            else getString(R.string.ka_header_pending, pending.size, TOTAL_KEEPALIVE_ITEMS)
        findViewById<TextView>(R.id.ka_summary).text =
            if (check.allDone) getString(R.string.ka_summary_all_done)
            else getString(R.string.ka_summary_pending, pending.size)
    }

    /**
     * 单项自检展示:needed=false(原生 ROM 无此项)显示灰点+"无需设置";
     * 否则绿="已完成"、橙="待完成",状态文字用对应颜色强调。
     */
    private fun applyKeepAliveItem(dotId: Int, stateId: Int, ok: Boolean, needed: Boolean) {
        val (colorRes, textRes) = when {
            !needed -> R.color.text_hint to R.string.ka_state_not_needed
            ok -> R.color.status_ok to R.string.ka_state_done
            else -> R.color.status_warning to R.string.ka_state_pending
        }
        findViewById<View>(dotId).backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
        findViewById<TextView>(stateId).apply {
            text = getString(textRes)
            setTextColor(getColor(colorRes))
        }
    }

    /**
     * 配对成功后的保活引导(替代 0.3.0 的一次性电池弹窗 + 泛化 ROM 提示):
     * 按 电池优化 → 自启动/后台活动 → 多任务加锁 → 常驻通知 的顺序,
     * 逐个弹出当前未完成项的说明与跳转;每个弹窗都可"稍后再说"跳过,
     * 或"不再提示"永久关闭(「后台保活」卡内随时可继续完成)。
     */
    private fun maybeRunKeepAliveOnboarding() {
        if (KeepAliveChecks.onboardingDismissed(this)) return
        val steps = currentKeepAliveCheck().pending()
        if (steps.isNotEmpty()) showKeepAliveStep(steps, 0)
    }

    /** 递归展示引导步骤:正向动作后进入下一步;跳过/不再提示同样推进或终止整个链条。 */
    private fun showKeepAliveStep(steps: List<KeepAliveStep>, index: Int) {
        val step = steps.getOrNull(index)
        if (step == null) {
            updateKeepAliveStatus()
            return
        }
        val builder = AlertDialog.Builder(this)
        when (step) {
            KeepAliveStep.BATTERY -> builder
                .setTitle(R.string.battery_dialog_title)
                .setMessage(R.string.battery_dialog_message)
                .setPositiveButton(R.string.battery_dialog_go) { _, _ ->
                    if (!KeepAliveIntents.requestIgnoreBatteryOptimizations(this)) {
                        Toast.makeText(this, R.string.battery_dialog_manual_fallback, Toast.LENGTH_LONG).show()
                    }
                    showKeepAliveStep(steps, index + 1)
                }
            KeepAliveStep.AUTOSTART -> builder
                .setTitle(R.string.ka_step_autostart_title)
                .setMessage(keepAliveFlavor.autostartStepsRes)
                .setPositiveButton(R.string.ka_action_go) { _, _ ->
                    if (!KeepAliveIntents.openAutostart(this, keepAliveFlavor)) {
                        Toast.makeText(this, R.string.ka_autostart_no_entry, Toast.LENGTH_LONG).show()
                    }
                    showKeepAliveStep(steps, index + 1)
                }
            KeepAliveStep.LOCK_RECENTS -> builder
                .setTitle(R.string.ka_step_lock_title)
                .setMessage(keepAliveFlavor.lockStepsRes)
                .setPositiveButton(R.string.ka_action_locked) { _, _ ->
                    // 加锁无法程序验证,以用户点"已加锁"作为确认
                    KeepAliveChecks.setRecentsConfirmed(this, true)
                    showKeepAliveStep(steps, index + 1)
                }
            KeepAliveStep.NOTIFICATION -> builder
                .setTitle(R.string.ka_step_notification_title)
                .setMessage(R.string.ka_notification_desc)
                .setPositiveButton(R.string.ka_action_notification) { _, _ ->
                    KeepAliveIntents.openAppNotificationSettings(this)
                    showKeepAliveStep(steps, index + 1)
                }
        }
        builder
            .setNeutralButton(R.string.ka_action_never) { _, _ ->
                KeepAliveChecks.setOnboardingDismissed(this)
                Toast.makeText(this, R.string.ka_onboarding_dismissed_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.battery_dialog_later) { _, _ ->
                showKeepAliveStep(steps, index + 1)
            }
            .show()
    }

    // ==================== 完整退出与恢复 ====================

    /**
     * 页底退出入口:未退出时点击弹确认后执行完整退出([AppExit.performExit]);
     * 已退出时按钮变为「重新启动推送」,点击清除退出标记并按当前配置恢复服务。
     */
    private fun setupExitSection() {
        findViewById<Button>(R.id.exit_button).setOnClickListener { onExitButtonClicked() }
        refreshExitSection()
    }

    /** 退出/恢复按钮与说明行的文案切换(进入页面、状态变化、恢复后都会调用)。 */
    private fun refreshExitSection() {
        val exited = AppExit.isExited(this)
        findViewById<Button>(R.id.exit_button).setText(
            if (exited) R.string.exit_resume_button else R.string.exit_button
        )
        findViewById<TextView>(R.id.exit_hint).setText(
            if (exited) R.string.exit_hint_exited else R.string.exit_hint
        )
    }

    private fun onExitButtonClicked() {
        if (AppExit.isExited(this)) {
            resumeFromExit()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(R.string.exit_confirm_message)
            .setPositiveButton(R.string.exit_confirm_ok) { _, _ -> performExitFromUi() }
            .setNegativeButton(R.string.exit_confirm_cancel, null)
            .show()
    }

    /**
     * 确认后的退出落地:停掉全部通道并落盘退出标记,随后立即把界面刷到"已退出"
     * 状态(不依赖服务 ACTION_EXIT 的异步回报,防止 startService 失败时界面滞留旧状态)。
     */
    private fun performExitFromUi() {
        AppExit.performExit(this)
        refreshExitSection()
        applyConnectionState(false, getString(R.string.status_exited))
        Toast.makeText(this, R.string.exit_done_toast, Toast.LENGTH_LONG).show()
    }

    /**
     * 从退出态恢复:清标记后按当前配置恢复服务。不走 [ensureListenerServiceRestarted] ——
     * 退出态下状态文本是"已退出"而非"未启用",过不了它的补发条件;这里显式重启,
     * 与"保存并重连"同一条路径。界面随服务重启后的状态回报自然刷新。
     */
    private fun resumeFromExit() {
        AppExit.setExited(this, false)
        refreshExitSection()
        restartListenerService()
        maybeStartForegroundService()
        Toast.makeText(this, R.string.exit_resume_toast, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 当前版本名(页脚展示用);查询失败时返回"?",不阻塞界面。 */
    private fun currentVersionName(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        } ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private companion object {
        private const val TAG = "MainActivity"

        /** 自检总项数,仅用于页头"x/4 项待完成"的展示。 */
        const val TOTAL_KEEPALIVE_ITEMS = 4

        /** 保存配置后复查 rebind 是否生效的延时(覆盖系统销毁重建监听服务的窗口)。 */
        const val REBIND_FALLBACK_DELAY_MS = 4_000L

        /** 英雄卡光环的状态色透明度(30%);播放状态胶囊为 12%。 */
        const val HALO_ALPHA = 78
        const val CHIP_ALPHA = 31
    }
}
