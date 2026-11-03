package dev.stride.hud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the unit-test classpath carries a real org.json rather than
 * android.jar's stubs, which return null/0 and would make every JSON test in
 * this directory pass or fail for reasons that have nothing to do with it.
 */
class HarnessTest {
    @Test fun jsonIsReal() {
        assertEquals("{\"a\":1.5}", JSONObject().put("a", 1.5).toString())
    }
}
