package com.vanced.manager.core.mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MviFlow<State, Action, SideEffect> {

    fun bindView(
        view: MviRenderView<State, Action, SideEffect>,
        scope: CoroutineScope,
        actions: List<Action> = listOf()
    )

    fun bindSideEffects(
        view: MviRenderView<State, Action, SideEffect>,
        scope: CoroutineScope
    )
}

private class MviFlowImpl<State, Action, Modification, SideEffect>(
    initialState: State,
    private val reducer: Reducer<State, Modification>,
    private val handler: Handler<State, Action, Modification, SideEffect>,
    scope: CoroutineScope,
) : MviFlow<State, Action, SideEffect>,
    CoroutineScope by scope,
    Mutex by Mutex() {

    private val state = MutableStateFlow(initialState)
    private val sideEffect = MutableSharedFlow<SideEffect>()

    override fun bindSideEffects(
        view: MviRenderView<State, Action, SideEffect>,
        scope: CoroutineScope
    ): Unit = with(scope) {
        launch {
            sideEffect.collect { view.sideEffects(it) }
        }
    }

    override fun bindView(
        view: MviRenderView<State, Action, SideEffect>,
        scope: CoroutineScope,
        actions: List<Action>
    ): Unit = with(scope) {
        emitStateForRender(view)
        handleActions(view, actions)
    }

    private fun CoroutineScope.emitStateForRender(
        view: MviRenderView<State, Action, SideEffect>
    ) = launch {
        state.collect {
            view.render(it)
        }
    }

    private fun CoroutineScope.handleActions(
        view: MviRenderView<State, Action, SideEffect>,
        actions: List<Action>
    ) = launch {
        view.actionsFlow()
            .onStart { emitAll(actions.asFlow()) }
            .proceed()
    }

    private suspend fun Flow<Action>.proceed() =
        collect { action ->
            handler.invoke(
                MutableSharedFlow<Modification>().setState(),
                state.value, action, sideEffect
            )
        }

    private fun MutableSharedFlow<Modification>.setState(): MutableSharedFlow<Modification> {
        onEach { modification ->
            withLock {
                state.value = reducer.invoke(state.value, modification)
            }
        }.launchIn(this@MviFlowImpl)
        return this
    }
}

fun <State, Action, SideEffect, Modification> MviFlow(
    initialState: State,
    reducer: Reducer<State, Modification>,
    handler: Handler<State, Action, Modification, SideEffect>,
    scope: CoroutineScope
): MviFlow<State, Action, SideEffect> = MviFlowImpl(
    initialState = initialState,
    reducer = reducer,
    handler = handler,
    scope = scope
)