package com.justcorpiot.coretemp

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.TextView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.app.PendingIntent
import android.os.Build
import com.hoho.android.usbserial.driver.*
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors
import java.nio.charset.Charset
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {
    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var ioExecutor: java.util.concurrent.ExecutorService? = null
    private lateinit var logView: TextView

    private val ACTION_USB_PERMISSION = "com.justcorpiot.coretemp.USB_PERMISSION"

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (granted && device != null) openDevice(device) else append("permission denied\n")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.textValue)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // 1) デバイス列挙（既に挿さっている場合）
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            // append("no usb-serial device\n")
            return
        }

        // 2) ひとつ目のポートを対象に
        val driver = drivers[0]
        val device = driver.device

        // 3) 権限がなければリクエスト
        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE // Android 12+ 必須
            )
            // 受信登録するところ（onCreate など）
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(usbPermissionReceiver, filter)
            }        } else {
            openDevice(device)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            append("no driver for device\n"); return
        }
        val connection = usbManager.openDevice(driver.device) ?: run {
            append("failed to open device (no permission?)\n"); return
        }
        port = driver.ports[0].also { p ->
            p.open(connection)
            p.setParameters(
                /* baud */ 115200,
                /* dataBits */ UsbSerialPort.DATABITS_8,
                /* stopBits */ UsbSerialPort.STOPBITS_1,
                /* parity   */ UsbSerialPort.PARITY_NONE
            )
            append("serial opened\n")

            // 読み取り(イベント駆動)
            ioManager = SerialInputOutputManager(p, this)
            ioExecutor = Executors.newSingleThreadExecutor()
            ioExecutor!!.submit { ioManager }

        }
    }

    override fun onNewData(data: ByteArray) {
        // ここはライブラリのIOスレッドから呼ばれる。UI操作はしない
        feedBytes(data)
    }

    override fun onRunError(e: Exception) {
        runOnUiThread { append("IO error: ${e.message}\n") }
    }

    private fun append(s: String) { logView.setText(s) }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        ioManager?.stop()
        port?.close()
        flushPartialLine()         // 任意：最後の不完全行を処理
        analyzePool.shutdownNow()
    }

    // MainActivity クラス内に追加：行切り出し＆解析キュー
    private val utf8: Charset = Charsets.UTF_8
    private val lineBuf = ByteArrayOutputStream(4096)   // 1行バッファ（必要に応じてサイズ調整）
    private var lastWasCR = false                        // CR単体対応用
    private val analyzePool = Executors.newSingleThreadExecutor() // 解析はUIから切り離して単一スレッドで

    // 1行ごと解析する本体（ここを書き換えて好きな処理を）
    private fun analyzeLine(line: String) {
        // TODO: ここに解析処理
        // 例: CSVパース/正規表現/閾値監視など
        runOnUiThread { append("[analyze] $line\n") }
    }

    // 改行で区切ってコールバックするフレーマ
    @Synchronized
    private fun feedBytes(data: ByteArray, off: Int = 0, len: Int = data.size) {
        var i = off
        val end = off + len
        while (i < end) {
            val b = data[i]
            if (b == '\n'.code.toByte()) {
                // 直前がCRならCRを落とす（CRLF対応）
                val bytes = lineBuf.toByteArray()
                lineBuf.reset()
                val effective = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.copyOf(bytes.size - 1)
                } else bytes
                val line = effective.toString(utf8)
                // 解析はワーカースレッドへ
                analyzePool.submit { analyzeLine(line) }
                lastWasCR = false
            } else if (b == '\r'.code.toByte()) {
                // CR単独改行対応（古い機器など）
                val bytes = lineBuf.toByteArray()
                lineBuf.reset()
                val line = bytes.toString(utf8)
                analyzePool.submit { analyzeLine(line) }
                lastWasCR = true
            } else {
                lineBuf.write(b.toInt())
                lastWasCR = false
            }
            i++
        }
    }

    // 受信終了時や明示フラッシュで最後の不完全行を処理したい場合に呼ぶ（任意）
    @Synchronized
    private fun flushPartialLine() {
        if (lineBuf.size() > 0) {
            val line = lineBuf.toByteArray().toString(utf8)
            lineBuf.reset()
            analyzePool.submit { analyzeLine(line) }
        }
    }
}