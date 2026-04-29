package org.jetbrains

import kscript.KFunctionParameter
import kscript.KIdentifier
import kscript.KInt
import kscript.KString
import kscript.KToken
import kscript.KTokenData
import kscript.KTokenType
import kscript.KVariableDeclaration
import kscript.ProcessState
import kscript.bridgeFunctionVoid
import kscript.bridgeGetter
import kscript.emptyProcessState

/**
 * Build a [ProcessState] populated with the bridge declarations exposed
 * to the user's script, each backed by an action on [vm].
 *
 * Adding a new bridge declaration to the API is typically a one-line
 * change: add the corresponding action to [GameViewModel] and wire a
 * `bridgeFunctionVoid` / `bridgeGetter` for it below.
 *
 * The resulting state is ready to be passed to `KScriptRunner.execute`.
 */
fun buildInitialState(vm: GameViewModel): ProcessState {
    val state = emptyProcessState()
    state += bridgeFunctionVoid("move") { vm.move() }
    state += bridgeFunctionVoid("turnRight") { vm.turnRight() }
    state += bridgeFunctionVoid("turnLeft") { vm.turnLeft() }
    // `warp(name)` reads its `name` parameter out of the active
    // [ProcessState] (where the runner has bound the call's argument)
    // and forwards it to the view model. The view model owns the
    // level-switching logic and throws [CancellationException] to stop
    // the currently-executing script.
    state += bridgeFunctionVoid(
        "warp",
        parameters = listOf(KFunctionParameter("name", "String")),
    ) {
        val nameDecl = state[KIdentifier("name")] as KVariableDeclaration
        val nameValue = nameDecl.initializer?.let { runner.execute(it, state) }
        val name = (nameValue as? KString)?.value
            ?: error("warp(name): expected a String argument, got $nameValue")
        vm.warp(name)
    }
    // Bridge getters re-evaluate on every reference so the script
    // always sees the up-to-date golem position after `move()` calls.
    state += bridgeGetter("x", INT_TYPE) { intValue(vm.golemX) }
    state += bridgeGetter("y", INT_TYPE) { intValue(vm.golemY) }

    return state
}

private val INT_TYPE = KIdentifier("Int")

private fun intValue(value: Int): KInt =
    KInt(KToken(KTokenData.Text(KTokenType.INTEGER_LITERAL, value.toString())), value)
