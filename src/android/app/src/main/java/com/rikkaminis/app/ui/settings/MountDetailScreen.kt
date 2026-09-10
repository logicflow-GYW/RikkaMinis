package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.MountedFoldersStore
import kotlinx.coroutines.launch
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.ui.theme.ChatColors

/**
 * Detail/edit screen for a single mounted folder. Mirrors iOS
 * MountDetailView (external case only). Shows where the mount points,
 * toggles the user soft-lock on writes, opens the in-app file browser
 * (placeholder toast for T219-2), and unmounts with confirmation.
 *
 * Renaming is deliberately not offered: a mount's name *is* its path
 * identity (`/var/minis/mounts/<name>`), so a rename silently
 * invalidates every path already written into sessions and scripts,
 * and the UI cannot report that. To change the name, unmount and
 * mount the same host folder again.
 *
 * Save is enabled only while the write toggle differs from the stored
 * value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountDetailScreen(
    store: MountedFoldersStore,
    mountId: String,
    onBack: () -> Unit,
    onBrowseFiles: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == mountId }

    if (entry == null) {
        // Entry was removed (e.g. unmount + back-stack pop race). Pop out.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var allowWrite by remember(entry.id) { mutableStateOf(entry.userAllowWrite) }
    var showUnmountConfirm by remember { mutableStateOf(false) }

    val canSave = allowWrite != entry.userAllowWrite

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mount_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    MinisTextButton(
                        enabled = canSave,
                        onClick = {
                            scope.launch {
                                store.setUserAllowWrite(entry.id, allowWrite)
                                onBack()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            HeaderCard(entry = entry)

            Spacer(Modifier.height(20.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mount_add_allow_writes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.mount_add_allow_writes_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = allowWrite,
                        onCheckedChange = { allowWrite = it },
                        // Disable when the OS-level grant itself isn't writable.
                        enabled = entry.isWritable,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            ActionRow(
                icon = Icons.Outlined.Folder,
                tint = ChatColors.link,
                label = stringResource(R.string.mount_detail_browse_files),
                onClick = onBrowseFiles,
            )

            Spacer(Modifier.height(12.dp))
            ActionRow(
                icon = Icons.Outlined.Folder,
                tint = MaterialTheme.colorScheme.error,
                label = stringResource(R.string.mount_unmount_confirm),
                destructive = true,
                onClick = { showUnmountConfirm = true },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showUnmountConfirm) {
        AlertDialog(
            onDismissRequest = { showUnmountConfirm = false },
            title = { Text(stringResource(R.string.mount_unmount_title)) },
            text = { Text(stringResource(R.string.mount_unmount_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showUnmountConfirm = false
                    scope.launch {
                        store.remove(entry.id)
                        onBack()
                    }
                }) {
                    Text(
                        stringResource(R.string.mount_unmount_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showUnmountConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun HeaderCard(entry: MountedFoldersStore.Entry) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF9500)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderShared,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "/var/minis/mounts/${entry.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val source = entry.sourceDisplayName.ifEmpty {
                    stringResource(R.string.mount_path_unavailable)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "← $source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
            Text(
                text = label,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
