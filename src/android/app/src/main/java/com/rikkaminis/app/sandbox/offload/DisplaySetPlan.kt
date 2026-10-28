package com.rikkaminis.app.sandbox.offload

/**
 * [meta1028-B05 F-09] The `android-shizuku-cli display set` argument decision.
 *
 * Top-level and free of Android types (no `Context`, no Shizuku binder) so the
 * whole contract is exercised by a plain JVM unit test — the same reason
 * [FastioSearch]'s walk engines and [PageSizeOutcome] live beside their
 * handlers. `displaySet` needs a running Shizuku service to call `wm`, so
 * without this split the decision could only be reasoned about, not tested.
 *
 * The three silent-failure shapes this closes (all verified against the real
 * handler at meta1028-B05 F-09):
 *
 *  1. **no recognised option** → the old body answered `{"ok":true,"data":{}}`,
 *     i.e. "your override succeeded" while nothing ran;
 *  2. **a value that is not a number** (`--density abc`) → `getInt` returned
 *     null, indistinguishable from "not given", so the call was skipped and
 *     still reported success;
 *  3. **half a size** (`--width 1080` alone) → `wm size` takes the pair as ONE
 *     `WxH` argument, so the half that WAS spelled was dropped silently.
 */

/**
 * Accepted `--width` / `--height` range, in pixels.
 *
 * Below [DISPLAY_MIN_DIMENSION] the panel is smaller than any real (or
 * virtual) display and `wm size` leaves the UI unusable with no way back except
 * `display reset`; above [DISPLAY_MAX_DIMENSION] nothing is allocated anyway
 * (SurfaceFlinger clamps to the physical panel), so the call only proves the
 * caller mistyped. Chosen wide on purpose: the guard is against nonsense, not
 * against unusual hardware.
 */
internal const val DISPLAY_MIN_DIMENSION = 100
internal const val DISPLAY_MAX_DIMENSION = 10_000

/**
 * Accepted `--density` range, in dpi. Real panels report 120–640; 72 is the
 * mdpi baseline `wm density` documents, 1200 leaves headroom for the density
 * overrides used when testing high-dpi layouts. Outside this window the value
 * cannot be a density anyone meant (`--density 0` collapses every dp to zero
 * pixels), so it is refused instead of applied.
 */
internal const val DISPLAY_MIN_DENSITY = 72
internal const val DISPLAY_MAX_DENSITY = 1_200

/** The value-taking options `display set` owns, in the order they are reported. */
internal val DISPLAY_SET_VALUE_OPTIONS = listOf("width", "height", "density")

/** Outcome of [planDisplaySet] — a decision, so nothing reaches `wm` unvalidated. */
internal sealed class DisplaySetPlan {
    /**
     * Run these calls. `width` and `height` are set together or not at all
     * (`wm size` takes a single `WxH`); `density` is independent.
     */
    data class Ready(val width: Int?, val height: Int?, val density: Int?) : DisplaySetPlan()

    /** Refuse the command; [message] names the argument and says why. */
    data class Refused(val message: String) : DisplaySetPlan()
}

/**
 * Decide what `display set` should do with [args]. Pure.
 *
 * A bare value option (`--density` with nothing after it) is a fourth case: the
 * shared parser files it as a BOOLEAN flag rather than a value, which is why it
 * is read through [OffloadArgs.unknownFlags] — a `hasFlag("density")` read would
 * be a dead branch AND would demand `density` join `NO_VALUE_FLAGS` (which would
 * then eat the value of every legitimate `--density 420`).
 */
internal fun planDisplaySet(args: OffloadArgs): DisplaySetPlan {
    val bare = args.unknownFlags(emptySet())
    for (name in DISPLAY_SET_VALUE_OPTIONS) {
        if (name in bare) {
            return DisplaySetPlan.Refused(
                "--$name needs a value" +
                    if (name == "density") " (e.g. --density 420)" else " (e.g. --$name 1080)",
            )
        }
    }

    val rawWidth = args.get("width")
    val rawHeight = args.get("height")
    val rawDensity = args.get("density")
    if (rawWidth == null && rawHeight == null && rawDensity == null) {
        return DisplaySetPlan.Refused(
            "display set needs --density and/or --width with --height; " +
                "nothing was changed ('display reset' restores the panel defaults)",
        )
    }

    var width: Int? = null
    var height: Int? = null
    if (rawWidth != null || rawHeight != null) {
        // A half-specified pair cannot be honoured: `wm size` takes WxH as ONE
        // argument. Refuse instead of dropping the half the caller did spell.
        val wRaw = rawWidth ?: return DisplaySetPlan.Refused(
            "--height is given without --width; `wm size` takes width AND height " +
                "(pass --width N --height M, or omit both)",
        )
        val hRaw = rawHeight ?: return DisplaySetPlan.Refused(
            "--width is given without --height; `wm size` takes width AND height " +
                "(pass --width N --height M, or omit both)",
        )
        val w = wRaw.toIntOrNull()
            ?: return DisplaySetPlan.Refused("--width '$wRaw' is not an integer (e.g. --width 1080)")
        if (w !in DISPLAY_MIN_DIMENSION..DISPLAY_MAX_DIMENSION) {
            return DisplaySetPlan.Refused(
                "--width $w is outside $DISPLAY_MIN_DIMENSION..$DISPLAY_MAX_DIMENSION px; " +
                    "refusing to leave the panel unusable",
            )
        }
        val h = hRaw.toIntOrNull()
            ?: return DisplaySetPlan.Refused("--height '$hRaw' is not an integer (e.g. --height 2400)")
        if (h !in DISPLAY_MIN_DIMENSION..DISPLAY_MAX_DIMENSION) {
            return DisplaySetPlan.Refused(
                "--height $h is outside $DISPLAY_MIN_DIMENSION..$DISPLAY_MAX_DIMENSION px; " +
                    "refusing to leave the panel unusable",
            )
        }
        width = w
        height = h
    }

    var density: Int? = null
    if (rawDensity != null) {
        val d = rawDensity.toIntOrNull()
            ?: return DisplaySetPlan.Refused("--density '$rawDensity' is not an integer (e.g. --density 420)")
        if (d !in DISPLAY_MIN_DENSITY..DISPLAY_MAX_DENSITY) {
            return DisplaySetPlan.Refused(
                "--density $d is outside $DISPLAY_MIN_DENSITY..$DISPLAY_MAX_DENSITY dpi; " +
                    "refusing to leave the panel unusable",
            )
        }
        density = d
    }
    return DisplaySetPlan.Ready(width = width, height = height, density = density)
}
