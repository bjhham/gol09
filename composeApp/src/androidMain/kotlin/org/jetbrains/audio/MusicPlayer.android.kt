package org.jetbrains.audio

import android.media.MediaDataSource
import android.media.MediaPlayer
import gol09.composeapp.generated.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android [MusicPlayer]: loads the resource bytes via [Res.readBytes] and
 * feeds them into a [MediaPlayer] through an in-memory [MediaDataSource], so
 * the implementation doesn't depend on a `Context` and can live entirely in
 * the platform-agnostic resource pipeline.
 */
actual class MusicPlayer actual constructor(private val resourcePath: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private var player: MediaPlayer? = null
    private var loadJob: Job? = null
    private var shouldPlay = false

    actual fun play() {
        if (shouldPlay) return
        shouldPlay = true
        loadJob = scope.launch {
            mutex.withLock {
                if (!shouldPlay) return@withLock
                val active = player ?: createPlayer().also { player = it }
                if (!active.isPlaying) active.start()
            }
        }
    }

    actual fun stop() {
        if (!shouldPlay) return
        shouldPlay = false
        scope.launch {
            mutex.withLock {
                player?.let { p ->
                    if (p.isPlaying) p.pause()
                    p.seekTo(0)
                }
            }
        }
    }

    actual fun release() {
        shouldPlay = false
        loadJob?.cancel()
        scope.launch {
            mutex.withLock {
                player?.let { p ->
                    runCatching { if (p.isPlaying) p.stop() }
                    p.release()
                }
                player = null
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    private suspend fun createPlayer(): MediaPlayer {
        val bytes = Res.readBytes(resourcePath)
        val player = MediaPlayer()
        player.isLooping = true
        player.setDataSource(ByteArrayMediaDataSource(bytes))
        player.prepare()
        return player
    }

    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val end = minOf(position + size, data.size.toLong()).toInt()
            val length = end - position.toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, length)
            return length
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() {
            // The byte array is owned by the enclosing player and reused on
            // subsequent prepare() calls; nothing to release here.
        }
    }
}
