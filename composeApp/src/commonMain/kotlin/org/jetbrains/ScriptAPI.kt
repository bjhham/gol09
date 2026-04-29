package org.jetbrains

import kscript.KClassDeclaration
import kscript.KClassInstance
import kscript.KClassKind
import kscript.KFunctionParameter
import kscript.KIdentifier
import kscript.KInt
import kscript.KString
import kscript.KToken
import kscript.KTokenData
import kscript.KTokenType
import kscript.KValOrVar
import kscript.KValue
import kscript.KVariableDeclaration
import kscript.ProcessState
import kscript.bridgeFunctionVoid
import kscript.bridgeGetter
import kscript.emptyProcessState
import org.jetbrains.game.GameToken

/**
 * Build a [ProcessState] populated with the bridge declarations exposed
 * to the user's script, each backed by an action on [vm].
 *
 * Adding a new bridge declaration to the API is typically a one-line
 * change: add the corresponding action to [GameViewModel] and wire a
 * `bridgeFunctionVoid` / `bridgeGetter` for it below.
 *
 * [loopLimit] is forwarded to [ProcessState.loopLimit] so the caller
 * can bound pathological scripts (e.g. an empty program whose outer
 * play loop would otherwise spin forever, or a `while (true) {}` body
 * that never yields). `null` (the default) leaves the runner
 * unbounded, matching the existing test suite.
 *
 * The resulting state is ready to be passed to `KScriptRunner.execute`.
 */
fun buildInitialState(
    vm: GameViewModel,
    loopLimit: Int? = null,
): ProcessState {
    val state = emptyProcessState(loopLimit = loopLimit)
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

    // Expose every renderable token on the grid as a script variable
    // named after [GameToken.name], whose value is a [Position] data
    // class instance carrying the token's grid coordinates. This lets
    // user scripts compare positions, e.g. `gol.x == cheese.x`. Like
    // the `x`/`y` getters above, each reference re-evaluates against
    // the current grid so the values stay fresh as the golem moves.
    for (token in vm.tokens) {
        val variableName = token.name
        state += bridgeGetter(variableName, POSITION_TYPE) {
            positionValue(vm, variableName)
        }
    }

    return state
}

private val INT_TYPE = KIdentifier("Int")
private val POSITION_TYPE = KIdentifier("Position")
private val X_NAME = KIdentifier("x")
private val Y_NAME = KIdentifier("y")

/**
 * The synthetic `data class Position(val x: Int, val y: Int)` that
 * backs the per-token bridge getters. Member access on a
 * [KClassInstance] reads from its `properties` map directly, so this
 * declaration only needs the [name][KClassDeclaration.name] used when
 * rendering the value (`Position(x = ..., y = ...)`).
 */
private val POSITION_DECL = KClassDeclaration(
    children = emptyList(),
    annotations = emptyList(),
    visibility = null,
    name = POSITION_TYPE,
    kind = KClassKind.DATA,
    typeParameters = emptyList(),
    superTypes = emptyList(),
    declarations = listOf(
        KVariableDeclaration(
            children = emptyList(),
            annotations = emptyList(),
            mutability = KValOrVar.VAL,
            name = X_NAME,
            initializer = null,
        ),
        KVariableDeclaration(
            children = emptyList(),
            annotations = emptyList(),
            mutability = KValOrVar.VAL,
            name = Y_NAME,
            initializer = null,
        ),
    ),
)

private fun intValue(value: Int): KInt =
    KInt(KToken(KTokenData.Text(KTokenType.INTEGER_LITERAL, value.toString())), value)

/**
 * Build a [Position] [KClassInstance] for the (current) token named
 * [tokenName] on [vm]'s grid. Re-evaluated on every reference so the
 * value reflects the live grid; if the token has been removed (e.g.
 * after a level switch) the previous coordinates are reported.
 */
private fun positionValue(vm: GameViewModel, tokenName: String): KClassInstance {
    val token: GameToken? = vm.tokens.firstOrNull { it.name == tokenName }
    val position = token?.position
    val properties = mutableMapOf<KIdentifier, KValue>(
        X_NAME to intValue(position?.x ?: 0),
        Y_NAME to intValue(position?.y ?: 0),
    )
    return KClassInstance(POSITION_DECL, properties)
}
