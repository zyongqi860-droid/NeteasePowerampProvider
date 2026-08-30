package com.yczz.neteasepoweramp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row of the cached songs table. This is the ONLY data source the provider reads from,
 * so Poweramp scans never hit the network (phase-2 rule: provider methods do no IO beyond Room).
 */
@Entity(tableName = "songs")
data class SongEntity(
    /** NetEase song id; also the stable suffix of the provider document id. */
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    /** Duration in milliseconds. */
    @ColumnInfo(name = "duration")
    val duration: Long,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String,
    /** Wall clock time of the last insert/update, for future cache invalidation. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    // ---------------------------------------------------------------- phase 4 source metadata
    /** Upstream source tag, e.g. "netease" (task section 3: fixed per source, extensible). */
    @ColumnInfo(name = "source", defaultValue = "netease")
    val source: String = "netease",
    /** Source artist id; 0 when the API did not provide one. */
    @ColumnInfo(name = "artist_id", defaultValue = "0")
    val artistId: Long = 0L,
    /** Source album id; 0 when the API did not provide one. */
    @ColumnInfo(name = "album_id", defaultValue = "0")
    val albumId: Long = 0L,
    // ---------------------------------------------------------------- phase 3 playback cache
    // The columns below are a CACHE of the last resolved playback source, never the source of
    // truth: remote URLs expire, so openDocument always re-resolves when urlExpireTime passed.

    /** Identifier of the upstream source this song came from (e.g. "netease", "mock"). Empty for phase-2 rows. */
    @ColumnInfo(name = "source_id")
    val sourceId: String = "",
    /** Quality label reported by the source (e.g. "320k"). Empty when unknown. */
    @ColumnInfo(name = "quality")
    val quality: String = "",
    /** Last resolved playback URL; may be empty and may be expired - never persisted as permanent. */
    @ColumnInfo(name = "last_play_url")
    val lastPlayUrl: String = "",
    /** Epoch ms after which [lastPlayUrl] must be re-resolved; 0 = no cached URL. */
    @ColumnInfo(name = "url_expire_time")
    val urlExpireTime: Long = 0L,
    // ---------------------------------------------------------------- search batch (v5)
    /**
     * Which auto-search sync wrote this row. 0 = permanent (manual saves, playlist imports,
     * legacy pinned rows); >0 = epoch-ms batch id of the netease_search RPC that inserted it.
     * Each new sync wipes every other non-zero batch, so Poweramp's search only ever sees the
     * latest keyword's results. Schema default is 1 so rows migrated from v4 count as the
     * legacy batch and get cleaned on the first post-update sync.
     */
    @ColumnInfo(name = "search_batch", defaultValue = "1")
    val searchBatch: Long = 0L,
    /**
     * Position of this row inside its search batch (0 = NetEase's most relevant hit).
     * -1 = not an auto-search row (manual saves, playlist imports). The 搜索结果 folder lists
     * batch rows by this position so Poweramp's scan - and therefore its search result order -
     * follows NetEase's popularity ranking instead of alphabetical order.
     */
    @ColumnInfo(name = "batch_pos", defaultValue = "-1")
    val batchPos: Int = -1,
)
