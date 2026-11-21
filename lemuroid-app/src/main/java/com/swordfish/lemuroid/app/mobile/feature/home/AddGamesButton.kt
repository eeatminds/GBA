package com.swordfish.lemuroid.app.mobile.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.swordfish.lemuroid.R

/** The "+" button on Home: add one game, add a whole folder, or get help. */
@Composable
fun AddGamesButton(
    onAddGame: () -> Unit,
    onAddFolder: () -> Unit,
    onHelp: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_games))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_game)) },
                leadingIcon = { Icon(Icons.Filled.NoteAdd, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAddGame()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_game_folder)) },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAddFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_game_help)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                onClick = {
                    expanded = false
                    onHelp()
                },
            )
        }
    }
}
