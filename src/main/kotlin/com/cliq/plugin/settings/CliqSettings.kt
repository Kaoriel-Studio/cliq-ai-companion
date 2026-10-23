package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import java.util.EventListener

@State(
    name = "CliqSettings",
    storages = [Storage("cliq-settings.xml")],
)
@Service(Service.Level.APP)
class CliqSettings : PersistentStateComponent<CliqSettings.State> {

    class State {
        var agents: MutableList<CliAgentDefinition> = mutableListOf()
        var autoApplyChanges: Boolean = false
        var promptHistoryEnabled: Boolean = true
        var confirmClipboardPlaceholder: Boolean = true
        var commitMessagePromptTemplate: String = DEFAULT_COMMIT_MESSAGE_PROMPT_TEMPLATE
    }

    interface AgentsListener : EventListener {
        fun onAgentsChanged(agents: List<CliAgentDefinition>)
    }

    companion object {
        val TOPIC: Topic<AgentsListener> = Topic.create("Cliq Agents Changed", AgentsListener::class.java)

        const val COMMIT_DIFF_PLACEHOLDER = "{{diff}}"

        const val DEFAULT_COMMIT_MESSAGE_PROMPT_TEMPLATE =
            "Write a concise commit message for the following changes. " +
                "Use the imperative mood for the summary line (max ~72 characters), " +
                "followed by an optional short body explaining the why:\n\n" +
                "```diff\n" + COMMIT_DIFF_PLACEHOLDER + "\n```"

        @JvmStatic
        fun getInstance(): CliqSettings =
            ApplicationManager.getApplication().getService(CliqSettings::class.java)

        fun defaultAgents(): MutableList<CliAgentDefinition> = mutableListOf(
            CliAgentDefinition(
                id = CliAgentDefinition.CLAUDE_ID,
                displayName = "Claude",
                executablePath = "claude",
            ),
            CliAgentDefinition(
                id = CliAgentDefinition.GEMINI_ID,
                displayName = "Gemini",
                executablePath = "gemini",
            ),
            CliAgentDefinition(
                id = CliAgentDefinition.QWEN_ID,
                displayName = "Qwen",
                executablePath = "qwen",
            ),
        )
    }

    private val lock = Any()

    @Volatile
    private var myState = State()

    var autoApplyChanges: Boolean
        get() = myState.autoApplyChanges
        set(value) {
            synchronized(lock) { myState.autoApplyChanges = value }
        }

    var promptHistoryEnabled: Boolean
        get() = myState.promptHistoryEnabled
        set(value) {
            synchronized(lock) { myState.promptHistoryEnabled = value }
        }

    var confirmClipboardPlaceholder: Boolean
        get() = myState.confirmClipboardPlaceholder
        set(value) {
            synchronized(lock) { myState.confirmClipboardPlaceholder = value }
        }

    var commitMessagePromptTemplate: String
        get() = myState.commitMessagePromptTemplate
        set(value) {
            synchronized(lock) {
                myState.commitMessagePromptTemplate = value.ifBlank { DEFAULT_COMMIT_MESSAGE_PROMPT_TEMPLATE }
            }
        }

    fun agents(): List<CliAgentDefinition> = synchronized(lock) {
        myState.agents.map { it.deepCopy() }
    }

    fun setAgents(newAgents: List<CliAgentDefinition>) {
        synchronized(lock) {
            myState.agents = newAgents.map { it.deepCopy() }.toMutableList()
        }
        ApplicationManager.getApplication().messageBus
            .syncPublisher(TOPIC)
            .onAgentsChanged(agents())
    }

    fun replaceAgent(agent: CliAgentDefinition) {
        synchronized(lock) {
            val index = myState.agents.indexOfFirst { it.id == agent.id }
            if (index < 0) return
            myState.agents[index] = agent.deepCopy()
        }
    }

    fun resetToDefaults() {
        setAgents(defaultAgents())
    }

    override fun getState(): State = synchronized(lock) {
        State().apply {
            agents = myState.agents.map { it.deepCopy() }.toMutableList()
            autoApplyChanges = myState.autoApplyChanges
            promptHistoryEnabled = myState.promptHistoryEnabled
            confirmClipboardPlaceholder = myState.confirmClipboardPlaceholder
            commitMessagePromptTemplate = myState.commitMessagePromptTemplate
        }
    }

    override fun loadState(state: State) {
        synchronized(lock) {
            myState = state
            if (myState.agents.isEmpty()) {
                myState.agents = defaultAgents()
            }
        }
    }

    override fun noStateLoaded() {
        synchronized(lock) {
            myState.agents = defaultAgents()
        }
    }
}
