package org.jetbrains.audio

import gol09.composeapp.generated.resources.Res
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JVM (Compose Desktop) [MusicPlayer]: loads the resource bytes via
 * [Res.readBytes] and feeds them into a [javax.sound.sampled.Clip], looping
 * for as long as [play] is in effect.
 */
actual class MusicPlayer actual constructor(private val resourcePath: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var clip: Clip? = null
    private var loadJob: Job? = null
    private var shouldPlay = false

    actual fun play() {
        if (shouldPlay) return
        shouldPlay = true
        loadJob = scope.launch {
            mutex.withLock {
                if (!shouldPlay) return@withLock
                val activeClip = clip ?: openClip().also { clip = it }
                if (!activeClip.isRunning) {
                    activeClip.framePosition = 0
                    activeClip.loop(Clip.LOOP_CONTINUOUSLY)
                }
            }
        }
    }

    actual fun stop() {
        if (!shouldPlay) return
        shouldPlay = false
        scope.launch {
            mutex.withLock {
                clip?.let {
                    if (it.isRunning) it.stop()
                    it.framePosition = 0
                }
            }
        }
    }

    actual fun release() {
        shouldPlay = false
        loadJob?.cancel()
        scope.launch {
            mutex.withLock {
                clip?.let {
                    if (it.isRunning) it.stop()
                    it.close()
                }
                clip = null
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    private suspend fun openClip(): Clip {
        val bytes = Res.readBytes(resourcePath)
        val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        val clip = AudioSystem.getClip()
        clip.open(stream)
        return clip
    }
}
