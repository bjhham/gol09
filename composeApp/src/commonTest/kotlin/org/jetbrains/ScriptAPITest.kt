package org.jetbrains

import kotlinx.coroutines.test.runTest
import kscript.KScriptParser
import kscript.KScriptRunner
import kscript.parseExpression
import org.jetbrains.game.Cheese
import org.jetbrains.game.Direction
import org.jetbrains.game.GameGrid
import org.jetbrains.game.Golem
import org.jetbrains.game.Point
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the script bridge built by [buildInitialState] exposes
 * each [GameToken][org.jetbrains.game.GameToken] on the grid as a
 * variable named after [GameToken.name][org.jetbrains.game.GameToken.name],
 * carrying its grid position so user scripts can compare token positions.
 */
class ScriptAPITest {
    private val parser = KScriptParser()
    private val runner = KScriptRunner()

    @Test
    fun token_variables_expose_grid_position() = runTest {
        val vm = viewModelFor(
            GameGrid(
                tokens = listOf(
                    Golem(Point(2, 3), Direction.SOUTH),
                    Cheese(Point(7, 5)),
                ),
            )
        )
        val state = buildInitialState(vm)

        assertEquals("2", runner.execute(parser.parseExpression("gol.x"), state).asString())
        assertEquals("3", runner.execute(parser.parseExpression("gol.y"), state).asString())
        assertEquals("7", runner.execute(parser.parseExpression("goal.x"), state).asString())
        assertEquals("5", runner.execute(parser.parseExpression("goal.y"), state).asString())
    }

    @Test
    fun token_positions_can_be_compared() = runTest {
        val vm = viewModelFor(
            GameGrid(
                tokens = listOf(
                    Golem(Point(4, 4), Direction.SOUTH),
                    Cheese(Point(4, 7)),
                ),
            )
        )
        val state = buildInitialState(vm)

        assertEquals(
            "true",
            runner.execute(parser.parseExpression("gol.x == goal.x"), state).asString(),
        )
        assertEquals(
            "false",
            runner.execute(parser.parseExpression("gol.y == goal.y"), state).asString(),
        )
    }

    @Test
    fun token_variables_track_grid_changes() = runTest {
        val initial = GameGrid(
            tokens = listOf(
                Golem(Point(0, 0), Direction.SOUTH),
                Cheese(Point(0, 5)),
            ),
        )
        val vm = viewModelFor(initial)
        val state = buildInitialState(vm)

        assertEquals("0", runner.execute(parser.parseExpression("gol.y"), state).asString())

        // Advance the golem one cell south; the bridged getter should
        // pick up the new position on its next reference.
        vm.setGrid(vm.gameGrid!!.moveGolem())

        assertEquals("1", runner.execute(parser.parseExpression("gol.y"), state).asString())
    }

    @Test
    fun completionsFor_includes_bridge_functions_with_parens() {
        val vm = viewModelFor(
            GameGrid(
                tokens = listOf(
                    Golem(Point(0, 0), Direction.SOUTH),
                    Cheese(Point(0, 5)),
                ),
            )
        )
        val state = buildInitialState(vm)
        val labels = completionsFor(state).map { it.label }

        // Bridge functions should be presented as callable, with `()`.
        assertEquals(true, "move()" in labels, "move() missing from $labels")
        assertEquals(true, "turnLeft()" in labels)
        assertEquals(true, "turnRight()" in labels)
        assertEquals(true, "warp()" in labels)
        // Bridge getters and token variables should be bare names.
        assertEquals(true, "x" in labels)
        assertEquals(true, "facing" in labels)
        assertEquals(true, "gol" in labels)
        assertEquals(true, "goal" in labels)
        // A handful of Kotlin keywords should be included.
        assertEquals(true, "while" in labels)
        assertEquals(true, "if" in labels)
        assertEquals(true, "val" in labels)
    }

    @Test
    fun completionsFor_returns_a_sorted_unique_list() {
        val vm = viewModelFor(
            GameGrid(
                tokens = listOf(Golem(Point(0, 0), Direction.SOUTH)),
            )
        )
        val labels = completionsFor(buildInitialState(vm)).map { it.label }
        assertEquals(labels.sortedBy { it.lowercase() }, labels)
        assertEquals(labels.toSet().size, labels.size)
    }

    private fun viewModelFor(initial: GameGrid): GameViewModel {
        val vm = GameViewModel(
            levelMapPaths = emptyList(),
            tickMillis = 0L,
        )
        vm.setGrid(initial)
        return vm
    }
}
