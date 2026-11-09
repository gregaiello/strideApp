package dev.stride.hud

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session as SshSession
import com.jcraft.jsch.SftpException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Copies each walk's .fit to a NAS over SFTP, into a folder named after the
 * walker in the NAS account's home: `~/Gre/2026-11-05_2017_Gre.fit`.
 *
 * Fork-only. It is the one thing here that needs a library (JSch, pure
 * Java), because SSH is not something to write by hand.
 *
 * **Setup is once.** STRIDE makes its own RSA key, logs in with the NAS
 * password one time to add that key to `~/.ssh/authorized_keys`, and keeps
 * only the key. The password is never stored. Typing a private key on a
 * treadmill's touchscreen was the alternative.
 *
 * **The NAS is remembered on first contact** by its host key, written at
 * setup, and every later copy refuses a NAS that answers with a different
 * one, rather than hand a walk to whoever took over the address. The key is
 * pinned on its own, not under the address it was reached at: a known_hosts
 * file keyed by what was typed called the same NAS "changed" the moment its
 * name was swapped for its IP, and would again whenever DHCP moved it.
 *
 * **Nothing waits for it.** Copies run on their own thread. A copy that fails
 * (NAS asleep, Wi-Fi down) leaves a marker in `nas/pending/` and is tried
 * again after the next walk, at start-up, or from SEND NOW in Settings. The
 * .fit itself stays on the console either way, where `adb pull` still finds it.
 */
class NasCopy(
    private val ctx: Context,
    private val cfg: Settings,
    /** Every converted route from the NAS's routes/ folder, after each fetch. */
    private val onRoutes: (org.json.JSONArray) -> Unit = {},
) {

    companion object {
        private const val TAG = "Stride"
        private const val CONNECT_MS = 8000
        private const val KEY_COMMENT = "stride@treadmill"
        /** Next to the walkers' folders: `<base>/routes/`, holding .gpx files. */
        const val ROUTES_DIR = "routes"
        /** A walk's GPX is well under a megabyte; past this it is not a route. */
        private const val MAX_GPX_BYTES = 20L * 1024 * 1024
    }

    private val dir = File(ctx.filesDir, "nas").apply { mkdirs() }
    private val keyFile = File(dir, "id_rsa")
    private val pubFile = File(dir, "id_rsa.pub")
    /** "type base64key" of the NAS as first seen at setup. */
    private val hostKeyFile = File(dir, "host_key")
    private val pendingDir = File(dir, "pending").apply { mkdirs() }
    /** Local copies of the NAS's GPX files, so a fetch only downloads what changed. */
    private val gpxDir = File(dir, "routes").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var state = "idle"      // idle | working | ok | error
    @Volatile private var message = ""
    @Volatile private var lastSent = ""
    /**
     * The copy's own outcome, apart from [state]/[message], which answer CHECK
     * and SET UP. Copying used to report through those too and finish on
     * "Ready" — the same word it showed before anything was sent — so a SEND
     * NOW that worked looked exactly like one that had not started
     * (2026-11-09). This says what happened and when, in the row the button
     * is in.
     */
    @Volatile private var sendState = "idle"   // idle | working | ok | error
    @Volatile private var sendMessage = ""
    /** elapsedRealtime of the last finished copy: the wall clock on this
     *  console can be years out, a duration cannot. */
    @Volatile private var sendAt = 0L
    @Volatile private var routesState = "idle"
    @Volatile private var routesMessage = ""

    fun configured(): Boolean =
        cfg.nasHost().isNotEmpty() && cfg.nasUser().isNotEmpty() &&
            keyFile.exists() && hostKeyFile.exists()

    /**
     * A .fit was just written. Queued even if the copy will fail, so nothing
     * is lost to a NAS that was asleep; not queued at all before setup, so
     * setting it up later does not dump months of walks on it unasked.
     */
    fun enqueue(fit: File, walker: String) {
        if (!configured()) return
        File(pendingDir, fit.name).writeText(walker)
        sendPending()
    }

    fun sendPending() {
        worker.execute { drain() }
    }

    fun pendingCount(): Int = pendingDir.listFiles()?.size ?: 0

    /** The .fit names still to copy, which the ten-walk ring must not delete. */
    fun pendingNames(): Set<String> = pendingDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

    fun status(): String = JSONObject()
        .put("configured", configured())
        .put("state", state)
        .put("message", message)
        .put("pending", pendingCount())
        .put("lastSent", lastSent)
        .put("sendState", sendState)
        .put("sendMessage", sendMessage)
        .put("sendAgoSec", if (sendAt > 0) (android.os.SystemClock.elapsedRealtime() - sendAt) / 1000 else -1)
        .put("routesState", routesState)
        .put("routesMessage", routesMessage)
        .toString()

    /**
     * Is what was typed into Settings right, short of the password?
     *
     * Not an ICMP ping: a NAS answering pings proves nothing about SSH, and
     * plenty of them do not answer pings at all. This looks the name up, says
     * which address it found (so an IP or a DNS name can be told apart from
     * a typo), opens the port and reads the server's greeting, which every
     * SSH server sends before anyone logs in. Once set up, it also logs in
     * with STRIDE's key and writes a test file where walks will go.
     */
    fun check() {
        worker.execute {
            val host = cfg.nasHost()
            val port = cfg.nasPort()
            if (host.isEmpty()) { state = "error"; message = "Enter the NAS address first."; return@execute }
            state = "working"; message = "Looking up $host…"
            val addr = try {
                java.net.InetAddress.getByName(host).hostAddress
            } catch (e: java.net.UnknownHostException) {
                state = "error"
                // Android 9 has no mDNS in its resolver; a Synology's
                // "DiskStation.local" is found by a laptop and not by this.
                message = if (host.endsWith(".local", true))
                    "This console cannot look up .local names. Use the IP, or the name your router gives it."
                else "Cannot find $host on the network. Check the spelling, or use the IP."
                return@execute
            }
            message = "Found $addr, knocking on port $port…"
            val banner = try {
                java.net.Socket().use { sock ->
                    try {
                        sock.connect(java.net.InetSocketAddress(addr, port), CONNECT_MS)
                    } catch (e: Exception) {
                        state = "error"
                        message = "Found $addr, but nothing answers on port $port. Is SSH turned on?"
                        return@execute
                    }
                    sock.soTimeout = CONNECT_MS
                    // Something that connects and then says nothing (adb on
                    // 5555 does exactly this) is "not SSH", not "nobody there".
                    val line = try {
                        sock.getInputStream().bufferedReader(Charsets.US_ASCII).readLine() ?: ""
                    } catch (_: Exception) { "" }
                    // Greet back before hanging up, so the NAS logs a client
                    // that left rather than a protocol error.
                    if (line.startsWith("SSH-")) try {
                        sock.getOutputStream().write("SSH-2.0-STRIDE_check\r\n".toByteArray())
                    } catch (_: Exception) {}
                    line
                }
            } catch (e: Exception) { "" }
            if (!banner.startsWith("SSH-")) {
                state = "error"
                message = "Found $addr, and port $port answers, but it is not SSH."
                return@execute
            }
            // "SSH-2.0-OpenSSH_9.6" -> "OpenSSH_9.6"
            val server = banner.substringAfter("SSH-2.0-").substringBefore(' ').take(32)
            val reached = "Found $addr · SSH answers ($server)"
            if (!configured()) {
                state = "ok"; message = "$reached · now SET UP with the password"
                return@execute
            }
            message = "$reached · logging in…"
            try {
                withSftp { sftp -> probe(sftp) }
                state = "ok"; message = "$reached · login and folder OK"
            } catch (e: Exception) {
                state = "error"; message = "$reached, but: ${explain(e)}"
            }
            Log.i(TAG, "nas: check $host:$port -> $message")
        }
    }

    /** Forget the NAS, keep the key: setting it up again reuses it. */
    fun forget() {
        hostKeyFile.delete()
        pendingDir.listFiles()?.forEach { it.delete() }
        state = "idle"; message = ""
        Log.i(TAG, "nas: forgotten")
    }

    /**
     * The one time the password is used. Saves nothing but what Settings
     * already holds; the password lives only for this call.
     */
    fun setUp(password: String) {
        // The box clears after every SET UP, so a second press sends nothing.
        // Say so instead of letting the NAS count it as a failed login.
        if (password.isEmpty()) {
            state = "error"; message = "Type the NAS password first, then SET UP."
            return
        }
        worker.execute {
            state = "working"; message = "Connecting…"
            try {
                ensureKey()
                // A fresh JSch has no host keys, so this login trusts whatever
                // answers. The pinned key is replaced only once it succeeds: a
                // mistyped password must not break a setup that was working.
                val jsch = JSch()
                val s = jsch.getSession(cfg.nasUser(), cfg.nasHost(), cfg.nasPort())
                s.setPassword(password)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                s.userInfo = PasswordOnly(password)
                s.connect(CONNECT_MS)
                // This is the first contact, and the one we trust from now on.
                hostKeyFile.writeText("${s.hostKey.type} ${s.hostKey.key}")
                try {
                    message = "Adding STRIDE's key…"
                    installKey(s)
                } finally {
                    s.disconnect()
                }
                message = "Checking the key works…"
                withSftp { sftp -> probe(sftp) }
                state = "ok"; message = "Ready"
                Log.i(TAG, "nas: set up ${cfg.nasUser()}@${cfg.nasHost()}:${cfg.nasPort()}")
                drain()
                fetchRoutes()
            } catch (e: Exception) {
                state = "error"; message = explain(e)
                Log.w(TAG, "nas: setup failed: ${e.message}")
            }
        }
    }

    /**
     * Routes from the NAS: every `.gpx` in `<base>/routes/`, converted on the
     * console by [GpxRoute] and handed to [onRoutes].
     *
     * The folder is created if missing, so after the first fetch it is there
     * to be found next to the walkers' folders. Only files whose size or date
     * changed are downloaded; ones gone from the NAS are dropped here too, so
     * deleting a file on the NAS removes the route. With the NAS unreachable
     * nothing changes: the last converted set stays, as phone routes do.
     */
    fun fetchRoutes() {
        worker.execute {
            if (!configured()) { routesState = "idle"; routesMessage = ""; return@execute }
            routesState = "working"; routesMessage = "Looking in the NAS's routes folder…"
            try {
                val folder = withSftp { sftp ->
                    val remote = "${baseOf(sftp)}/$ROUTES_DIR"
                    mkdirQuiet(sftp, remote)
                    @Suppress("UNCHECKED_CAST")
                    val entries = (sftp.ls(remote) as java.util.Vector<ChannelSftp.LsEntry>)
                        .filter { !it.attrs.isDir && it.filename.endsWith(".gpx", true) }
                    val wanted = entries.map { it.filename }.toSet()
                    gpxDir.listFiles()?.forEach { if (it.name !in wanted) it.delete() }
                    for (e in entries) {
                        if (e.attrs.size > MAX_GPX_BYTES) continue
                        val local = File(gpxDir, e.filename)
                        val stamp = e.attrs.mTime * 1000L
                        if (local.exists() && local.length() == e.attrs.size &&
                            local.lastModified() == stamp) continue
                        val part = File(gpxDir, e.filename + ".part")
                        part.outputStream().use { sftp.get("$remote/${e.filename}", it) }
                        local.delete(); part.renameTo(local)
                        local.setLastModified(stamp)
                        Log.i(TAG, "nas: fetched route ${e.filename}")
                    }
                    remote
                }
                val out = org.json.JSONArray()
                val skipped = ArrayList<String>()
                gpxDir.listFiles { f -> f.name.endsWith(".gpx", true) }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { f ->
                        val r = try {
                            GpxRoute.convert(f.readText(), f.name)
                        } catch (e: Exception) {
                            GpxRoute.Result.Skip("could not be read")
                        }
                        when (r) {
                            is GpxRoute.Result.Ok -> out.put(r.route)
                            is GpxRoute.Result.Skip -> {
                                skipped.add("${f.name}: ${r.why}")
                                Log.w(TAG, "nas: route ${f.name} skipped (${r.why})")
                            }
                        }
                    }
                onRoutes(out)
                routesState = if (skipped.isEmpty()) "ok" else "error"
                routesMessage = when {
                    out.length() == 0 && skipped.isEmpty() ->
                        "No .gpx files yet. Put them in $folder"
                    else -> "${out.length()} route(s) from $folder" +
                        if (skipped.isEmpty()) "" else " · skipped ${skipped.joinToString("; ")}"
                }
            } catch (e: Exception) {
                routesState = "error"; routesMessage = explain(e)
                Log.w(TAG, "nas: route fetch failed: ${e.message}")
            }
        }
    }

    private fun ensureKey() {
        if (keyFile.exists() && pubFile.exists()) return
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        keyFile.outputStream().use { kp.writePrivateKey(it) }
        pubFile.outputStream().use { kp.writePublicKey(it, KEY_COMMENT) }
        kp.dispose()
        Log.i(TAG, "nas: made a key for this console")
    }

    /**
     * Shell first, SFTP second. The shell is how any Linux box does it, but a
     * Synology only gives a shell to administrators, while SFTP works for
     * every user with a home folder.
     */
    private fun installKey(s: SshSession) {
        val pub = pubFile.readText().trim()
        val viaShell = try {
            val ch = s.openChannel("exec") as ChannelExec
            ch.setCommand("umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
                "(grep -qF '${pub.substringBeforeLast(' ')}' ~/.ssh/authorized_keys || " +
                "echo '$pub' >> ~/.ssh/authorized_keys) && chmod 700 ~/.ssh && " +
                "chmod 600 ~/.ssh/authorized_keys")
            val err = ByteArrayOutputStream()
            ch.setErrStream(err)
            ch.connect(CONNECT_MS)
            while (!ch.isClosed) Thread.sleep(50)
            val ok = ch.exitStatus == 0
            ch.disconnect()
            if (!ok) Log.w(TAG, "nas: shell would not add the key: ${err.toString().trim()}")
            ok
        } catch (e: Exception) {
            false
        }
        if (viaShell) return
        val sftp = s.openChannel("sftp") as ChannelSftp
        sftp.connect(CONNECT_MS)
        try {
            val home = homeOf(sftp)
            val ssh = "$home/.ssh"
            mkdirQuiet(sftp, ssh)
            try { sftp.chmod("700".toInt(8), ssh) } catch (_: SftpException) {}
            val keys = "$ssh/authorized_keys"
            val had = try {
                ByteArrayOutputStream().also { sftp.get(keys, it) }.toString("UTF-8")
            } catch (_: SftpException) { "" }
            if (!had.contains(pub.substringBeforeLast(' '))) {
                val text = (if (had.isEmpty() || had.endsWith("\n")) had else had + "\n") + pub + "\n"
                sftp.put(text.byteInputStream(), keys)
            }
            try { sftp.chmod("600".toInt(8), keys) } catch (_: SftpException) {}
        } finally {
            sftp.disconnect()
        }
    }

    /** Writes and removes a file where walks will go, so setup proves the path too. */
    private fun probe(sftp: ChannelSftp) {
        val base = baseOf(sftp)
        mkdirQuiet(sftp, base)
        val p = "$base/.stride-test"
        sftp.put("ok".byteInputStream(), p)
        sftp.rm(p)
    }

    private fun drain() {
        if (!configured()) return
        val queued = pendingDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (queued.isEmpty()) {
            // Still an answer: a SEND NOW with nothing to send must say so,
            // not leave the last outcome on screen as if it were this one.
            sendState = "ok"; sendMessage = "Nothing waiting: every walk is on the NAS"
            sendAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        val fitDir = ctx.getExternalFilesDir("fit") ?: File(ctx.filesDir, "fit")
        sendState = "working"
        sendMessage = "Copying ${queued.size} walk${if (queued.size == 1) "" else "s"}…"
        var copied = 0
        try {
            withSftp { sftp ->
                val base = baseOf(sftp)
                for (marker in queued) {
                    val fit = File(fitDir, marker.name)
                    if (!fit.exists()) { marker.delete(); continue }
                    val folder = "$base/${folderName(marker.readText())}"
                    mkdirQuiet(sftp, folder)
                    // Sent aside and renamed, so a dropped connection never
                    // leaves a half file under a name that looks finished.
                    val part = "$folder/${fit.name}.part"
                    fit.inputStream().use { sftp.put(it, part) }
                    try { sftp.rm("$folder/${fit.name}") } catch (_: SftpException) {}
                    sftp.rename(part, "$folder/${fit.name}")
                    marker.delete()
                    copied++
                    lastSent = fit.name
                    Log.i(TAG, "nas: copied ${fit.name} to $folder")
                }
            }
            if (state == "error") { state = "ok"; message = "Ready" }
            sendState = "ok"
            sendMessage = "Copied $copied walk${if (copied == 1) "" else "s"}" +
                if (lastSent.isNotEmpty()) " · last $lastSent" else ""
        } catch (e: Exception) {
            state = "error"; message = explain(e)
            sendState = "error"
            sendMessage = (if (copied > 0) "Copied $copied, then: " else "") + explain(e)
            Log.w(TAG, "nas: copy failed, ${pendingCount()} waiting: ${e.message}")
        }
        sendAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun <T> withSftp(block: (ChannelSftp) -> T): T {
        val jsch = JSch()
        jsch.hostKeyRepository = PinnedHostKey(hostKeyFile.readText().trim())
        jsch.addIdentity(keyFile.path)
        val s = jsch.getSession(cfg.nasUser(), cfg.nasHost(), cfg.nasPort())
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "publickey")
        s.connect(CONNECT_MS)
        try {
            val sftp = s.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_MS)
            try { return block(sftp) } finally { sftp.disconnect() }
        } finally {
            s.disconnect()
        }
    }

    /**
     * Where SFTP put us, which on most NASes is the home folder. A Synology
     * is the exception: its SFTP starts at a root of shared folders, and the
     * user's own home is the `home` share inside it.
     */
    private fun homeOf(sftp: ChannelSftp): String {
        val start = sftp.home.trimEnd('/')
        if (start.isNotEmpty()) return start
        return try { sftp.stat("/home"); "/home" } catch (_: SftpException) { "" }
    }

    /** The folder the walkers' folders go in: the home, or the one set in Settings. */
    private fun baseOf(sftp: ChannelSftp): String {
        val set = cfg.nasDir().trim().trimEnd('/')
        if (set.startsWith("/")) return set
        val home = homeOf(sftp)
        return if (set.isEmpty()) home else "$home/$set"
    }

    /** "" is the root: a home of "/" trims to it, and the root always exists. */
    private fun mkdirQuiet(sftp: ChannelSftp, path: String) {
        if (path.isEmpty()) return
        try { sftp.stat(path) } catch (_: SftpException) { sftp.mkdir(path) }
    }

    /** The walker's name as a folder: Gre stays Gre, anything odd becomes _. */
    private fun folderName(walker: String): String =
        walker.trim().replace(Regex("[^A-Za-z0-9_ -]"), "_").ifEmpty { "_" }

    /** What went wrong, in words for the settings screen rather than a stack trace. */
    private fun explain(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) ||
                m.contains("Too many authentication", true) ->
                "The NAS refused the login. Check the user and password."
            m.contains("HostKey has been changed", true) || m.contains("reject HostKey", true) ->
                "The NAS's identity changed. Set it up again if you replaced it."
            m.contains("timeout", true) || m.contains("UnknownHost", true) ||
                m.contains("refused", true) || m.contains("No route", true) ->
                "Could not reach the NAS. Is it on, and is SSH enabled?"
            m.contains("Permission denied", true) ->
                "The NAS would not let STRIDE write there. Check the folder."
            else -> m
        }
    }

    /** Accepts the one host key seen at setup, at whatever address it answers. */
    private class PinnedHostKey(pinned: String) : com.jcraft.jsch.HostKeyRepository {
        private val key = pinned.substringAfter(' ')
        override fun check(host: String?, k: ByteArray?): Int {
            val seen = java.util.Base64.getEncoder().encodeToString(k ?: return com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED)
            return if (seen == key) com.jcraft.jsch.HostKeyRepository.OK
                   else com.jcraft.jsch.HostKeyRepository.CHANGED
        }
        override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: com.jcraft.jsch.UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "stride-pinned"
        override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> = emptyArray()
    }

    /**
     * Answers JSch's password and keyboard-interactive prompts with the setup
     * password, once per method, then gives up. Answering "yes" every
     * time JSch asks made a wrong password loop until OpenSSH hit MaxAuthTries
     * and hung up with "Too many authentication failures" (seen on the real
     * NAS, OpenSSH 10.0, after SET UP was pressed a second time with the box
     * already cleared). One try each is all a typed password deserves.
     */
    private class PasswordOnly(private val pw: String) :
        com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        private var passwordAsked = false
        private var kbdAsked = false
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pw
        override fun promptPassword(message: String?): Boolean {
            if (passwordAsked) return false
            passwordAsked = true
            return true
        }
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = true
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? {
            if (kbdAsked) return null   // null cancels keyboard-interactive
            kbdAsked = true
            return Array(prompt?.size ?: 0) { pw }
        }
    }
}
