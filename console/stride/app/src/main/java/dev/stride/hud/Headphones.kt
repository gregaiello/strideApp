package dev.stride.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Bluetooth headphones for whatever the console is playing.
 *
 * Asked for so a film in NewPipe or Netflix can be heard over the belt
 * without the console's speakers filling the room. Headphones are classic
 * Bluetooth (A2DP), not BLE: LE Audio needs Android 13 and this console runs
 * 9. Its stack does carry A2DP source and AVRCP target (dumpsys
 * bluetooth_manager lists A2dpService and AvrcpTargetService), so once a set
 * is connected Android routes all media to it, every app's and the coach's
 * alike. Nothing here touches audio routing itself.
 *
 * STRIDE does the pairing because the console's own Bluetooth screen sits
 * behind the immersive UI and the disabled iFit launcher.
 *
 * **One hidden call.** Android 9 has no public way for an app to *connect* an
 * A2DP device: [BluetoothA2dp.connect] is @hide. It is on P's light greylist,
 * so reflection reaches it with only a logcat warning. Pairing, discovery and
 * the connection state are all public. If a later ROM ever closed the door,
 * a pair still works and the headphones' own power-on reconnect still lands,
 * because Android accepts an incoming A2DP link from a bonded device.
 */
class Headphones(private val context: Context, private val cfg: Settings) {

    companion object {
        private const val TAG = "Stride"
        /** Classic discovery is ~12 s on its own; a fixed stop keeps it bounded. */
        private const val SEARCH_MS = 15_000L
    }

    data class Found(val address: String, val name: String, val bonded: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter

    @Volatile private var a2dp: BluetoothA2dp? = null
    private val seen = LinkedHashMap<String, Found>()

    @Volatile var searching = false
        private set
    /** What the screen shows beside the paired set: pairing, refused, etc. */
    @Volatile var note = ""
        private set

    val available: Boolean get() = adapter?.isEnabled == true

    private var started = false

    /** Once per process: the A2DP proxy and the receivers. */
    fun start() {
        if (started) return
        started = true
        val a = adapter ?: return
        a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                // Headphones left on from the last walk come back without a tap.
                reconnect()
            }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        })
    }

    fun stop() {
        if (!started) return
        started = false
        stopSearch()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
    }

    // --- searching ------------------------------------------------------------

    /**
     * Classic discovery, plus what Android already has bonded. Listing the
     * bonded audio devices too means a set paired some other way (or before a
     * FORGET of STRIDE's own record) can be picked without pairing mode.
     */
    @SuppressLint("MissingPermission")
    fun search() {
        val a = adapter ?: return
        seen.clear()
        try {
            a.bondedDevices?.filter { isAudio(it) }?.forEach {
                seen[it.address] = Found(it.address, nameOf(it), true)
            }
            if (a.isDiscovering) a.cancelDiscovery()
            searching = a.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "headphones: no permission to search (${e.message})")
            searching = false
            return
        }
        handler.postDelayed({ stopSearch() }, SEARCH_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopSearch() {
        if (!searching) return
        searching = false
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
    }

    fun found(): List<Found> = seen.values.toList()

    /**
     * Headphones, earbuds, speakers: anything that takes audio in. The class
     * check rather than a name list, because headphones call themselves
     * anything ("WH-1000XM4", "Buds2", "JBL TUNE"). Unnamed devices are kept
     * out: a nameless entry is a phone or a TV more often than a headset.
     */
    @SuppressLint("MissingPermission")
    private fun isAudio(d: BluetoothDevice): Boolean {
        val c = d.bluetoothClass ?: return false
        return c.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            c.hasService(BluetoothClass.Service.RENDER) && c.hasService(BluetoothClass.Service.AUDIO)
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(d: BluetoothDevice): String =
        try { d.name ?: "" } catch (_: SecurityException) { "" }

    // --- pairing and connecting -----------------------------------------------

    @SuppressLint("MissingPermission")
    fun pair(address: String, name: String) {
        val a = adapter ?: return
        stopSearch()
        cfg.saveHeadphones(address, name)
        val d = a.getRemoteDevice(address)
        if (d.bondState == BluetoothDevice.BOND_BONDED) {
            connect()
            return
        }
        note = "Pairing…"
        // Most sets pair with no code. Old ones that want 0000 get Android's
        // own pairing dialog, which appears over STRIDE.
        if (!d.createBond()) note = "Could not start pairing. Is it in pairing mode?"
        Log.i(TAG, "headphones: pairing with $name")
    }

    /** Connect the saved set now. */
    @SuppressLint("MissingPermission")
    fun connect() {
        val addr = cfg.headphonesAddr()
        val a = adapter ?: return
        if (addr.isBlank()) return
        val p = a2dp ?: run { note = "Bluetooth audio is not ready yet."; return }
        val d = a.getRemoteDevice(addr)
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            note = "Not paired. Search and pair it again."
            return
        }
        // Discovery slows every other radio job down; a connect during one
        // can take a minute instead of a second.
        if (a.isDiscovering) a.cancelDiscovery()
        note = "Connecting…"
        if (!hidden(p, "connect", d)) {
            note = "This console would not connect it. Switch the headphones off and on."
            return
        }
        // Several sets known: make this one the one that plays. Harmless when
        // it is the only one, and not every Android 9 build has it.
        hidden(p, "setActiveDevice", d)
        Log.i(TAG, "headphones: connecting to ${cfg.headphonesName()}")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val addr = cfg.headphonesAddr()
        val p = a2dp ?: return
        if (addr.isBlank()) return
        hidden(p, "disconnect", adapter?.getRemoteDevice(addr) ?: return)
        note = ""
    }

    /**
     * Forget for good: STRIDE's record and Android's bond. Leaving the bond
     * would let the set keep connecting itself at power-on, which is the
     * opposite of what FORGET says.
     */
    @SuppressLint("MissingPermission")
    fun forget() {
        val addr = cfg.headphonesAddr()
        if (addr.isNotBlank()) {
            disconnect()
            adapter?.getRemoteDevice(addr)?.let { hidden(it, "removeBond") }
        }
        cfg.forgetHeadphones()
        note = ""
        Log.i(TAG, "headphones: forgotten")
    }

    /** Only when one is saved and not already playing. */
    private fun reconnect() {
        if (cfg.headphonesAddr().isNotBlank() && !connected()) connect()
    }

    @SuppressLint("MissingPermission")
    fun connected(): Boolean {
        val addr = cfg.headphonesAddr()
        val p = a2dp ?: return false
        if (addr.isBlank()) return false
        return try {
            p.connectedDevices.any { it.address == addr }
        } catch (_: SecurityException) { false }
    }

    private fun hidden(target: Any, method: String, vararg args: BluetoothDevice): Boolean =
        try {
            val m = target.javaClass.getMethod(method,
                *Array(args.size) { BluetoothDevice::class.java })
            (m.invoke(target, *args) as? Boolean) ?: true
        } catch (e: Exception) {
            Log.w(TAG, "headphones: $method unavailable (${e.javaClass.simpleName}: ${e.message})")
            false
        }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) {
            val d: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (d == null || !searching || !isAudio(d)) return
                    val name = nameOf(d)
                    if (name.isBlank()) return
                    seen[d.address] = Found(d.address, name,
                        d.bondState == BluetoothDevice.BOND_BONDED)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> searching = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (d?.address != cfg.headphonesAddr()) return
                    when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(TAG, "headphones: paired")
                            // Some sets take a moment after bonding before
                            // they accept the audio link.
                            handler.postDelayed({ connect() }, 1500)
                        }
                        BluetoothDevice.BOND_NONE -> if (note == "Pairing…") {
                            note = "Pairing failed. Put them in pairing mode and try again."
                            Log.w(TAG, "headphones: pairing failed")
                        }
                    }
                }
                // The set came back in range and reached us first: finish the
                // job if Android does not make the audio link on its own.
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (d?.address == cfg.headphonesAddr())
                        handler.postDelayed({ reconnect() }, 3000)
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (d?.address != cfg.headphonesAddr()) return
                    when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            note = ""
                            Log.i(TAG, "headphones: connected, media plays there")
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (note == "Connecting…") note = "Did not connect. Are they on and in range?"
                            Log.i(TAG, "headphones: disconnected")
                        }
                    }
                }
            }
        }
    }
}
