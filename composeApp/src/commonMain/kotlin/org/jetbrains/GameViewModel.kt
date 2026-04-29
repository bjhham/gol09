package org.jetbrains

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.ViewModel
import gol09.composeapp.generated.resources.Res
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.game.Cheese
import org.jetbrains.game.GameGrid
import org.jetbrains.game.GameToken
import org.jetbrains.game.Golem
import org.jetbrains.game.MapParser
import org.jetbrains.game.Point

/**
 * View model that owns the game's mutable state and exposes the
 * semantic actions that the script bridge calls into.
 *
 * Built on top of `androidx.lifecycle.ViewModel` from the Compose
 * Multiplatform lifecycle library so that it is obtained from a
 * composable via `viewModel { GameViewModel(...) }`, survives
 * recomposition, and is automatically cleaned up by the surrounding
 * `ViewModelStoreOwner`.
 *
 * The view model owns its own Compose state — [gameGrid], [isRunning],
 * [levelIndex], [walkAnimation] — exposed as plain Kotlin properties
 * backed by [mutableStateOf]. Reading any of these from a composable
 * subscribes the composable to changes; writing through the action
 * methods (or the small number of UI-facing setters below) triggers
 * recomposition just like any other Compose state.
 *
 * Each script-facing action mirrors a single bridge declaration:
 * [move], [turnRight], [turnLeft], [warp], plus the [golem]/[tokens]
 * accessors. New bridge actions should generally be added here first,
 * then wired through [buildInitialState] in `ScriptAPI.kt`.
 *
 * @property levelMapPaths the ordered list of level resource paths used
 *   to resolve [warp] calls and to advance to the next level.
 * @property tickMillis duration of a single [move] step, in milliseconds.
 */
class GameViewModel(
    val levelMapPaths: List<String>,
    private val tickMillis: Long,
) : ViewModel() {

    /** The freshly-parsed initial grid for the current level, or `null` while loading. */
    var initialGrid: GameGrid? by mutableStateOf(null)
        private set

    /** The current parsed grid, or `null` while loading. */
    var gameGrid: GameGrid? by mutableStateOf(null)
        private set

    /** Index of the currently-loaded level inside [levelMapPaths]. */
    var levelIndex: Int by mutableStateOf(0)
        private set

    /** Whether the simulation is currently running. */
    var isRunning: Boolean by mutableStateOf(false)

    /** Whether the player has dismissed the level-complete overlay for the current level. */
    var levelCompleteDismissed: Boolean by mutableStateOf(false)
        private set

    /**
     * In-flight walk animation, or `null` when the golem is at rest.
     * See [WalkAnimation] for the semantics.
     */
    var walkAnimation: WalkAnimation? by mutableStateOf(null)
        private set

    /**
     * The level is complete as soon as the golem occupies a cell that
     * contains a [Cheese]. Backed by [derivedStateOf] so observers only
     * re-trigger when the underlying grid changes.
     */
    val levelComplete: Boolean by derivedStateOf {
        val grid = gameGrid ?: return@derivedStateOf false
        val golemPos = grid.golem.position
        grid.tokens.any { it is Cheese && it.position == golemPos }
    }

    /** Get the current state of the golem, or `null` if no level is loaded. */
    val golem: Golem? get() = gameGrid?.golem

    /**
     * The renderable tokens currently on the grid, or an empty list if no
     * level is loaded. Used by the script bridge to expose each token as a
     * named variable whose value carries its grid position.
     */
    val tokens: List<GameToken> get() = gameGrid?.tokens ?: emptyList()

    fun canMove(): Boolean = gameGrid?.canMoveGolem() ?: false

    /**
     * Reset the current level back to its freshly-loaded [initialGrid]
     * and stop the simulation. Used by the UI refresh button and the
     * level-complete overlay's "Play Again" button.
     */
    fun resetLevel() {
        isRunning = false
        gameGrid = initialGrid
        walkAnimation = null
        levelCompleteDismissed = false
    }

    /**
     * Advance to the next level in [levelMapPaths] (if any). Stops the
     * simulation and clears the in-flight walk animation immediately so
     * the golem doesn't keep moving while the next level loads. The
     * keyed call to [loadLevel] from the host composable then reloads
     * the map and resets the per-level state.
     */
    fun goToNextLevel() {
        if (levelIndex < levelMapPaths.lastIndex) {
            isRunning = false
            walkAnimation = null
            levelIndex += 1
        }
    }

    /** Test/utility hook: install [grid] as both the initial and current grid. */
    fun setGrid(grid: GameGrid) {
        initialGrid = grid
        gameGrid = grid
        isRunning = false
        walkAnimation = null
        levelCompleteDismissed = false
    }

    /**
     * Load (or reload) the level at [levelMapPaths]`[`[levelIndex]`]`.
     * Resets the per-level UI state so the player starts each level
     * with the golem at its `START` cell, the simulation paused, and
     * the level-complete overlay un-dismissed.
     */
    suspend fun loadLevel() {
        val bytes = Res.readBytes(levelMapPaths[levelIndex])
        val parsed = MapParser().parse(bytes.decodeToString())
        initialGrid = parsed
        gameGrid = parsed
        isRunning = false
        walkAnimation = null
        levelCompleteDismissed = false
    }

    /**
     * Advances the golem one cell in its facing direction over
     * [tickMillis], animating the figure as it slides between cells. If
     * the move would take the golem off the grid, the model is left
     * untouched but the simulation still paces by [tickMillis] so the
     * script doesn't spin.
     */
    suspend fun move() {
        // Bail out before doing any work if the simulation has been
        // paused/reset since the last instruction. The move's slide runs
        // inside a `withContext(NonCancellable)` block below, which
        // intentionally swallows parent cancellation so the golem doesn't
        // stop mid-cell; without this guard the script would happily
        // start *another* move after the previous one completed even
        // though the user had asked the simulation to stop.
        currentCoroutineContext().ensureActive()
        if (!isRunning) return
        val grid = gameGrid ?: return
        // If the golem is already standing on the cheese, the level is
        // complete and we must not take another step. The
        // `LaunchedEffect(levelComplete)` that flips `isRunning` to false
        // runs on recomposition, so by the time the script requests the
        // next instruction it can race ahead of that effect — without
        // this guard the golem walks one cell past the cheese and the
        // level-complete overlay flickers away.
        if (grid.tokens.any { it is Cheese && it.position == grid.golem.position }) {
            isRunning = false
            return
        }
        val target = grid.moveGolem()
        val from = grid.golem.position
        val to = target.golem.position
        // Run the step inside a NonCancellable context so that pressing
        // pause mid-step lets the current move animation complete before
        // the simulation coroutine is cancelled. Cancellation will be
        // observed at the next suspension point after this block (i.e.
        // when the script runner asks for its next instruction). Refresh
        // also flips `isRunning` to false, but additionally replaces the
        // grid; we detect that below and skip committing the move so
        // refresh isn't overwritten by an in-flight animation.
        withContext(NonCancellable) {
            if (from == to) {
                // The move would have taken the golem off the grid, so
                // the model didn't change. Still pace the simulation so
                // the script doesn't spin, but skip the slide animation.
                delay(tickMillis)
                return@withContext
            }
            animateWalk(from, to)
            // Commit the move to the model and clear the animation so
            // the golem renders in its idle pose at its new cell. If the
            // grid has been replaced underneath us (e.g. by the refresh
            // button), honour that change instead of stomping on it.
            if (gameGrid === grid) {
                gameGrid = target
            }
            walkAnimation = null
        }
    }

    /** Rotates the golem 90° clockwise in place. */
    fun turnRight() {
        gameGrid?.turnGolemRight()?.let { gameGrid = it }
    }

    /** Rotates the golem 90° counter-clockwise in place. */
    fun turnLeft() {
        gameGrid?.turnGolemLeft()?.let { gameGrid = it }
    }

    /**
     * Jumps to a level by base file [name] (e.g. `warp("level1")` loads
     * `files/maps/level1`). Useful for testing as more stages are added:
     * a script can position the golem on any level without having to
     * advance through the preceding ones via the UI. Updating the level
     * index triggers the keyed [loadLevel] call in [App] to reload the
     * map and reset per-level state (including [isRunning]); we
     * additionally throw [CancellationException] so the
     * currently-executing script stops immediately rather than running
     * one more instruction against the freshly-loaded grid.
     */
    fun warp(name: String): Nothing {
        val targetIndex = levelMapPaths.indexOfFirst { it.substringAfterLast('/') == name }
        if (targetIndex < 0) {
            error("warp(name): unknown level '$name'")
        }
        isRunning = false
        if (levelIndex != targetIndex) {
            levelIndex = targetIndex
        }
        throw CancellationException("warp(\"$name\")")
    }

    /**
     * Slide the golem from [from] to [to] over [tickMillis], updating
     * the walk-animation state on every frame. Returns once the
     * animation has reached `progress = 1f`.
     *
     * The frame-driving primitives (`withFrameMillis`) read the
     * `MonotonicFrameClock` from the active coroutine context, which —
     * when this is invoked from a `LaunchedEffect` — is supplied by
     * the surrounding composition. If the user pauses or resets the
     * simulation while a step is in progress, the `LaunchedEffect`
     * driving the script is cancelled and the composition's frame
     * clock raises [androidx.compose.runtime.LeftCompositionCancellationException]
     * out of the next `withFrameMillis` call. We are deliberately
     * inside a `NonCancellable` block at that point (so the animation
     * runs to completion in the normal case), so we catch that
     * particular `CancellationException` here and treat it as "skip
     * to the end of the animation": the golem snaps to its target
     * cell and the caller commits the move.
     */
    private suspend fun animateWalk(from: Point, to: Point) {
        walkAnimation = WalkAnimation(from = from, to = to, progress = 0f)
        try {
            val startMillis = withFrameMillis { it }
            while (true) {
                val nowMillis = withFrameMillis { it }
                val elapsed = nowMillis - startMillis
                val progress = (elapsed.toFloat() / tickMillis).coerceIn(0f, 1f)
                walkAnimation = WalkAnimation(from = from, to = to, progress = progress)
                if (progress >= 1f) break
            }
        } catch (_: CancellationException) {
            // The composition's frame clock has been disposed (the
            // host `LaunchedEffect` was cancelled by pause/reset).
            // Finish the step instantly so the caller can commit the
            // move and clear the walk animation in its `finally`-like
            // tail; without this catch the exception would propagate
            // out of the `NonCancellable` block in `move()` and
            // surface as `LeftCompositionCancellationException` to
            // the user.
            walkAnimation = WalkAnimation(from = from, to = to, progress = 1f)
        }
    }
}

/**
 * Snapshot of an in-progress walk animation. While `move()` is animating,
 * the golem's logical position in the [GameGrid] is held at [from] until
 * [progress] reaches `1f`; the canvas renders its figure interpolated
 * between [from] and [to] in the meantime. Holding the model still until
 * the animation completes keeps the level-complete overlay (which keys on
 * the golem occupying a cheese cell) in sync with the visible motion.
 */
data class WalkAnimation(
    val from: Point,
    val to: Point,
    val progress: Float,
)
