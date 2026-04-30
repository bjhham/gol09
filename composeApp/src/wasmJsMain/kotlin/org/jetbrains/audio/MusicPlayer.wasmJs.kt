package org.jetbrains.audio

import gol09.composeapp.generated.resources.Res

/**
 * Kotlin/Wasm-JS [MusicPlayer]: drives a browser `HTMLAudioElement` sourced
 * from the resource URI returned by [Res.getUri]. The browser handles
 * streaming and decoding, so there's no need to load the bytes ourselves.
 *
 * Implemented via `js("...")` calls because the Wasm-JS DOM bindings differ
 * between Kotlin/Wasm releases; reaching into JavaScript directly keeps the
 * implementation small and version-tolerant.
 *
 * Note: most browsers gate audio playback on a user gesture. Because the
 * simulation is started from a button press, [play] is invoked from inside
 * the resulting click handler and so the browser allows the track to start.
 */
actual class MusicPlayer actual constructor(resourcePath: String) {
    private val audio: JsAny = createAudio(Res.getUri(resourcePath))

    actual fun play() {
        // `play()` returns a Promise on real browsers; we don't need to
        // await it here — failures (e.g. autoplay policy violations) are
        // surfaced asynchronously and the simulation should keep running
        // even if the soundtrack can't start.
        playAudio(audio)
    }

    actual fun stop() {
        stopAudio(audio)
    }

    actual fun release() {
        releaseAudio(audio)
    }
}

private fun createAudio(src: String): JsAny =
    js("(function(s){var a=new Audio(s);a.loop=true;a.preload='auto';return a;})(src)")

private fun playAudio(audio: JsAny) {
    js("audio.play()")
}

private fun stopAudio(audio: JsAny) {
    js("(function(a){a.pause();a.currentTime=0;})(audio)")
}

private fun releaseAudio(audio: JsAny) {
    js("(function(a){a.pause();a.removeAttribute('src');a.load();})(audio)")
}
