package com.yczz.neteasepoweramp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Process-wide Room database. The provider and the UI share this single instance; Room itself
 * serializes writes, so no extra locking is needed around DAO calls.
 */
@Database(
    entities = [SongEntity::class, AlbumEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    abstract fun playlistDao(): PlaylistDao

    companion object {
        private const val NAME = "netease_songs.db"

        /** v1 -> v2: playback cache columns; ALTER TABLE keeps user-saved songs intact. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN source_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE songs ADD COLUMN quality TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE songs ADD COLUMN last_play_url TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE songs ADD COLUMN url_expire_time INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3: source metadata columns on songs + albums/playlists/playlist_tracks tables. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN source TEXT NOT NULL DEFAULT 'netease'")
                db.execSQL("ALTER TABLE songs ADD COLUMN artist_id INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN album_id INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS albums (" +
                        "album_id INTEGER NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "artist TEXT NOT NULL, " +
                        "cover_url TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playlists (" +
                        "playlist_id INTEGER NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "cover_url TEXT NOT NULL, " +
                        "track_count INTEGER NOT NULL DEFAULT 0, " +
                        "updated_at INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playlist_tracks (" +
                        "playlist_id INTEGER NOT NULL, " +
                        "song_id INTEGER NOT NULL, " +
                        "position INTEGER NOT NULL, " +
                        "PRIMARY KEY(playlist_id, position), " +
                        "FOREIGN KEY(playlist_id) REFERENCES playlists(playlist_id) ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_playlist_tracks_song_id ON playlist_tracks(song_id)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /** THREADING: thread-safe lazy init; safe to call from provider and UI threads. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
