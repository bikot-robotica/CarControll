/* 
  @copyright Bikot
  @website bikotworl.com
  Todos los derechos reservados.
*/

package bikot.robotica.caresp32

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : Activity() {

    companion object {
        private const val TARGET_NAME = "Bikot_Car"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQ_BT = 1001
    }

    private lateinit var webView: WebView
    private var adapter: BluetoothAdapter? = null

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var connected = false
    @Volatile private var connecting = false
    @Volatile private var connectedName = ""

    private val writer = Executors.newSingleThreadExecutor()
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        webView = WebView(this).apply {
            setBackgroundColor(0xFF0E0F11.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    if (scheme == "http" || scheme == "https") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (_: ActivityNotFoundException) {
                        }
                        return true
                    }
                    return false
                }
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onPause() {
        super.onPause()
        writeByte('S'.code)
    }

    override fun onDestroy() {
        disconnectInternal()
        writer.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Se usa para cerrar primero los menus laterales")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        webView.evaluateJavascript("window.handleBack && window.handleBack()") { r ->
            if (r != "true") finish()
        }
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun withPermission(action: () -> Unit) {
        if (hasBtPermission()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke()
        else notifyState("error", "", "Permiso de Bluetooth denegado. Concédalo en los ajustes de la app.")
    }

    private fun connectTo(device: BluetoothDevice) {
        if (connecting) return
        val bt = adapter
        if (bt == null) { notifyState("error", "", "Este dispositivo no tiene Bluetooth"); return }
        if (!bt.isEnabled) {
            notifyState("error", "", "Bluetooth desactivado. Actívelo e intente de nuevo.")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        disconnectInternal()
        connecting = true
        val name = device.name ?: device.address
        notifyState("connecting", name, "")

        Thread {
            var s: BluetoothSocket? = null
            try {
                try {
                    s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                } catch (e: IOException) {
                    try { s?.close() } catch (_: IOException) {}
                    s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                }
                socket = s
                out = s.outputStream
                connectedName = name
                connected = true
                connecting = false
                notifyState("connected", name, "")
                startReader(s)
            } catch (e: Exception) {
                try { s?.close() } catch (_: IOException) {}
                connected = false
                connecting = false
                notifyState("error", name, "No se pudo conectar: ${e.message ?: "error desconocido"}")
            }
        }.start()
    }

    private fun startReader(s: BluetoothSocket) {
        Thread {
            val buf = ByteArray(256)
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) notifyData(String(buf, 0, n))
                }
            } catch (_: IOException) {
            }
            if (socket === s) {
                disconnectInternal()
                notifyState("disconnected", "", "Conexión perdida")
            }
        }.start()
    }

    private fun disconnectInternal() {
        connected = false
        val s = socket
        socket = null
        out = null
        try { s?.close() } catch (_: IOException) {}
    }

    private fun writeByte(b: Int): Boolean {
        if (!connected) return false
        writer.execute {
            try {
                out?.apply { write(b); flush() }
            } catch (e: IOException) {
                if (connected) {
                    disconnectInternal()
                    notifyState("disconnected", "", "Conexión perdida al enviar")
                }
            }
        }
        return true
    }

    private fun js(script: String) = runOnUiThread { webView.evaluateJavascript(script, null) }

    private fun notifyState(state: String, name: String, msg: String) = js(
        "window.onBtState && window.onBtState(" +
            "${JSONObject.quote(state)},${JSONObject.quote(name)},${JSONObject.quote(msg)})"
    )

    private fun notifyData(text: String) =
        js("window.onBtData && window.onBtData(${JSONObject.quote(text)})")

    inner class Bridge {

        @JavascriptInterface
        fun sendCommand(cmd: String): Boolean {
            val c = cmd.firstOrNull() ?: return false
            return writeByte(c.code)
        }

        @JavascriptInterface
        fun isConnected(): Boolean = connected

        @JavascriptInterface
        fun getConnectedName(): String = connectedName

        @JavascriptInterface
        fun getPairedDevices(): String {
            if (!hasBtPermission()) {
                runOnUiThread {
                    withPermission { js("window.onPairedChanged && window.onPairedChanged()") }
                }
                return "[]"
            }
            val arr = JSONArray()
            adapter?.bondedDevices?.forEach { d ->
                arr.put(JSONObject().put("name", d.name ?: "").put("address", d.address))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun connectByName(name: String) {
            runOnUiThread {
                withPermission {
                    val dev = adapter?.bondedDevices?.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    if (dev != null) connectTo(dev)
                    else notifyState("error", "", "$name no está emparejado. Empareje el dispositivo en los ajustes Bluetooth.")
                }
            }
        }

        @JavascriptInterface
        fun connect(address: String) {
            runOnUiThread {
                withPermission {
                    if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                        notifyState("error", "", "Dirección Bluetooth no válida")
                    } else {
                        adapter?.getRemoteDevice(address)?.let { connectTo(it) }
                    }
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            writeByte('S'.code)
            disconnectInternal()
            notifyState("disconnected", "", "")
        }

        @JavascriptInterface
        fun openBluetoothSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }
}
