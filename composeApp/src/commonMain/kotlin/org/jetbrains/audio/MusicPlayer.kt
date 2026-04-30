package org.jetbrains.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * Cross-platform handle for looping background music loaded from a Compose
 * Multiplatform resource path (e.g. `"files/audio/groove.wav"`).
 *
 * Each platform provides an `actual` implementation in its own source set:
 *
 * - JVM: `javax.sound.sampled.Clip`.
 * - Android: `android.media.MediaPlayer`.
 * - iOS: `AVAudioPlayer`.
 * - Web (js + wasmJs): the browser's `HTMLAudioElement`.
 *
 * Implementations should:
 *
 * - Load the audio bytes lazily/asynchronously from the supplied resource
 *   path the first time [play] is invoked, and cache the decoded form so
 *   subsequent toggles between play and stop don't re-decode the file.
 * - Loop the track for as long as it's playing — the simulation can run for
 *   an unbounded amount of time, so the music must continue seamlessly.
 * - Treat repeated [play] / [stop] calls as idempotent.
 * - Free any native or browser resources from [release], which is invoked
 *   when the owning composable leaves the composition.
 */
expect class MusicPlayer(resourcePath: String) {
    /** Start (or resume) looping playback. No-op if already playing. */
    fun play()

    /** Stop playback and rewind to the start. No-op if not currently playing. */
    fun stop()

    /** Release any underlying audio resources. The instance must not be reused afterwards. */
    fun release()
}

/**
 * Remember a [MusicPlayer] for [resourcePath] across recompositions and
 * release it automatically when the composable leaves the composition.
 */
@Composable
fun rememberMusicPlayer(resourcePath: String): MusicPlayer {
    val player = remember(resourcePath) { MusicPlayer(resourcePath) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}
