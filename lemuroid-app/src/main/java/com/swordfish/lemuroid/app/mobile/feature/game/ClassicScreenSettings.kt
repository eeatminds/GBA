package com.swordfish.lemuroid.app.mobile.feature.game

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Screen size and position for handheld games, chosen in the game menu and shared by all of them. */
object ClassicScreenSettings {
    private const val PREFERENCES = "classic_screen"
    private const val KEY_SIZE = "size"
    private const val KEY_POSITION = "position"

    data class Values(
        val size: ClassicScreenSize,
        val position: ClassicScreenPosition,
    )

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(context: Context): Values {
        val preferences = preferences(context)
        return Values(
            size = enumValueOrDefault(preferences.getString(KEY_SIZE, null), ClassicScreenSize.FIT),
            position = enumValueOrDefault(preferences.getString(KEY_POSITION, null), ClassicScreenPosition.TOP),
        )
    }

    fun setSize(
        context: Context,
        size: ClassicScreenSize,
    ) = preferences(context).edit().putString(KEY_SIZE, size.name).apply()

    fun setPosition(
        context: Context,
        position: ClassicScreenPosition,
    ) = preferences(context).edit().putString(KEY_POSITION, position.name).apply()

    /** Current values, updated as soon as they change (for example from the game menu). */
    @Composable
    fun rememberValues(): State<Values> {
        val context = LocalContext.current
        val state = remember { mutableStateOf(read(context)) }
        DisposableEffect(context) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read(context) }
            preferences(context).registerOnSharedPreferenceChangeListener(listener)
            onDispose { preferences(context).unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return state
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        name: String?,
        default: T,
    ): T = enumValues<T>().firstOrNull { it.name == name } ?: default
}
