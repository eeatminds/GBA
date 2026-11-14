package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper

/**
 * Game files the player added one by one with "Add a game". The app keeps read access to
 * each of them (a persistable permission), so they stay in the library without needing
 * access to a whole folder.
 */
object PickedGameFiles {
    private const val KEY = "picked_game_files"

    fun getAll(context: Context): Set<Uri> {
        return preferences(context).getStringSet(KEY, emptySet())
            .orEmpty()
            .map { Uri.parse(it) }
            .toSet()
    }

    fun add(
        context: Context,
        uris: Collection<Uri>,
    ) {
        val current = preferences(context).getStringSet(KEY, emptySet()).orEmpty()
        val updated = current + uris.map { it.toString() }
        preferences(context).edit().putStringSet(KEY, updated).apply()
    }

    fun remove(
        context: Context,
        uri: Uri,
    ) {
        val current = preferences(context).getStringSet(KEY, emptySet()).orEmpty()
        preferences(context).edit().putStringSet(KEY, current - uri.toString()).apply()
    }

    fun contains(
        context: Context,
        uri: Uri,
    ): Boolean = uri.toString() in preferences(context).getStringSet(KEY, emptySet()).orEmpty()

    private fun preferences(context: Context) = SharedPreferencesHelper.getLegacySharedPreferences(context)
}
