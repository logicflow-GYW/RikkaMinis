package android.util

/** Probe stub — production slices only log through android.util.Log. */
object Log {
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String, t: Throwable): Int = 0
    @JvmStatic fun w(tag: String, msg: String, t: Throwable): Int = 0
}
