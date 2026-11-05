package com.rikkaminis.app.logging

/** Probe stub — production slices only log through AppLogger. */
object AppLogger {
    fun info(tag: String, message: String) { }
    fun warning(tag: String, message: String) { }
    fun error(tag: String, message: String) { }
    fun error(tag: String, message: String, t: Throwable) { }
    fun debug(tag: String, message: String) { }
}
