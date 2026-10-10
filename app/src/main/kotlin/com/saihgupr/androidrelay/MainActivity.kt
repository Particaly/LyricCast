package com.saihgupr.androidrelay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // LAN 测试连接的一次性客户端与状态(均在主线程访问)
    private var lanTestClient: LanPushClient? = null
    private var lanTesting = false
    private var lanTestTimeoutPending = false

    // LAN 连接状态监听句柄(onResume 注册 / onPause 注销)
    private var lanStateListener: ((Boolean, String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val permissionButton = findViewById<Button>(R.id.permission_button)
        val mqttBrokerEdit = findViewById<android.widget.EditText>(R.id.mqtt_broker_edit)
        val mqttTopicEdit = findViewById<android.widget.EditText>(R.id.mqtt_topic_edit)
        val mqttAuthSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.mqtt_auth_switch)
        val mqttAuthContainer = findViewById<android.widget.LinearLayout>(R.id.mqtt_auth_container)
        val mqttUsernameEdit = findViewById<android.widget.EditText>(R.id.mqtt_username_edit)
        val mqttPasswordEdit = findViewById<android.widget.EditText>(R.id.mqtt_password_edit)
        val saveButton = findViewById<Button>(R.id.save_config_button)
        val testButton = findViewById<Button>(R.id.test_mqtt_button)

        // Load existing config
        val prefs = getSharedPreferences("mqtt_config", MODE_PRIVATE)
        mqttBrokerEdit.setText(prefs.getString("broker_ip", "192.168.1.199"))
        mqttTopicEdit.setText(prefs.getString("topic", "android_tv/playback_state"))

        val useAuth = prefs.getBoolean("use_auth", false)
        mqttAuthSwitch.isChecked = useAuth
        mqttAuthContainer.visibility = if (useAuth) android.view.View.VISIBLE else android.view.View.GONE
        mqttUsernameEdit.setText(prefs.getString("username", ""))
        mqttPasswordEdit.setText(prefs.getString("password", ""))

        mqttAuthSwitch.setOnCheckedChangeListener { _, isChecked ->
            mqttAuthContainer.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        permissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        saveButton.setOnClickListener {
            val ip = mqttBrokerEdit.text.toString()
            val topic = mqttTopicEdit.text.toString()
            val auth = mqttAuthSwitch.isChecked
            val user = mqttUsernameEdit.text.toString()
            val pass = mqttPasswordEdit.text.toString()

            prefs.edit()
                .putString("broker_ip", ip)
                .putString("topic", topic)
                .putBoolean("use_auth", auth)
                .putString("username", user)
                .putString("password", pass)
                .apply()

            // Restart service to pick up changes
            val componentName = ComponentName(this, MediaSessionListenerService::class.java)
            android.service.notification.NotificationListenerService.requestRebind(componentName)
            android.widget.Toast.makeText(this, "Relay Service Re-initialized", android.widget.Toast.LENGTH_SHORT).show()
        }

        val testResultText = findViewById<TextView>(R.id.test_result_text)
        var isTesting = false

        testButton.setOnClickListener {
            if (isTesting) return@setOnClickListener

            isTesting = true
            val mqtt = MqttRelay(this)
            testResultText.text = "Initializing..."
            testResultText.setTextColor(getColor(R.color.text_secondary))

            mqtt.connect(object : MqttRelay.MqttStatusListener {
                override fun onStatusUpdate(message: String, isError: Boolean) {
                    runOnUiThread {
                        testResultText.text = message
                        if (isError) {
                            testResultText.setTextColor(getColor(R.color.status_error))
                            isTesting = false
                        } else if (message.contains("Connected")) {
                            testResultText.setTextColor(getColor(R.color.status_ok))
                            mqtt.publish(null, "{\"status\": \"test\", \"message\": \"Hello from Relay UI\"}")
                            isTesting = false
                        }
                    }
                }
            })
        }

        setupLanSection()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // 实时展示 LAN 连接状态(由服务内的 LanPushManager 写入)
        val listener = { connected: Boolean, text: String ->
            runOnUiThread {
                findViewById<TextView>(R.id.lan_status_text).text = text
                findViewById<TextView>(R.id.lan_status_text).setTextColor(
                    getColor(if (connected) R.color.status_ok else R.color.text_secondary)
                )
            }
        }
        lanStateListener = listener
        LanConnectionState.addListener(listener)
        val (connected, text) = LanConnectionState.snapshot()
        listener(connected, text)
    }

    override fun onPause() {
        super.onPause()
        lanStateListener?.let { LanConnectionState.removeListener(it) }
        lanStateListener = null
    }

    // ==================== LAN Push 设置区 ====================

    /**
     * 组装 LAN 推送卡片:读取配置、重新生成 Token、保存重连、一次性连接测试。
     * LAN 设置独立存放("lan_config"),保存后通过 requestRebind 重启服务生效,
     * 与 MQTT 的"Save and Restart Service"互不干扰。
     */
    private fun setupLanSection() {
        val lanPrefs = LanSettings.prefs(this)
        val enabledSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_enabled_switch)
        val hostEdit = findViewById<android.widget.EditText>(R.id.lan_host_edit)
        val portEdit = findViewById<android.widget.EditText>(R.id.lan_port_edit)
        val tokenEdit = findViewById<android.widget.EditText>(R.id.lan_token_edit)
        val regenButton = findViewById<Button>(R.id.lan_regen_token_button)
        val artworkSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_artwork_switch)
        val blacklistEdit = findViewById<android.widget.EditText>(R.id.lan_blacklist_edit)
        val testButton = findViewById<Button>(R.id.lan_test_button)
        val saveButton = findViewById<Button>(R.id.lan_save_button)

        enabledSwitch.isChecked = lanPrefs.getBoolean("lan_enabled", false)
        hostEdit.setText(lanPrefs.getString("lan_host", ""))
        val savedPort = lanPrefs.getInt("lan_port", LanSettings.DEFAULT_PORT)
        portEdit.setText(savedPort.toString())
        tokenEdit.setText(LanSettings.token(this))
        artworkSwitch.isChecked = lanPrefs.getBoolean("lan_send_artwork", true)
        blacklistEdit.setText(lanPrefs.getString("lan_app_blacklist", ""))

        regenButton.setOnClickListener {
            tokenEdit.setText(LanSettings.generateToken())
        }

        saveButton.setOnClickListener {
            val port = portEdit.text.toString().trim().toIntOrNull() ?: LanSettings.DEFAULT_PORT
            lanPrefs.edit()
                .putBoolean("lan_enabled", enabledSwitch.isChecked)
                .putString("lan_host", hostEdit.text.toString().trim())
                .putInt("lan_port", port)
                .putString("lan_token", tokenEdit.text.toString().trim())
                .putBoolean("lan_send_artwork", artworkSwitch.isChecked)
                .putString("lan_app_blacklist", blacklistEdit.text.toString().trim())
                .apply()

            // 重启监听服务,使新配置立即生效(服务 onCreate 会按开关建立/不建立连接)
            val componentName = ComponentName(this, MediaSessionListenerService::class.java)
            android.service.notification.NotificationListenerService.requestRebind(componentName)
            android.widget.Toast.makeText(this, "LAN settings saved, service restarted", android.widget.Toast.LENGTH_SHORT).show()
        }

        testButton.setOnClickListener { runLanConnectionTest() }
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
            resultText.text = "Please enter the PC address first."
            resultText.setTextColor(getColor(R.color.status_error))
            return
        }

        lanTesting = true
        lanTestTimeoutPending = true
        resultText.text = "Connecting to $host:$port ..."
        resultText.setTextColor(getColor(R.color.text_secondary))

        mainHandler.postDelayed({
            lanTestTimeoutPending = false
            if (lanTesting) {
                finishLanTest("Timeout: no response from $host:$port (check IP/port and PC firewall)", true)
            }
        }, 6000)

        lanTestClient = LanPushClient(
            this,
            host,
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
                    finishLanTest("Cannot connect: $reason", true)
                }

                override fun onMessage(message: JSONObject) {
                    if (lanTestTimeoutPending) {
                        lanTestTimeoutPending = false
                        mainHandler.removeCallbacksAndMessages(null)
                    }
                    when (message.optString("type")) {
                        "hello_ack" -> finishLanTest("Connected! Server acknowledged the token.", false)
                        "error" -> finishLanTest(
                            "Rejected by server (${message.optString("code")}) — check the token.",
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

    private fun updateStatus() {
        val statusText = findViewById<TextView>(R.id.status_text)
        val permissionButton = findViewById<Button>(R.id.permission_button)
        val adbInstructions = findViewById<TextView>(R.id.adb_instructions)

        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isEnabled = enabledListeners?.contains(packageName) == true

        if (isEnabled) {
            statusText.text = "Connected and Authorized"
            statusText.setTextColor(getColor(R.color.status_ok))
            permissionButton.visibility = android.view.View.GONE
            adbInstructions.visibility = android.view.View.GONE
        } else {
            statusText.text = "Permission Required"
            statusText.setTextColor(getColor(R.color.status_error))
            permissionButton.visibility = android.view.View.VISIBLE
            adbInstructions.visibility = android.view.View.VISIBLE
        }
    }
}
