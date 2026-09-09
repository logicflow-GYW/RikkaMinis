package com.rikkaminis.app.ui.sandbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private fun observeInstallProgress(manager: RootfsManager) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            manager.installState.collect { state ->
                when (state) {
                    is RootfsInstallState.Idle -> Unit
                    is RootfsInstallState.Preparing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Preparing rootfs…",
                            installProgress = 0f,
                        )
                    is RootfsInstallState.Extracting ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Extracting rootfs… ${(state.progress * 100).toInt()}%",
                            installProgress = state.progress,
                        )
                    is RootfsInstallState.Finalizing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Finalizing…",
                            installProgress = 1f,
                        )
                    is RootfsInstallState.Installed,
                    is RootfsInstallState.Failed -> {
                        _uiState.value = _uiState.value.copy(installProgress = null)
                        progressJob?.cancel()
                    }
                }
            }
        }
    }

    fun refresh(context: Context) {
        val manager = RootfsManager.getInstance(context)

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

    fun install(context: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Installing rootfs...",
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(context)
        observeInstallProgress(manager)
        viewModelScope.launch {
            try {
                manager.installIfNeeded()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    resultMessage = "Rootfs installed successfully",
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Installation failed: ${e.message}",
                    installProgress = null,
                )
            }
        }
    }

    fun resetRootfs(context: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Resetting rootfs...",
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(context)
        observeInstallProgress(manager)
        viewModelScope.launch {
            try {
                manager.reset()

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    resultMessage = "Rootfs reset complete",
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Reset failed: ${e.message}",
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
    fun repairRootfs(context: Context) {
        val manager = RootfsManager.getInstance(context)
        val initial = manager.verifyIntegrity()
        if (initial.healthy) {
            _uiState.value = _uiState.value.copy(
                lastOperationSuccess = true,
                resultMessage = "Rootfs is healthy — no repair needed",
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Repairing rootfs…",
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
                        "Rootfs repaired — missing files restored"
                    } else {
                        "Repair failed — still missing: ${after.missing.joinToString(", ")}"
                    },
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Repair failed: ${e.message}",
                    installProgress = null,
                )
            }
        }
    }
}
