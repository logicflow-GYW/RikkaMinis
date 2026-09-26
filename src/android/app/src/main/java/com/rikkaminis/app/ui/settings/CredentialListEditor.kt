package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.ProviderCredentialMeta
import com.rikkaminis.app.ui.components.MinisSmallButton
import com.rikkaminis.app.ui.components.MinisSmallOutlinedButton
import com.rikkaminis.app.ui.components.MinisSmallTextButton
import com.rikkaminis.app.ui.components.SectionTextField

/**
 * [T-multi-api-key] Editor for an instance's credential list.
 *
 * ## Where the secrets live
 *
 * Only in this composable's transient state and in the `onSave` callback —
 * never in [ProviderCredentialMeta] (which deliberately has no secret field)
 * and never in the config document. Metadata (label/note/order/enabled) is
 * what round-trips; the secret is addressed by list position.
 *
 * ## Destructive actions are explicit and immediate-looking
 *
 * Deleting a row and disabling a credential both take effect on Save, and both
 * are visible before it (a removed row is simply gone; a disabled one is
 * greyed by its switch). Saving after a deletion clears the underlying slot —
 * essential, because rotation is keyed by index and a stale secret left at a
 * now-unused index would be silently reused for a key the user believed they
 * removed.
 *
 * Secrets follow their row's IDENTITY, not its position: each row remembers the
 * slot it was loaded from ([CredentialSlotRow.sourceSlot]) and the save writes
 * the resulting dense list, so deleting row 0 of three moves K2/K3's secrets
 * down with them instead of leaving K2 wearing K1's secret while K3's secret is
 * erased from the tail. A row the user adds owns no slot and can never inherit
 * one.
 */
@Composable
internal fun CredentialListEditor(
    metas: List<ProviderCredentialMeta>,
    storedKeys: Map<Int, String?>,
    onSave: (metas: List<ProviderCredentialMeta>, drafts: List<String?>, previousCount: Int) -> Unit,
) {
    val rows = remember(metas) {
        mutableStateListOf<CredentialSlotRow>().apply {
            if (metas.isEmpty()) {
                // An instance with no metadata still holds the historical
                // single key at slot 0 — surface it as one row rather than an
                // empty editor, which would read as "your key is gone".
                add(CredentialSlotRow(ProviderCredentialMeta(label = ""), draft = null, sourceSlot = 0))
            } else {
                // sourceSlot = the row's own position: this is what keeps each
                // row bound to its own secret when a deletion shifts the list
                // (see CredentialSlotRow's doc).
                metas.forEachIndexed { i, meta ->
                    add(CredentialSlotRow(meta, draft = null, sourceSlot = i))
                }
            }
        }
    }
    var expandedIndex by remember { mutableStateOf(-1) }
    var visibleIndex by remember { mutableStateOf(-1) }

    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            CredentialRowView(
                index = index,
                row = row,
                storedKey = row.effectiveSecret(storedKeys),
                expanded = expandedIndex == index,
                secretVisible = visibleIndex == index,
                canDelete = rows.size > 1,
                onToggleExpand = { expandedIndex = if (expandedIndex == index) -1 else index },
                onToggleVisibility = { visibleIndex = if (visibleIndex == index) -1 else index },
                onMetaChange = { updated -> rows[index] = rows[index].copy(meta = updated) },
                onDraftChange = { typed ->
                    // Clearing the field restores "leave stored alone" instead
                    // of writing a blank secret — a blank slot reads as a
                    // missing credential and would burn a rotation attempt.
                    rows[index] = rows[index].copy(draft = typed.ifEmpty { null })
                },
                onDelete = {
                    rows.removeAt(index)
                    if (expandedIndex >= rows.size) expandedIndex = -1
                    if (visibleIndex >= rows.size) visibleIndex = -1
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        MinisSmallOutlinedButton(
            onClick = {
                // sourceSlot = null: a brand-new row owns no slot yet, so it can
                // never inherit (and then re-save) the secret of a row the user
                // just deleted in the same editing session.
                rows.add(CredentialSlotRow(ProviderCredentialMeta(label = ""), draft = null, sourceSlot = null))
                expandedIndex = rows.lastIndex
            },
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.provider_credential_add))
        }

        Spacer(modifier = Modifier.height(12.dp))
        MinisSmallButton(
            onClick = {
                onSave(
                    rows.map { it.meta },
                    // Materialized: every row contributes its OWN secret (draft
                    // or the stored value at its original slot), so the written
                    // list is dense and positional — null now means "this row
                    // has no secret", which the repository turns into an
                    // explicit clear instead of silently keeping a stale one.
                    planCredentialSlots(rows, storedKeys),
                    // The pre-edit size drives the slot cleanup on the
                    // repository side: slots at/after this index that the user
                    // just removed must be cleared.
                    metas.size.coerceAtLeast(1),
                )
                expandedIndex = -1
            },
        ) {
            Text(stringResource(R.string.provider_detail_save_key))
        }
    }
}

@Composable
private fun CredentialRowView(
    index: Int,
    row: CredentialRow,
    storedKey: String?,
    expanded: Boolean,
    secretVisible: Boolean,
    canDelete: Boolean,
    onToggleExpand: () -> Unit,
    onToggleVisibility: () -> Unit,
    onMetaChange: (ProviderCredentialMeta) -> Unit,
    onDraftChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.meta.displayLabel(index),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            // Disabling parks a key WITHOUT deleting its secret — distinct
            // from exhaustion, which is the provider's verdict. Rotation skips
            // both, but only one is the user's own decision.
            Switch(
                checked = row.meta.isEnabled,
                onCheckedChange = { enabled -> onMetaChange(row.meta.copy(isEnabled = enabled)) },
            )
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.provider_credential_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            text = when {
                storedKey.isNullOrBlank() -> stringResource(R.string.provider_credential_empty)
                secretVisible -> storedKey
                else -> maskCredential(storedKey)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            SectionTextField(
                value = row.meta.label,
                onValueChange = { onMetaChange(row.meta.copy(label = it)) },
                placeholder = stringResource(R.string.provider_credential_label_placeholder),
            )
            Spacer(modifier = Modifier.height(4.dp))
            SectionTextField(
                value = row.meta.note,
                onValueChange = { onMetaChange(row.meta.copy(note = it)) },
                placeholder = stringResource(R.string.provider_credential_note_placeholder),
            )
            Spacer(modifier = Modifier.height(8.dp))
            SectionTextField(
                value = row.draft ?: "",
                onValueChange = onDraftChange,
                placeholder = stringResource(R.string.provider_credential_replace_placeholder),
                visualTransformation = if (secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                },
            )
        } else {
            MinisSmallTextButton(
                onClick = onToggleExpand,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(stringResource(R.string.provider_credential_edit))
            }
        }
    }
}

/**
 * Mask a credential for display: first 6 + last 4, bullet-filled when too
 * short to mask meaningfully. Mirrors the detail screen's `maskedKey` so the
 * two surfaces never disagree about what a key looks like.
 */
internal fun maskCredential(key: String): String {
    if (key.length <= 10) return key.replace(Regex("."), "•")
    return key.take(6) + "..." + key.takeLast(4)
}
