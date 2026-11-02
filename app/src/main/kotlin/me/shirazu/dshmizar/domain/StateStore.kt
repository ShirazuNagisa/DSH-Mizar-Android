package me.shirazu.dshmizar.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.shirazu.dshmizar.UiState

/**
 * The one channel a domain coordinator may read and write app state through.
 *
 * The app keeps a single [UiState] because every screen draws from it, and that is
 * not changing here. What a coordinator needs is not a slice of that state but the
 * promise that a write is atomic and visible to the UI at once; handing over this
 * instead of the whole view model keeps one domain from reaching for another's work
 * -- a coordinator that only ever touches its own fields cannot quietly cancel a
 * reconnect or post a notification.
 */
internal interface StateStore {

    val state: StateFlow<UiState>

    fun update(transform: (UiState) -> UiState)
}

/** The app's one [StateStore]: a thin face on the flow the screens already read. */
internal class FlowStateStore(private val flow: MutableStateFlow<UiState>) : StateStore {

    override val state: StateFlow<UiState> = flow.asStateFlow()

    override fun update(transform: (UiState) -> UiState) {
        flow.update(transform)
    }
}
