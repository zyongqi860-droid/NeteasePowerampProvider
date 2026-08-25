package com.yczz.neteasepoweramp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Process-wide Room database. The provider and the UI share this single instance; Room itself
 * serializes writes, so no extra locking is needed around [SongDao] calls.
 */
@Database(entities = [SongEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    companion object {
        private const val NAME = "netease_songs.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** THREADING: thread-safe lazy init; safe to call from provider and UI threads. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
