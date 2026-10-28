package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * [meta1028-B05 F-09] `android-shizuku-cli display set` — the argument decision,
 * pinned where it can actually be tested.
 *
 * The handler body cannot run off-device: `displaySet` calls
 * `ShizukuManager.runProcess`, and `wm` needs a device with Shizuku. Every
 * arm here therefore runs against [planDisplaySet] (the decision, lifted out
 * for exactly that reason) plus two SHAPE reads of the real handler:
 *
 *  - the handler must call [planDisplaySet] and refuse on
 *    [DisplaySetPlan.Refused] — a correct plan that is never consulted is the
 *    "wrapper is alive, the path does not run through it" failure;
 *  - the exit path must not fall through to a success envelope.
 *
 * Expectations are literals. Nothing here reads the constants under test, so
 * widening DISPLAY_MIN_DENSITY must turn the range arms red rather than
 * quietly follow the new value.
 */
class DisplaySetPlanTest {

    companion object {
        private const val HANDLER_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ShizukuOffloadHandler.kt"
    }

    private fun args(vararg argv: String) = OffloadArgs(argv.toList())

    private fun refused(argv: List<String>): String {
        val plan = planDisplaySet(OffloadArgs(argv))
        assertTrue("expected a refusal for $argv, got $plan", plan is DisplaySetPlan.Refused)
        return (plan as DisplaySetPlan.Refused).message
    }

    private fun ready(vararg argv: String): DisplaySetPlan.Ready {
        val plan = planDisplaySet(args(*argv))
        assertTrue("expected a plan for ${argv.toList()}, got $plan", plan is DisplaySetPlan.Ready)
        return plan as DisplaySetPlan.Ready
    }

    // ── the accepted plans ──────────────────────────────────────────────────

    @Test
    fun `density alone is a plan`() {
        assertEquals(DisplaySetPlan.Ready(null, null, 420), ready("--density", "420"))
    }

    @Test
    fun `width and height together are a plan`() {
        assertEquals(DisplaySetPlan.Ready(1080, 2400, null), ready("--width", "1080", "--height", "2400"))
    }

    @Test
    fun `all three together are one plan`() {
        assertEquals(DisplaySetPlan.Ready(720, 1280, 320), ready("--width", "720", "--height", "1280", "--density", "320"))
    }

    @Test
    fun `equals spelling works too`() {
        assertEquals(DisplaySetPlan.Ready(null, null, 440), ready("--density=440"))
    }

    @Test
    fun `the boundary values are inside the accepted range`() {
        assertEquals(DisplaySetPlan.Ready(100, 100, 72), ready("--width", "100", "--height", "100", "--density", "72"))
        assertEquals(
            DisplaySetPlan.Ready(10_000, 10_000, 1_200),
            ready("--width", "10000", "--height", "10000", "--density", "1200"),
        )
    }

    // ── 1: nothing to do ────────────────────────────────────────────────────

    @Test
    fun `a bare display set changes nothing and says so`() {
        // The pre-fix body answered {"ok":true,"data":{}} — a successful
        // override that never ran.
        val msg = refused(emptyList())
        assertTrue("must say nothing changed, got: $msg", msg.contains("nothing was changed"))
        assertTrue("must name what the caller can pass, got: $msg", msg.contains("--density"))
        assertTrue("must name the pair, got: $msg", msg.contains("--width") && msg.contains("--height"))
    }

    @Test
    fun `an unrelated option still leaves nothing to do`() {
        assertTrue(refused(listOf("--compact")).contains("nothing was changed"))
    }

    // ── 2: a value that is not a number ─────────────────────────────────────

    @Test
    fun `a non-numeric density is refused, not skipped`() {
        val msg = refused(listOf("--density", "abc"))
        assertTrue("must quote the rejected value, got: $msg", msg.contains("abc"))
        assertTrue("must name the flag, got: $msg", msg.contains("--density"))
    }

    @Test
    fun `a non-numeric width is refused`() {
        assertTrue(refused(listOf("--width", "1080p", "--height", "2400")).contains("1080p"))
    }

    @Test
    fun `a non-numeric height is refused`() {
        assertTrue(refused(listOf("--width", "1080", "--height", "tall")).contains("tall"))
    }

    @Test
    fun `a decimal is not a density`() {
        assertTrue(refused(listOf("--density", "420.5")).contains("420.5"))
    }

    // ── 3: half a size ──────────────────────────────────────────────────────

    @Test
    fun `width without height is refused and names the missing half`() {
        val msg = refused(listOf("--width", "1080"))
        assertTrue("must name the missing flag, got: $msg", msg.contains("--height"))
        assertTrue("must say the pair is required, got: $msg", msg.contains("width AND height"))
    }

    @Test
    fun `height without width is refused and names the missing half`() {
        val msg = refused(listOf("--height", "2400"))
        assertTrue("must name the missing flag, got: $msg", msg.contains("--width"))
    }

    @Test
    fun `width alone with a valid density is still refused`() {
        // The density must not rescue the half-specified size: `wm size` would
        // still be skipped, so the pair has to be refused either way.
        assertTrue(refused(listOf("--width", "1080", "--density", "420")).contains("--height"))
    }

    // ── range ───────────────────────────────────────────────────────────────

    @Test
    fun `a density of zero is refused`() {
        val msg = refused(listOf("--density", "0"))
        assertTrue("must give the accepted range, got: $msg", msg.contains("72") && msg.contains("1200"))
    }

    @Test
    fun `an absurd density is refused`() {
        assertTrue(refused(listOf("--density", "99999")).contains("99999"))
    }

    @Test
    fun `a one-pixel width is refused`() {
        val msg = refused(listOf("--width", "1", "--height", "2400"))
        assertTrue("must give the accepted range, got: $msg", msg.contains("100") && msg.contains("10000"))
    }

    @Test
    fun `an oversized height is refused`() {
        assertTrue(refused(listOf("--width", "1080", "--height", "999999")).contains("999999"))
    }

    // ── 4: a value option spelled bare ──────────────────────────────────────

    @Test
    fun `a trailing bare density is refused as needing a value`() {
        // `--density` with nothing after it: the shared parser files it as a
        // BOOLEAN flag, so a `get("density")` read never sees it.
        val msg = refused(listOf("set", "--density"))
        assertTrue("must say a value is needed, got: $msg", msg.contains("--density needs a value"))
    }

    @Test
    fun `a bare width is refused as needing a value`() {
        assertTrue(refused(listOf("--width")).contains("--width needs a value"))
    }

    @Test
    fun `a bare density followed by another option is refused`() {
        assertTrue(refused(listOf("--density", "--width", "1080")).contains("--density needs a value"))
    }

    // ── the handler must actually consult the plan (shape) ──────────────────

    private fun handlerSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, HANDLER_REL)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$HANDLER_REL not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** The `displaySet` body, sliced by declaration (brace-matched). */
    private fun displaySetBody(): String {
        val src = handlerSource()
        val start = src.indexOf("private fun displaySet(")
        assertTrue("displaySet must exist", start >= 0)
        var depth = 0
        var i = src.indexOf('{', start)
        val from = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced displaySet")
    }

    @Test
    fun `displaySet consults the plan and refuses on a refusal`() {
        val body = displaySetBody()
        assertTrue("must call planDisplaySet", body.contains("planDisplaySet(args)"))
        assertTrue("must handle the Refused arm", body.contains("DisplaySetPlan.Refused"))
        assertTrue("must answer INVALID_ARGS", body.contains("INVALID_ARGS"))
        // The rejected shapes must be gone: the old body parsed and skipped.
        assertTrue("must not read the raw int itself", !body.contains("args.getInt(\"density\")"))
        assertTrue("must not read the raw width itself", !body.contains("args.getInt(\"width\")"))
        assertTrue("must not read the raw height itself", !body.contains("args.getInt(\"height\")"))
    }

    @Test
    fun `the plan drives the wm calls, not the raw args`() {
        val body = displaySetBody()
        assertTrue("the density call reads the plan", body.contains("plan.density?.let"))
        assertTrue("the size call reads the plan", body.contains("val w = plan.width"))
        assertTrue("the size call reads the plan", body.contains("val h = plan.height"))
    }

    @Test
    fun `the help text documents the pair requirement`() {
        val src = handlerSource()
        assertTrue(
            "help must show width and height as a pair",
            src.contains("display set [--width N --height N]"),
        )
    }

    /** `errEnvelope` is the only refusal exit the handler has; sanity-check it exists. */
    @Test
    fun `the refusal path is the handler's existing error envelope`() {
        val src = handlerSource()
        assertTrue("errEnvelope must exist", src.contains("private fun errEnvelope("))
        assertTrue("the envelope carries a code", src.contains("JSONObject().put(\"code\", code)"))
        assertTrue("the plan's message reaches the caller", displaySetBody().contains("plan.message"))
    }

    /** Guards the JSON shape the envelope still promises after the change. */
    @Test
    fun `the refusal envelope keeps its ok false shape`() {
        val obj = JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", "INVALID_ARGS").put("message", "display set needs --density"),
        )
        assertEquals(false, obj.getBoolean("ok"))
        assertEquals("INVALID_ARGS", obj.getJSONObject("error").getString("code"))
    }
}
