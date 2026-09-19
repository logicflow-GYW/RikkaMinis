package com.rikkaminis.app.ui.sandbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rikkaminis.app.R
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.sandbox.RootfsInstallState
import com.rikkaminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RootfsManagementUiState(
    val isInstalled: Boolean = false,
    val isProcessing: Boolean = false,
    val statusMessage: String = "",
    val resultMessage: String? = null,
    val lastOperationSuccess: Boolean = false,
    val rootfsSize: Long = 0L,
    val rootfsPath: String = "",
    /** Current install phase + 0..1 progress (null when not installing). */
    val installProgress: Float? = null,
)

class RootfsManagementViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RootfsManagementUiState())
    val uiState: StateFlow<RootfsManagementUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null

    /**
     * Subscribe to the manager's installState and mirror progress + status
     * text into [_uiState]. Cancelled on completion so we don't leak a job
     * across multiple install() calls.
     */
    private fun observeInstallProgress(manager: RootfsManager, ctx: android.content.Context) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            manager.installState.collect { state ->
                when (state) {
                    is RootfsInstallState.Idle -> Unit
                    is RootfsInstallState.Preparing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = ctx.getString(R.string.rootfs_msg_preparing),
                            installProgress = 0f,
                        )
                    is RootfsInstallState.Extracting ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = ctx.getString(R.string.rootfs_msg_extracting, (state.progress * 100).toInt()),
                            installProgress = state.progress,
                        )
                    is RootfsInstallState.Finalizing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = ctx.getString(R.string.rootfs_msg_finalizing),
                            installProgress = 1f,
                        )
                    is RootfsInstallState.Installed,
                    is RootfsInstallState.Failed -> {
                        // [fix/render-ui F-277] No `progressJob?.cancel()` here.
                        // Cancelling the job that is currently running this
                        // collector from inside `collect {}` is self-cancellation:
                        // it made "who re-subscribes on the next install()"
                        // depend on how far this emission got before the
                        // cancellation landed. The terminal state is reached
                        // exactly once per install, so the job simply ends on
                        // its own — and `observeInstallProgress` cancels any
                        // previous job before starting a new one.
                        _uiState.value = _uiState.value.copy(installProgress = null)
                    }
                }
            }
        }
    }

    fun refresh(ctx: Context) {
        val manager = RootfsManager.getInstance(ctx)

        _uiState.value = _uiState.value.copy(
            isInstalled = manager.isInstalled,
            rootfsPath = manager.rootfsDir.absolutePath,
        )

        if (manager.isInstalled) {
            viewModelScope.launch {
                try {
                    val size = manager.getRootfsSize()
                    _uiState.value = _uiState.value.copy(rootfsSize = size)
                } catch (_: Exception) { }
            }
        }
    }

    fun install(ctx: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = ctx.getString(R.string.rootfs_msg_installing),
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(ctx)
        observeInstallProgress(manager, ctx)
        viewModelScope.launch {
            try {
                manager.installIfNeeded()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    resultMessage = ctx.getString(R.string.rootfs_msg_installed),
                    installProgress = null,
                )
                refresh(ctx)
            } catch (e: Exception) {
                // [fix/render-ui F-277] The raw exception used to be rendered as
                // the user-facing result line. It now goes to the log only.
                AppLogger.error("RootfsManagement", "install failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = ctx.getString(R.string.rootfs_msg_install_failed),
                    installProgress = null,
                )
            }
        }
    }

    fun resetRootfs(ctx: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = ctx.getString(R.string.rootfs_msg_resetting),
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(ctx)
        observeInstallProgress(manager, ctx)
        viewModelScope.launch {
            try {
                manager.reset()

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    resultMessage = ctx.getString(R.string.rootfs_msg_reset_complete),
                    installProgress = null,
                )
                refresh(ctx)
            } catch (e: Exception) {
                AppLogger.error("RootfsManagement", "reset failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = ctx.getString(R.string.rootfs_msg_reset_failed),
                    installProgress = null,
                )
            }
        }
    }

    /**
     * Verify critical rootfs files and repair in place when anything is
     * missing. Less destructive than [resetRootfs]: runs `apk fix` inside the
     * guest to restore bash/readline/ncurses, only falling back to a full
     * reset when apk itself is unusable. The terminal falls back to /bin/sh
     * independently if repair still leaves bash broken.
     */
    fun repairRootfs(ctx: Context) {
        val manager = RootfsManager.getInstance(ctx)
        val initial = manager.verifyIntegrity()
        if (initial.healthy) {
            _uiState.value = _uiState.value.copy(
                lastOperationSuccess = true,
                resultMessage = ctx.getString(R.string.rootfs_msg_healthy),
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = ctx.getString(R.string.rootfs_msg_repairing),
            resultMessage = null,
            installProgress = 0f,
        )
        viewModelScope.launch {
            try {
                val repaired = manager.autoRepair()
                val after = manager.verifyIntegrity()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = repaired,
                    resultMessage = if (repaired) {
                        ctx.getString(R.string.rootfs_msg_repaired)
                    } else {
                        ctx.getString(R.string.rootfs_msg_repair_missing, after.missing.joinToString(", "))
                    },
                    installProgress = null,
                )
                refresh(ctx)
            } catch (e: Exception) {
                AppLogger.error("RootfsManagement", "repair failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = ctx.getString(R.string.rootfs_msg_repair_failed),
                    installProgress = null,
                )
            }
        }
    }
}
