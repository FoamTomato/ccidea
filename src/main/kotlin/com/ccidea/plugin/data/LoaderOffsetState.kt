package com.ccidea.plugin.data

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.XCollection

/** Persists per-file tail-read state across IDE restarts. */
@Service(Service.Level.APP)
@State(name = "CcideaLoaderOffsets", storages = [Storage("ccidea-offsets.xml")])
class LoaderOffsetState : PersistentStateComponent<LoaderOffsetState.State> {

    class FileOffset(
        @OptionTag var path: String = "",
        @OptionTag var offset: Long = 0,
        @OptionTag var sizeBytes: Long = 0,
        @OptionTag var lastModifiedMs: Long = 0
    )

    class State(
        @XCollection(propertyElementName = "offsets")
        var offsets: MutableList<FileOffset> = mutableListOf()
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    fun snapshot(): Map<String, FileOffset> = state.offsets.associateBy { it.path }

    fun replaceAll(map: Map<String, FileOffset>) {
        state.offsets = map.values.toMutableList()
    }

    fun clear() {
        state.offsets = mutableListOf()
    }
}
