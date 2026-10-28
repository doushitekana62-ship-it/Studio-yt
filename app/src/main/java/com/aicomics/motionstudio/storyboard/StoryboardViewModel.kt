package com.aicomics.motionstudio.storyboard

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

data class StudioUiState(
    val aspectRatio: String = "9:16",
    val scenes: List<Scene> = emptyList(),
    val message: String = ""
)

class StoryboardViewModel : ViewModel() {
    private val generator = LocalStoryboardDraftGenerator()
    private val _state = mutableStateOf(StudioUiState())
    val state: State<StudioUiState> = _state

    fun setAspectRatio(ratio: String) {
        if (ratio !in setOf("9:16", "16:9", "1:1")) return
        _state.value = _state.value.copy(aspectRatio = ratio)
    }

    fun createDraft(prompt: String) {
        runCatching {
            generator.generate(prompt, _state.value.aspectRatio)
        }.onSuccess { storyboard ->
            val validation = TimelineValidator.validate(storyboard)
            _state.value = _state.value.copy(
                scenes = if (validation.isValid) storyboard.scenes else emptyList(),
                message = if (validation.isValid) {
                    "Draft created with ${storyboard.scenes.size} scenes. This is a template, not model-generated output."
                } else {
                    validation.errors.joinToString(" ")
                }
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                message = error.message ?: "Could not create storyboard draft."
            )
        }
    }
}
