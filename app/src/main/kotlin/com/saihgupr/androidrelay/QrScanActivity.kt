package com.saihgupr.androidrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 扫码配对页:对准 PC 插件"手机接入"卡片显示的配对二维码,
 * 解析成功([PairingQr.parse])后通过 RESULT_OK 把地址/端口/Token 回给 MainActivity。
 *
 * 依赖 CameraX(预览 + YUV 分析)与 ZXing core(仅 QR);解析在单线程 executor
 * 上执行,结果 post 回主线程,成功即返回并停止分析,失败去抖后继续扫。
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hintText: TextView
    private lateinit var statusText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisExecutor: ExecutorService? = null

    /** 已成功交付结果(防止重复返回);未知内容提示的去抖用。 */
    @Volatile private var delivered = false
    private var lastRejectedText: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                hintText.text = getString(R.string.scan_need_camera)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)
        previewView = findViewById(R.id.preview_view)
        hintText = findViewById(R.id.scan_hint_text)
        statusText = findViewById(R.id.scan_status_text)
        findViewById<TextView>(R.id.close_button).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 绑定后摄预览与图像分析;设备无摄像头时给出提示并停留(用户可退出)。 */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    hintText.text = getString(R.string.scan_no_camera)
                    return@addListener
                }
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also {
                    analysisExecutor = it
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyze) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                hintText.text = getString(R.string.scan_camera_error, e.message ?: "未知错误")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 单帧处理:YUV → 亮度矩阵 → 按 surface 旋转 → ZXing 解码 → 校验配对格式。 */
    private fun analyze(image: ImageProxy) {
        if (delivered) {
            image.close()
            return
        }
        try {
            val rotation = image.imageInfo.rotationDegrees
            val (luminance, width, height) = luminanceMatrix(image)
            val (rotated, rw, rh) = rotate(luminance, width, height, rotation)

            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true
                    )
                )
            }
            val text = try {
                val source = PlanarYUVLuminanceSource(rotated, rw, rh, 0, 0, rw, rh, false)
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: NotFoundException) {
                null
            } catch (_: Exception) {
                null
            } finally {
                reader.reset()
            }

            if (text != null) {
                val info = PairingQr.parse(text)
                if (info != null) {
                    delivered = true
                    mainHandler.post { deliver(info) }
                } else if (text != lastRejectedText) {
                    // 同一张无关二维码只提示一次,避免 Toast 刷屏;链接类内容
                    // (如插件页新加的"下载二维码")给专门提示,避免误扫后一脸茫然
                    lastRejectedText = text
                    val isUrl = text.startsWith("http://") || text.startsWith("https://")
                    val messageRes = if (isUrl) R.string.scan_rejected_url else R.string.scan_rejected_generic
                    mainHandler.post {
                        statusText.setText(messageRes)
                        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    /** 把结果回传 MainActivity;主线程调用,随后立即关页。 */
    private fun deliver(info: PairingQr.PairInfo) {
        val result = Intent().apply {
            putExtra(EXTRA_HOST, info.primaryHost)
            putExtra(EXTRA_PORT, info.port)
            putExtra(EXTRA_TOKEN, info.token)
            putExtra(EXTRA_ADDRESSES, ArrayList(info.addresses))
        }
        setResult(RESULT_OK, result)
        finish()
    }

    // ==================== YUV → 亮度矩阵 ====================

    /**
     * 提取 Y 平面为 width×height 的亮度字节;兼容 rowStride > width 与
     * pixelStride = 2 的设备,末行可用字节不足时保持 0。
     */
    private fun luminanceMatrix(image: ImageProxy): Triple<ByteArray, Int, Int> {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = ByteArray(width * height)

        val rowBuf = ByteArray(rowStride)
        var pos = 0
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            val available = minOf(rowStride, buffer.remaining())
            buffer.get(rowBuf, 0, available)
            if (pixelStride == 1 && available >= width) {
                System.arraycopy(rowBuf, 0, out, pos, width)
            } else {
                for (x in 0 until width) {
                    val index = x * pixelStride
                    if (index < available) out[pos + x] = rowBuf[index]
                }
            }
            pos += width
        }
        return Triple(out, width, height)
    }

    /**
     * 把亮度矩阵旋转到与屏幕方向一致(ZXing 对旋转不做自动矫正)。
     * 返回 (数据, 宽, 高);90°/270° 时宽高互换。
     */
    private fun rotate(src: ByteArray, w: Int, h: Int, rotation: Int): Triple<ByteArray, Int, Int> {
        when (rotation) {
            90 -> {
                val out = ByteArray(src.size)
                for (y in 0 until w) for (x in 0 until h) out[y * h + x] = src[(h - 1 - x) * w + y]
                return Triple(out, h, w)
            }
            270 -> {
                val out = ByteArray(src.size)
                for (y in 0 until w) for (x in 0 until h) out[y * h + x] = src[(w - 1 - y) * w + x]
                return Triple(out, h, w)
            }
            180 -> {
                val out = ByteArray(src.size)
                for (y in 0 until h) for (x in 0 until w) {
                    out[y * w + x] = src[(h - 1 - y) * w + (w - 1 - x)]
                }
                return Triple(out, w, h)
            }
            else -> return Triple(src, w, h)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor?.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val EXTRA_HOST = "pair_host"
        const val EXTRA_PORT = "pair_port"
        const val EXTRA_TOKEN = "pair_token"
        const val EXTRA_ADDRESSES = "pair_addresses"

        /** 便捷启动入口(MainActivity 用 ActivityResultLauncher 启动)。 */
        fun intent(activity: android.content.Context): Intent =
            Intent(activity, QrScanActivity::class.java)
    }
}
