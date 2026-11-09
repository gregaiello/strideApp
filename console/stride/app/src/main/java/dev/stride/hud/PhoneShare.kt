package dev.stride.hud

import android.os.SystemClock
import android.util.Log
import io.nayuki.qrcodegen.QrCode
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A walk to a phone, by QR code.
 *
 * Fork-only (2026-11-09). The console shows a code; a phone on the same Wi-Fi
 * points its camera at it and gets the .fit, to upload to Strava from the
 * phone's browser. The NAS copy is the archive; this is the walk you just did,
 * now, without a computer.
 *
 * A plain HTTP server on a socket, because it serves two kinds of page and a
 * file, and a library for that would be the second dependency in an app whose
 * rule is one. Plain HTTP rather than HTTPS on purpose: the console's clock
 * reads 2010 until NTP lands, so any certificate would be "not yet valid", and
 * a phone that warns about the treadmill teaches nobody anything.
 *
 * What keeps the rest of the house network out of somebody's heart rate:
 *  * **It runs only when asked.** Nothing listens until a code is on screen,
 *    and it stops itself [IDLE_MS] after the last request, or soon after the
 *    code is put away ([stopSoon]).
 *  * **The address carries a key.** A fresh random one each time it starts, in
 *    the QR code and nowhere else; a request without it gets a bare 404, the
 *    same as a path that does not exist, so a port scan learns nothing.
 *  * **It reads, never writes,** and only finished `.fit` files from the one
 *    folder, named from the list rather than from the request. It holds no
 *    reference to the belt or the bridge.
 */
class PhoneShare(
    private val fitDir: File,
    /** The file the summary on screen is for, or null; see [WalkFile]. */
    private val walkFile: () -> WalkFile,
) {

    /** What the summary's code should hand over. */
    sealed class WalkFile {
        /** No file is coming: a guest, a walk too short, or no walk at all. */
        object None : WalkFile()
        /** The walk ended and its .fit is still being written. */
        object Writing : WalkFile()
        data class Ready(val file: File) : WalkFile()
    }

    companion object {
        private const val TAG = "Stride"
        /** Tried first so the address is the same from one walk to the next. */
        private const val PORT = 8080
        private const val IDLE_MS = 10 * 60_000L
        /** After the code is put away: long enough to finish a download. */
        private const val GRACE_MS = 2 * 60_000L
        private const val READ_MS = 10_000
        /** How long /walk waits for the export the summary is ahead of. */
        private const val WRITE_WAIT_MS = 15_000L
        private val SAFE_NAME = Regex("[A-Za-z0-9_-]+\\.fit")
        const val STRAVA_UPLOAD = "https://www.strava.com/upload/select"
    }

    private val lock = Any()
    private var server: ServerSocket? = null
    private var token = ""
    @Volatile private var deadline = 0L
    private val handlers = Executors.newFixedThreadPool(2)

    /**
     * Starts the server if it is not running and answers with what the page
     * draws: `{ok, url, qr}` with an SVG of the code, or `{ok:false, message}`.
     *
     * @param what `walk` for the summary's one file, `list` for the recent walks.
     */
    fun share(what: String): String {
        val host = wifiAddress()
            ?: return fail("The console is not on Wi-Fi, so a phone cannot reach it.")
        if (what == "walk" && walkFile() == WalkFile.None)
            return fail("No file for this walk: it was too short, or a guest walked.")
        val port = try { ensureRunning() } catch (e: Exception) {
            Log.w(TAG, "phone: could not listen", e)
            return fail("Could not start the download server: ${e.message}")
        }
        val url = "http://$host:$port/$token/" + if (what == "walk") "walk" else ""
        return JSONObject().put("ok", true).put("url", url).put("qr", qrSvg(url))
            .put("minutes", IDLE_MS / 60_000).toString()
    }

    /** The code is off the screen; a download already under way may finish. */
    fun stopSoon() {
        deadline = minOf(deadline, SystemClock.elapsedRealtime() + GRACE_MS)
    }

    fun stop() {
        synchronized(lock) {
            try { server?.close() } catch (_: Exception) {}
            server = null
        }
    }

    private fun fail(message: String) =
        JSONObject().put("ok", false).put("message", message).toString()

    private fun ensureRunning(): Int = synchronized(lock) {
        deadline = SystemClock.elapsedRealtime() + IDLE_MS
        server?.let { if (!it.isClosed) return it.localPort }
        token = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val s = ServerSocket().apply {
            reuseAddress = true
            soTimeout = 1000
        }
        try {
            s.bind(InetSocketAddress(PORT))
        } catch (e: Exception) {
            // Something else has 8080. Any port works; the code carries it.
            s.bind(InetSocketAddress(0))
        }
        server = s
        val key = token
        Thread({ acceptLoop(s, key) }, "stride-phone").apply { isDaemon = true }.start()
        Log.i(TAG, "phone: serving on port ${s.localPort}")
        s.localPort
    }

    private fun acceptLoop(s: ServerSocket, key: String) {
        try {
            while (!s.isClosed && SystemClock.elapsedRealtime() < deadline) {
                val c = try { s.accept() } catch (_: SocketTimeoutException) { continue }
                deadline = maxOf(deadline, SystemClock.elapsedRealtime() + IDLE_MS)
                handlers.execute { serve(c, key) }
            }
        } catch (e: Exception) {
            if (!s.isClosed) Log.w(TAG, "phone: server stopped", e)
        } finally {
            synchronized(lock) {
                try { s.close() } catch (_: Exception) {}
                if (server === s) server = null
            }
            Log.i(TAG, "phone: stopped")
        }
    }

    private fun serve(c: Socket, key: String) {
        c.use { sock ->
            try {
                sock.soTimeout = READ_MS
                val line = readLine(sock) ?: return
                // Headers are read and ignored: nothing here depends on them.
                while (true) { val h = readLine(sock) ?: break; if (h.isEmpty()) break }
                val parts = line.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val path = URLDecoder.decode(
                    (parts.getOrNull(1) ?: "").substringBefore('?'), "UTF-8")
                val out = BufferedOutputStream(sock.getOutputStream())
                if (method != "GET" && method != "HEAD") return send(out, 405, "text/plain", "")
                route(out, path, key, method == "HEAD")
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "phone: request failed: ${e.message}")
            }
        }
    }

    private fun route(out: OutputStream, path: String, key: String, head: Boolean) {
        val prefix = "/$key/"
        if (!path.startsWith(prefix) && path != "/$key") return send(out, 404, "text/plain", "Not found")
        val rest = path.removePrefix("/$key").trimStart('/')
        when {
            rest.isEmpty() -> send(out, 200, "text/html; charset=utf-8", listPage(key), head)
            rest == "walk" -> sendWalk(out, key, head)
            rest.startsWith("f/") -> {
                val name = rest.removePrefix("f/")
                val file = FitExport.newestFirst(fitDir).firstOrNull { it.name == name }
                if (!SAFE_NAME.matches(name) || file == null)
                    send(out, 404, "text/html; charset=utf-8",
                        page("Gone", "<p>That walk is no longer on the treadmill. It keeps the last " +
                            "${FitExport.KEEP}.</p><p><a href=\"$prefix\">Recent walks</a></p>"), head)
                else sendFile(out, file, head)
            }
            else -> send(out, 404, "text/plain", "Not found")
        }
    }

    /** The summary's walk, waiting a moment if its file is still being written. */
    private fun sendWalk(out: OutputStream, key: String, head: Boolean) {
        val until = SystemClock.elapsedRealtime() + WRITE_WAIT_MS
        var w = walkFile()
        while (w == WalkFile.Writing && SystemClock.elapsedRealtime() < until) {
            Thread.sleep(250); w = walkFile()
        }
        when (w) {
            is WalkFile.Ready ->
                if (w.file.exists()) sendFile(out, w.file, head)
                else send(out, 404, "text/html; charset=utf-8",
                    page("Gone", "<p>That walk is no longer on the treadmill.</p>" +
                        "<p><a href=\"/$key/\">Recent walks</a></p>"), head)
            WalkFile.Writing -> send(out, 503, "text/html; charset=utf-8",
                page("Nearly", "<p>The treadmill is still writing this walk. Reload in a moment.</p>"), head)
            WalkFile.None -> send(out, 404, "text/html; charset=utf-8",
                page("No file", "<p>This walk has no file.</p><p><a href=\"/$key/\">Recent walks</a></p>"), head)
        }
    }

    private fun listPage(key: String): String {
        val files = FitExport.newestFirst(fitDir)
        val rows = if (files.isEmpty()) "<p>No walks on the treadmill yet.</p>" else
            files.joinToString("", "<ul>", "</ul>") { f ->
                val label = esc(f.name.removeSuffix(".fit").replace('_', ' '))
                "<li><a href=\"/$key/f/${f.name}\">$label</a>" +
                    "<span>${"%.0f".format(f.length() / 1024.0)} KB</span></li>"
            }
        return page("Walks",
            rows + "<p class=\"n\">The last ${FitExport.KEEP} walks are kept on the treadmill. " +
                "Download one, then upload it at <a href=\"$STRAVA_UPLOAD\">strava.com/upload</a>.</p>")
    }

    private fun page(title: String, body: String) = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>STRIDE · $title</title><style>
body{margin:0;padding:24px 16px;font:17px/1.45 -apple-system,system-ui,sans-serif;background:#0d0f10;color:#e8eef0}
h1{font-size:15px;letter-spacing:.3em;color:#5fc7c0;font-weight:500;margin:0 0 20px}
ul{list-style:none;padding:0;margin:0}
li{display:flex;justify-content:space-between;align-items:center;border:1px solid #263035;border-radius:10px;margin:0 0 10px}
li a{flex:1;padding:16px;color:#e8eef0;text-decoration:none}
li span{padding:0 16px;color:#7d8b90;font-size:14px}
a{color:#5fc7c0}.n{color:#7d8b90;font-size:14px;margin-top:22px}
</style></head><body><h1>STRIDE · ${title.uppercase()}</h1>$body</body></html>"""

    private fun sendFile(out: OutputStream, f: File, head: Boolean) {
        val hdr = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Disposition: attachment; filename=\"${f.name}\"\r\n" +
            "Content-Length: ${f.length()}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        if (!head) f.inputStream().use { it.copyTo(out) }
        Log.i(TAG, "phone: sent ${f.name}")
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String, head: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            503 -> "Service Unavailable"; else -> "Error" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII))
        if (!head) out.write(bytes)
    }

    /** One CRLF line, capped, so a client sending garbage cannot fill memory. */
    private fun readLine(s: Socket): String? {
        val inp = s.getInputStream()
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * The console's own address on the Wi-Fi, read each time: the router may
     * have handed it a new one since the last walk, and a code with a stale
     * address is a phone that times out with no clue why.
     */
    private fun wifiAddress(): String? {
        val all = try { NetworkInterface.getNetworkInterfaces()?.toList() } catch (_: Exception) { null }
            ?: return null
        val up = all.filter { it.isUp && !it.isLoopback }
        fun v4(i: NetworkInterface) = i.inetAddresses.toList()
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        return up.filter { it.name.startsWith("wlan") }.firstNotNullOfOrNull(::v4)
            ?: up.firstNotNullOfOrNull(::v4)
    }

    /** The code as one SVG path, drawn by the page at whatever size it likes. */
    private fun qrSvg(text: String): String {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val border = 4
        val n = qr.size + border * 2
        val d = StringBuilder()
        for (y in 0 until qr.size) for (x in 0 until qr.size)
            if (qr.getModule(x, y)) d.append("M${x + border},${y + border}h1v1h-1z")
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $n $n\" " +
            "shape-rendering=\"crispEdges\"><rect width=\"$n\" height=\"$n\" fill=\"#fff\"/>" +
            "<path d=\"$d\" fill=\"#000\"/></svg>"
    }
}
