package com.swordfish.lemuroid.app.mobile.feature.gamemenu.cheats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.cheats.Cheat
import com.swordfish.lemuroid.app.shared.cheats.CheatsManager
import com.swordfish.lemuroid.lib.library.db.entity.Game

/** Cheats for the running game. Changes are saved right away and applied when the menu closes. */
@Composable
fun GameMenuCheatsScreen(game: Game) {
    val context = LocalContext.current
    val manager = remember { CheatsManager(context) }
    val cheats = remember { mutableStateListOf<Cheat>().apply { addAll(manager.getCheats(game)) } }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    fun save() = manager.setCheats(game, cheats.toList())

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        FilledTonalButton(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            onClick = { adding = true },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.cheats_add), modifier = Modifier.padding(start = 8.dp))
        }

        if (cheats.isEmpty()) {
            Text(
                text = stringResource(R.string.cheats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        cheats.forEachIndexed { index, cheat ->
            ListItem(
                headlineContent = { Text(cheat.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        cheat.code.lines().first(),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Switch(
                        checked = cheat.enabled,
                        onCheckedChange = {
                            cheats[index] = cheat.copy(enabled = it)
                            save()
                        },
                    )
                },
                trailingContent = {
                    Column {
                        IconButton(onClick = { editingIndex = index }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.cheats_edit))
                        }
                        IconButton(
                            onClick = {
                                cheats.removeAt(index)
                                save()
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cheats_delete))
                        }
                    }
                },
            )
        }
    }

    if (adding) {
        CheatDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = {
                cheats.add(it)
                save()
                adding = false
            },
        )
    }

    editingIndex?.let { index ->
        CheatDialog(
            initial = cheats.getOrNull(index),
            onDismiss = { editingIndex = null },
            onSave = {
                if (index in cheats.indices) cheats[index] = it
                save()
                editingIndex = null
            },
        )
    }
}

@Composable
private fun CheatDialog(
    initial: Cheat?,
    onDismiss: () -> Unit,
    onSave: (Cheat) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var code by rememberSaveable { mutableStateOf(initial?.code.orEmpty()) }
    val defaultName = stringResource(R.string.cheats_default_name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.cheats_add else R.string.cheats_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.cheats_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = code,
                    onValueChange = { code = it.take(4000) },
                    label = { Text(stringResource(R.string.cheats_code)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3,
                )
                Text(
                    stringResource(R.string.cheats_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank(),
                onClick = { onSave(Cheat(name.ifBlank { defaultName }, code.trim(), initial?.enabled ?: true)) },
            ) { Text(stringResource(R.string.cheats_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.online_cancel)) }
        },
    )
}
