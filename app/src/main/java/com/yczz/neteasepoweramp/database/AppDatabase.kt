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
 *
 * Version history:
 * - v1: songs table (phase 2)
 * - v2: + playback cache columns on songs (phase 3)
 * - v3: + source metadata, albums, playlists, playlist_tracks (phase 4)
 * - v4: + lyrics, playback_history tables + indexes (phase 5)
 * - v5: + songs.search_batch (rolling auto-search window)
 * - v6: + songs.batch_pos (relevance order inside the batch)
 */
@Database(
    entities = [
        SongEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LyricEntity::class,
        PlaybackHistoryEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun lyricDao(): LyricDao

    abstract fun playbackHistoryDao(): PlaybackHistoryDao

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

        /** v3 -> v4: lyrics cache table + playback history table + missing indexes. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Lyrics table.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lyrics (" +
                        "song_id INTEGER NOT NULL PRIMARY KEY, " +
                        "content TEXT NOT NULL, " +
                        "updated_at INTEGER NOT NULL)",
                )

                // Playback history table.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "song_id INTEGER NOT NULL, " +
                        "played_at INTEGER NOT NULL, " +
                        "duration INTEGER NOT NULL, " +
                        "completed INTEGER NOT NULL DEFAULT 0)",
                )

                // Indexes for performance.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_song_id ON playback_history(song_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_played_at ON playback_history(played_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_source ON songs(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_updated_at ON songs(updated_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlists_updated_at ON playlists(updated_at)")
            }
        }

        /** v4 -> v5: rolling search batch column. Legacy rows join batch 1 (first sync cleans them). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN search_batch INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v5 -> v6: relevance position of a row inside its search batch. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN batch_pos INTEGER NOT NULL DEFAULT -1")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
    }
}
