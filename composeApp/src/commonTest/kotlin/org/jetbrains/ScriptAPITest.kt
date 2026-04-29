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
        assertEquals("7", runner.execute(parser.parseExpression("cheese.x"), state).asString())
        assertEquals("5", runner.execute(parser.parseExpression("cheese.y"), state).asString())
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
            runner.execute(parser.parseExpression("gol.x == cheese.x"), state).asString(),
        )
        assertEquals(
            "false",
            runner.execute(parser.parseExpression("gol.y == cheese.y"), state).asString(),
        )
    }

    @Test
    fun token_variables_track_grid_changes() = runTest {
        var grid = GameGrid(
            tokens = listOf(
                Golem(Point(0, 0), Direction.SOUTH),
                Cheese(Point(0, 5)),
            ),
        )
        val vm = GameViewModel(
            getGameGrid = { grid },
            setGameGrid = { grid = it },
            isRunning = { false },
            setRunning = { },
            getLevelIndex = { 0 },
            setLevelIndex = { },
            setWalkAnimation = { },
            levelMapPaths = emptyList(),
            tickMillis = 0L,
        )
        val state = buildInitialState(vm)

        assertEquals("0", runner.execute(parser.parseExpression("gol.y"), state).asString())

        // Advance the golem one cell south; the bridged getter should
        // pick up the new position on its next reference.
        grid = grid.moveGolem()

        assertEquals("1", runner.execute(parser.parseExpression("gol.y"), state).asString())
    }

    private fun viewModelFor(initial: GameGrid): GameViewModel {
        var grid: GameGrid = initial
        return GameViewModel(
            getGameGrid = { grid },
            setGameGrid = { grid = it },
            isRunning = { false },
            setRunning = { },
            getLevelIndex = { 0 },
            setLevelIndex = { },
            setWalkAnimation = { },
            levelMapPaths = emptyList(),
            tickMillis = 0L,
        )
    }
}
