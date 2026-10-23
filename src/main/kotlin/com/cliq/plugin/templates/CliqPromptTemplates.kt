package com.cliq.plugin.templates

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "CliqPromptTemplates",
    storages = [Storage("cliq-prompt-templates.xml")],
)
@Service(Service.Level.APP)
class CliqPromptTemplates : PersistentStateComponent<CliqPromptTemplates.State> {

    class State {
        var templates: MutableList<PromptTemplate> = mutableListOf()
    }

    companion object {
        @JvmStatic
        fun getInstance(): CliqPromptTemplates =
            ApplicationManager.getApplication().getService(CliqPromptTemplates::class.java)

        fun defaultTemplates(): MutableList<PromptTemplate> = mutableListOf(
            PromptTemplate(
                title = "Explain Code",
                content = "Explain what the following code does, step by step:\n\n{{selection}}",
                description = "Get a plain-language walkthrough of the selected code.",
            ),
            PromptTemplate(
                title = "Refactor & Optimize",
                content = "Refactor the following code for readability and performance. Explain each change you make:\n\n{{selection}}",
                description = "Ask the agent to clean up and speed up the code.",
            ),
            PromptTemplate(
                title = "Write Unit Tests",
                content = "Write comprehensive unit tests for the following code, covering edge cases:\n\n{{selection}}",
                description = "Generate a test suite for the selected code.",
            ),
            PromptTemplate(
                title = "Find Bugs",
                content = "Review the following code for bugs, edge cases, and potential issues:\n\n{{selection}}",
                description = "Ask the agent to audit the code for defects.",
            ),
        )
    }

    private val lock = Any()

    @Volatile
    private var myState = State()

    fun templates(): List<PromptTemplate> = synchronized(lock) {
        myState.templates.map { it.copy() }
    }

    fun setTemplates(newTemplates: List<PromptTemplate>) {
        synchronized(lock) {
            myState.templates = newTemplates.map { it.copy() }.toMutableList()
        }
    }

    fun addTemplate(template: PromptTemplate) {
        setTemplates(templates() + template)
    }

    override fun getState(): State = synchronized(lock) {
        State().apply { templates = myState.templates.map { it.copy() }.toMutableList() }
    }

    override fun loadState(state: State) {
        synchronized(lock) {
            myState = state
            if (myState.templates.isEmpty()) {
                myState.templates = defaultTemplates()
            }
        }
    }

    override fun noStateLoaded() {
        synchronized(lock) {
            myState.templates = defaultTemplates()
        }
    }
}
