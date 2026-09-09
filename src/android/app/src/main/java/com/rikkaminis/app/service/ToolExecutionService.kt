package com.rikkaminis.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Tool-execution process: the long-lived home of the native_offload socket
 * server and its handler registry ([native-oom Phase 1]).
 *
 * Goal (per native-oom-construction-plan.md Phase 1): keep the heavy
 * native/mmap load (per-request handler work, worker threads, mapped
 * interim files) OUT of the main process so a runaway tool burst crashes a
 * cheap, contained :toolservice process instead of the UI process that owns
 * the database, the socket namespace the PRoot guest connects into, and
 * foreground session state.
 *
 * Process role (`android:process=":toolservice"`): like `:modelservice`, this
 * process runs `MinisApp.onCreate` too. The main-process `MinisApp` detects
 * this role via [MinisApp.isToolServiceProcess] and skips the full app init;
 * THIS service then builds the tool-specific dependency graph instead
 * (forbidden: UI / WebView / foreground-session components).
 *
 * Migration state (2026-08-20):
 *  - Phase 1 step 1 (OffloadHandlerCatalog) landed — the main process no
 *    longer needs handler INSTANCES to generate stubs/args.
 *  - This service is declared so the `:toolservice` process exists and the
 *    process role is wired up, but it does NOT yet bind the socket
 *    `NativeOffloadServer` (the main process still owns it). Step 3 moves
 *    socket ownership here and is gated on a真机 check that the main-process
 *    PRoot guest can still reach the abstract socket across processes.
 */
class ToolExecutionService : Service() {

    companion object {
        private const val TAG = "ToolExecutionService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ToolExecutionService onCreate pid=${android.os.Process.myPid()}")
        // Step 3 will build the tool-specific dependency graph and own the
        // NativeOffloadServer socket + handler registry here. Until then this
        // process hosts nothing — the socket stays in the main process.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ToolExecutionService onStartCommand startId=$startId")
        // Long-lived: stay running while the app needs tool execution.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ToolExecutionService onDestroy")
        super.onDestroy()
    }
}
