package com.yczz.neteasepoweramp.database

import com.yczz.neteasepoweramp.model.Song

/** Single shared mapping from the domain model to the Room row (phase 4: reused by services). */
fun Song.toEntity(now: Long): SongEntity = SongEntity(
    songId = songId,
    title = title,
    artist = artist,
    album = album,
    duration = durationMs,
    coverUrl = coverUrl,
    updatedAt = now,
    source = source,
    artistId = artistId,
    albumId = albumId,
)

fun SongEntity.toSong(): Song = Song(
    songId = songId,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    durationMs = duration,
    coverUrl = coverUrl,
    source = source,
)
