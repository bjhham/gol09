package org.jetbrains.audio

import gol09.composeapp.generated.resources.Res
import kotlinx.browser.document
import org.w3c.dom.HTMLAudioElement

/**
 * Kotlin/JS [MusicPlayer]: drives an [HTMLAudioElement] sourced from the
 * resource URI returned by [Res.getUri]. The browser handles streaming and
 * decoding, so there's no need to load the bytes ourselves.
 *
 * Note: most browsers gate audio playback on a user gesture. Because the
 * simulation is started from a button press, [play] is invoked from inside
 * the resulting click handler and so the browser allows the track to start.
 */
actual class MusicPlayer actual constructor(resourcePath: String) {
    private val audio: HTMLAudioElement =
        (document.createElement("audio") as HTMLAudioElement).apply {
            src = Res.getUri(resourcePath)
            loop = true
            // `preload = "auto"` hints the browser to start fetching the
            // file eagerly so the first play() doesn't have to wait for
            // network I/O.
            preload = "auto"
        }

    actual fun play() {
        // `play()` returns a Promise on real browsers; we don't need to
        // await it here — failures (e.g. autoplay policy violations) are
        // surfaced asynchronously and the simulation should keep running
        // even if the soundtrack can't start.
        audio.play()
    }

    actual fun stop() {
        audio.pause()
        audio.currentTime = 0.0
    }

    actual fun release() {
        audio.pause()
        audio.removeAttribute("src")
        audio.load()
    }
}
