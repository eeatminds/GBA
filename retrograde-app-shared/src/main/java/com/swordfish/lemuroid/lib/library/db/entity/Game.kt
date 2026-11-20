/*
 * Game.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.lib.library.db.entity

import androidx.recyclerview.widget.DiffUtil
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "games",
    indices = [
        Index("id", unique = true),
        Index("fileUri", unique = true),
        Index("title"),
        Index("systemId"),
        Index("lastIndexedAt"),
        Index("lastPlayedAt"),
        Index("isFavorite"),
    ],
)
data class Game(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val fileName: String,
    val fileUri: String,
    val title: String,
    val systemId: String,
    val developer: String?,
    val coverFrontUrl: String?,
    val lastIndexedAt: Long,
    val lastPlayedAt: Long? = null,
    val isFavorite: Boolean = false,
    val customName: String? = null,
    val customCoverUri: String? = null,
) : Serializable {

    fun displayName(): String = customName ?: title
    fun displayCoverUrl(): String? = customCoverUri ?: coverFrontUrl?.let(::mirroredCoverUrl)

    companion object {
        private const val LIBRETRO_THUMBNAILS = "thumbnails.libretro.com/"

        /**
         * Covers from the libretro thumbnails server, read from its GitHub mirror instead (same
         * pictures; the server's folders stopped answering). Games already in the library keep
         * their saved address, so this is done when showing the cover.
         */
        fun mirroredCoverUrl(url: String): String {
            val path = url.substringAfter(LIBRETRO_THUMBNAILS, "").takeIf { it.isNotEmpty() } ?: return url
            val parts = path.split('/', limit = 3)
            if (parts.size != 3) return url
            val (system, type, file) = parts
            val encodedFile = java.net.URLEncoder.encode(file, "UTF-8").replace("+", "%20")
            return "https://raw.githubusercontent.com/libretro-thumbnails/${system.replace(' ', '_')}/master/$type/$encodedFile"
        }

        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<Game>() {
                override fun areItemsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem.id == newItem.id
                }

                override fun areContentsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem == newItem
                }
            }
    }
}
