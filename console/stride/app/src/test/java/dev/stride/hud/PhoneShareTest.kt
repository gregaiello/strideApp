package dev.stride.hud

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone server over a real socket, on this machine's own LAN address.
 * Skipped on a machine with no private IPv4 address, which has no LAN for a
 * phone to be on either.
 */
class PhoneShareTest {

    @get:Rule val tmp = TemporaryFolder()
    private var share: PhoneShare? = null

    @After fun stop() { share?.stop() }

    private fun get(url: String): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to body
    }

    private fun start(dir: File, walk: PhoneShare.WalkFile, what: String): JSONObject {
        share = PhoneShare(dir) { walk }
        val r = JSONObject(share!!.share(what))
        assumeTrue("no LAN address here: ${r.optString("message")}", r.optBoolean("ok"))
        return r
    }

    @Test fun listsServesAndRefuses() {
        val dir = tmp.newFolder()
        val fit = File(dir, "2026-11-09_1805_Gre.fit").apply { writeBytes(byteArrayOf(14, 16, 1, 2, 3)) }
        File(dir, "secret.txt").writeText("no")
        val url = start(dir, PhoneShare.WalkFile.None, "list").getString("url")
        val base = url.trimEnd('/')
        val key = base.substringAfterLast('/')
        val root = base.removeSuffix("/$key")

        val (code, page) = get(url)
        assertEquals(200, code)
        assertTrue(String(page).contains("/$key/f/${fit.name}"))

        assertArrayEquals(fit.readBytes(), get("$base/f/${fit.name}").second)
        // Without the key, with a wrong one, and outside the folder: all a bare 404.
        assertEquals(404, get("$root/").first)
        assertEquals(404, get("$root/0000000000000000/").first)
        assertEquals(404, get("$base/f/secret.txt").first)
        assertEquals(404, get("$base/f/..%2F..%2Fsecret.txt").first)
        // A guest's walk has no file, and must not hand over someone else's.
        assertEquals(404, get("$base/walk").first)
    }

    @Test fun walkServesTheSummarysFile() {
        val dir = tmp.newFolder()
        File(dir, "2026-11-09_1900_Sonia.fit").writeBytes(byteArrayOf(9, 9))
        val mine = File(dir, "2010-01-01_0000_Gre.fit").apply { writeBytes(byteArrayOf(14, 1)) }
        val r = start(dir, PhoneShare.WalkFile.Ready(mine), "walk")
        assertTrue(r.getString("qr").startsWith("<svg"))
        assertArrayEquals(mine.readBytes(), get(r.getString("url")).second)
    }

    @Test fun noFileNoCode() {
        share = PhoneShare(tmp.newFolder()) { PhoneShare.WalkFile.None }
        assertEquals(false, JSONObject(share!!.share("walk")).getBoolean("ok"))
    }
}
