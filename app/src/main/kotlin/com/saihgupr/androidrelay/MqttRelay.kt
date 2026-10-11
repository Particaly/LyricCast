package com.saihgupr.androidrelay

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttRelay(private val context: Context) {
    private val prefs = context.getSharedPreferences("mqtt_config", Context.MODE_PRIVATE)
    
    private val serverUri: String
        get() {
            val host = prefs.getString("broker_ip", "192.168.1.199")
            return "tcp://$host:1883"
        }
        
    private val defaultTopic: String
        get() = prefs.getString("topic", "android_tv/playback_state") ?: "android_tv/playback_state"

    private val clientId = "AndroidTVRelay_${android.os.Build.ID}_${java.util.UUID.randomUUID().toString().take(4)}"
    private var mqttClient: MqttClient? = null

    interface MqttStatusListener {
        /** 连接过程/结果文案(已本地化为中文),UI 可直接展示。 */
        fun onStatusUpdate(message: String, isError: Boolean = false)

        /**
         * 连接建立(Paho connectComplete 回调,含自动重连)。
         * UI 判定"测试成功"必须用本回调,不得依赖 onStatusUpdate 的文案子串。
         */
        fun onConnected() {}
    }

    fun connect(listener: MqttStatusListener? = null) {
        try {
            Thread {
                try {
                    val uri = serverUri
                    val cid = clientId
                    Log.i("AndroidRelay", "Configuring MQTT client for $uri with ID $cid")
                    mqttClient = MqttClient(uri, cid, MemoryPersistence())
                    val options = MqttConnectOptions()
                    options.isAutomaticReconnect = true
                    options.isCleanSession = true
                    options.connectionTimeout = 10

                    if (prefs.getBoolean("use_auth", false)) {
                        options.userName = prefs.getString("username", "")
                        options.password = prefs.getString("password", "")?.toCharArray()
                    }
                    
                    mqttClient?.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                            Log.i("AndroidRelay", "Connect successful to $serverURI (reconnect: $reconnect)")
                            listener?.onConnected()
                            listener?.onStatusUpdate("已连接到 $serverURI")
                        }
                        override fun connectionLost(cause: Throwable?) {
                            Log.w("AndroidRelay", "Connection lost: ${cause?.message}")
                            listener?.onStatusUpdate("连接断开:${cause?.message}", true)
                        }
                        override fun messageArrived(topic: String?, message: MqttMessage?) {}
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })

                    Log.i("AndroidRelay", "Attempting to connect to $uri...")
                    listener?.onStatusUpdate("正在连接 $uri …")
                    mqttClient?.connect(options)
                } catch (e: MqttException) {
                    Log.e("AndroidRelay", "MqttException during connect: ${e.message}", e)
                    listener?.onStatusUpdate("MQTT 错误:${e.message}", true)
                } catch (e: Exception) {
                    Log.e("AndroidRelay", "Unexpected error during connect: ${e.message}", e)
                    listener?.onStatusUpdate("错误:${e.message}", true)
                }
            }.start()
        } catch (e: Exception) {
            Log.e("AndroidRelay", "Error spawning connect thread", e)
            listener?.onStatusUpdate("连接线程启动失败", true)
        }
    }

    fun publish(topic: String? = null, payload: String) {
        try {
            val client = mqttClient
            if (client != null && client.isConnected) {
                val targetTopic = topic ?: defaultTopic
                Log.d("AndroidRelay", "Publishing to $targetTopic: $payload")
                val message = MqttMessage(payload.toByteArray())
                message.qos = 1
                client.publish(targetTopic, message)
            } else {
                Log.w("AndroidRelay", "Cannot publish: Client not connected")
            }
        } catch (e: Exception) {
            Log.e("AndroidRelay", "Error publishing", e)
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
        } catch (e: Exception) {
            Log.e("AndroidRelay", "Error disconnecting", e)
        }
    }
}
