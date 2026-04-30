package org.jetbrains.audio

import gol09.composeapp.generated.resources.Res
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * iOS [MusicPlayer]: loads the resource bytes via [Res.readBytes] into an
 * [NSData], then drives an [AVAudioPlayer] with `numberOfLoops = -1` so the
 * track loops for as long as [play] is in effect.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class MusicPlayer actual constructor(private val resourcePath: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private var player: AVAudioPlayer? = null
    private var loadJob: Job? = null
    private var shouldPlay = false

    actual fun play() {
        if (shouldPlay) return
        shouldPlay = true
        loadJob = scope.launch {
            mutex.withLock {
                if (!shouldPlay) return@withLock
                val active = player ?: createPlayer().also { player = it }
                if (!active.playing) active.play()
            }
        }
    }

    actual fun stop() {
        if (!shouldPlay) return
        shouldPlay = false
        scope.launch {
            mutex.withLock {
                player?.let { p ->
                    if (p.playing) p.stop()
                    p.currentTime = 0.0
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
                    if (p.playing) p.stop()
                }
                player = null
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    private suspend fun createPlayer(): AVAudioPlayer {
        val bytes = Res.readBytes(resourcePath)
        val data = bytes.toNSData()
        val player = AVAudioPlayer(data = data, error = null)
        player.numberOfLoops = -1L
        player.prepareToPlay()
        return player
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
