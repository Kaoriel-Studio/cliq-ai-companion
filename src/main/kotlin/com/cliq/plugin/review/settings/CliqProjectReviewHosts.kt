package com.cliq.plugin.review.settings

import com.cliq.plugin.review.connect.HostUrls
import com.cliq.plugin.review.provider.ProviderKind
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(name = "CliqReviewProjectHosts", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CliqProjectReviewHosts : PersistentStateComponent<CliqProjectReviewHosts.State> {

    class State {
        var hosts: MutableList<CliqReviewSettings.HostEntry> = mutableListOf()
    }

    private val lock = Any()

    @Volatile
    private var myState = State()

    fun hosts(): List<ReviewHost> = synchronized(lock) {
        myState.hosts.map { ReviewHost(it.url, ProviderKind.parse(it.kind), it.tokenStored) }
    }

    fun setHosts(hosts: List<ReviewHost>) {
        synchronized(lock) {
            myState = State().apply { this.hosts = sanitize(hosts.map { toEntry(it) }) }
        }
    }

    override fun getState(): State = synchronized(lock) {
        State().apply { hosts = myState.hosts.map { it.copy() }.toMutableList() }
    }

    override fun loadState(state: State) {
        synchronized(lock) { myState = State().apply { hosts = sanitize(state.hosts) } }
    }

    private fun toEntry(host: ReviewHost) = CliqReviewSettings.HostEntry(host.url, host.kind.name, host.tokenStored)

    private fun sanitize(entries: List<CliqReviewSettings.HostEntry>): MutableList<CliqReviewSettings.HostEntry> =
        entries.mapNotNull { entry ->
            HostUrls.normalize(entry.url)?.let {
                CliqReviewSettings.HostEntry(it, ProviderKind.parse(entry.kind).name, entry.tokenStored)
            }
        }.distinctBy { it.url }.toMutableList()
}
