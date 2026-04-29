package org.jetbrains

import androidx.compose.runtime.withFrameMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.game.Cheese
import org.jetbrains.game.GameGrid
import org.jetbrains.game.GameToken
import org.jetbrains.game.Golem
import org.jetbrains.game.Point

/**
 * View model that owns the game's mutable state and exposes the
 * semantic actions that the script bridge calls into.
 *
 * The view model is intentionally free of Compose state types: the host
 * (the [App] composable) supplies simple getter/setter lambdas wrapping
 * its `MutableState` properties, which keeps the model testable without
 * a Compose runtime.
 *
 * Each action mirrors a single bridge declaration: [move], [turnRight],
 * [turnLeft], [warp], plus the [golemX]/[golemY] getters. New bridge
 * actions should generally be added here first, then wired through
 * [buildInitialState] in `ScriptBridge.kt`.
 *
 * @property getGameGrid returns the current [GameGrid], or `null` if no
 *   level has been loaded yet.
 * @property setGameGrid replaces the current [GameGrid].
 * @property isRunning returns whether the simulation is currently running.
 * @property setRunning toggles the simulation running flag.
 * @property getLevelIndex returns the index of the active level inside
 *   [levelMapPaths].
 * @property setLevelIndex switches the active level by index.
 * @property setWalkAnimation publishes the in-flight walk animation (or
 *   clears it when passed `null`).
 * @property levelMapPaths the ordered list of level resource paths used to
 *   resolve [warp] calls.
 * @property tickMillis duration of a single [move] step, in milliseconds.
 */
class GameViewModel(
    private val getGameGrid: () -> GameGrid?,
    private val setGameGrid: (GameGrid) -> Unit,
    private val isRunning: () -> Boolean,
    private val setRunning: (Boolean) -> Unit,
    private val getLevelIndex: () -> Int,
    private val setLevelIndex: (Int) -> Unit,
    private val setWalkAnimation: (WalkAnimation?) -> Unit,
    private val levelMapPaths: List<String>,
    private val tickMillis: Long,
) {
    /**
     * Get the current state of the golem
     */
    val golem: Golem? get() = getGameGrid()?.golem

    /**
     * The renderable tokens currently on the grid, or an empty list if no
     * level is loaded. Used by the script bridge to expose each token as a
     * named variable whose value carries its grid position.
     */
    val tokens: List<GameToken> get() = getGameGrid()?.tokens ?: emptyList()

    fun canMove(): Boolean = getGameGrid()?.canMoveGolem() ?: false

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
        if (!isRunning()) return
        val grid = getGameGrid() ?: return
        // If the golem is already standing on the cheese, the level is
        // complete and we must not take another step. The
        // `LaunchedEffect(levelComplete)` that flips `isRunning` to false
        // runs on recomposition, so by the time the script requests the
        // next instruction it can race ahead of that effect — without
        // this guard the golem walks one cell past the cheese and the
        // level-complete overlay flickers away.
        if (grid.tokens.any { it is Cheese && it.position == grid.golem.position }) {
            setRunning(false)
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
            if (getGameGrid() === grid) {
                setGameGrid(target)
            }
            setWalkAnimation(null)
        }
    }

    /** Rotates the golem 90° clockwise in place. */
    fun turnRight() {
        getGameGrid()?.turnGolemRight()?.let(setGameGrid)
    }

    /** Rotates the golem 90° counter-clockwise in place. */
    fun turnLeft() {
        getGameGrid()?.turnGolemLeft()?.let(setGameGrid)
    }

    /**
     * Jumps to a level by base file [name] (e.g. `warp("level1")` loads
     * `files/maps/level1`). Useful for testing as more stages are added:
     * a script can position the golem on any level without having to
     * advance through the preceding ones via the UI. Updating the level
     * index triggers the keyed `LaunchedEffect(levelIndex)` in [App] to
     * reload the map and reset per-level state (including `isRunning`);
     * we additionally throw [CancellationException] so the
     * currently-executing script stops immediately rather than running
     * one more instruction against the freshly-loaded grid.
     */
    fun warp(name: String): Nothing {
        val targetIndex = levelMapPaths.indexOfFirst { it.substringAfterLast('/') == name }
        if (targetIndex < 0) {
            error("warp(name): unknown level '$name'")
        }
        setRunning(false)
        if (getLevelIndex() != targetIndex) {
            setLevelIndex(targetIndex)
        }
        throw CancellationException("warp(\"$name\")")
    }

    /**
     * Slide the golem from [from] to [to] over [tickMillis], updating
     * the walk-animation state on every frame. Returns once the
     * animation has reached `progress = 1f`.
     */
    private suspend fun animateWalk(from: Point, to: Point) {
        setWalkAnimation(WalkAnimation(from = from, to = to, progress = 0f))
        val startMillis = withFrameMillis { it }
        while (true) {
            val nowMillis = withFrameMillis { it }
            val elapsed = nowMillis - startMillis
            val progress = (elapsed.toFloat() / tickMillis).coerceIn(0f, 1f)
            setWalkAnimation(WalkAnimation(from = from, to = to, progress = progress))
            if (progress >= 1f) break
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
