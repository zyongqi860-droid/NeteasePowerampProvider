package com.yczz.neteasepoweramp.provider

import android.database.MatrixCursor
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import com.maxmpz.poweramp.player.TrackProviderConsts
import com.yczz.neteasepoweramp.database.SongEntity

/**
 * Row builders shared by every provider cursor. Kept separate from [NeteasePowerampProvider]
 * so projection handling stays in one place and both phase-1 and phase-2 nodes look uniform.
 */
internal object ProviderCursorBuilder {

    /**
     * Folder row. [subdirHint] uses TrackProviderConsts.COLUMN_FLAGS (not the SAF flag column)
     * so Poweramp can skip loading children until the user expands the tree.
     */
    fun fillFolderRow(
        row: MatrixCursor.RowBuilder,
        documentId: String,
        displayName: String,
        lastModified: Long,
        subdirHint: Int,
    ) {
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified)
        if (subdirHint != 0) {
            row.add(TrackProviderConsts.COLUMN_FLAGS, subdirHint)
        }
    }

    /** Phase-1 fixed test track; metadata columns let Poweramp skip tag-scanning the file. */
    fun fillTestTrackRow(row: MatrixCursor.RowBuilder, audioSize: Long, lastModified: Long) {
        row.add(Document.COLUMN_DOCUMENT_ID, ProviderConstants.TRACK_DOC_ID)
        row.add(Document.COLUMN_MIME_TYPE, TestTrack.MIME_TYPE)
        row.add(Document.COLUMN_DISPLAY_NAME, TestTrack.DISPLAY_NAME)
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified)
        row.add(Document.COLUMN_SIZE, audioSize)
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)

        row.add(MediaStore.MediaColumns.TITLE, TestTrack.TITLE)
        row.add(MediaStore.Audio.AudioColumns.ARTIST, TestTrack.ARTIST)
        row.add(MediaStore.Audio.AudioColumns.ALBUM, TestTrack.ALBUM)
        row.add(MediaStore.Audio.AudioColumns.TRACK, TestTrack.TRACK_NUMBER)
        row.add(MediaStore.Audio.AudioColumns.DURATION, TestTrack.DURATION_MS)
        row.add(TrackProviderConsts.COLUMN_GENRE, TestTrack.GENRE)
    }

    /** Folder row for one imported playlist ("我的歌单" children). */
    fun fillPlaylistRow(
        row: MatrixCursor.RowBuilder,
        documentId: String,
        displayName: String,
        lastModified: Long,
    ) {
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified)
        row.add(TrackProviderConsts.COLUMN_FLAGS, TrackProviderConsts.FLAG_NO_SUBDIRS)
    }

    /**
     * Cached NetEase song (phase 2 display, phase 3 remote playback).
     *
     * [documentId] is passed in because the same song can appear under 搜索结果 and inside
     * playlists with different document paths. COLUMN_URL=DYNAMIC_URL tells Poweramp this
     * track's URL is fetched at playback time via CALL_GET_URL (official mechanism). When
     * duration<=0 the DURATION column is omitted so Poweramp reads the real duration from the
     * stream (metadata must match the audio, task section 11).
     */
    fun fillSongRow(
        row: MatrixCursor.RowBuilder,
        documentId: String,
        song: SongEntity,
        servedSize: Long,
        lyrics: String? = null,
    ) {
        val displayName = buildString {
            append(song.title.ifEmpty { "song_${song.songId}" })
            append(".mp3")
        }
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_MIME_TYPE, TestTrack.MIME_TYPE)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_LAST_MODIFIED, song.updatedAt)
        row.add(Document.COLUMN_SIZE, servedSize)
        val hasLyrics = !lyrics.isNullOrBlank()
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
        if (hasLyrics) {
            row.add(TrackProviderConsts.COLUMN_FLAGS, TrackProviderConsts.FLAG_HAS_LYRICS)
        }
        row.add(TrackProviderConsts.COLUMN_URL, TrackProviderConsts.DYNAMIC_URL)

        row.add(MediaStore.MediaColumns.TITLE, song.title)
        row.add(MediaStore.Audio.AudioColumns.ARTIST, song.artist)
        row.add(MediaStore.Audio.AudioColumns.ALBUM, song.album)
        if (song.duration > 0L) {
            row.add(MediaStore.Audio.AudioColumns.DURATION, song.duration)
        }
        if (hasLyrics) {
            row.add(TrackProviderConsts.COLUMN_TRACK_LYRICS_SYNCED, lyrics)
            row.add(TrackProviderConsts.COLUMN_TRACK_LYRICS, lyrics)
        }
    }
}
