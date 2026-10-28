package com.aicomics.motionstudio.storyboard

data class Storyboard(
    val title: String,
    val prompt: String,
    val totalDurationSeconds: Int = 30,
    val aspectRatio: String = "9:16",
    val scenes: List<Scene>
)

data class Scene(
    val id: String,
    val title: String,
    val startSeconds: Int,
    val endSeconds: Int,
    val visualDescription: String,
    val cameraMotion: String,
    val dialogue: String,
    val soundEffects: List<String> = emptyList()
) {
    val durationSeconds: Int get() = endSeconds - startSeconds
}

data class TimelineValidation(
    val isValid: Boolean,
    val errors: List<String>
)

object TimelineValidator {
    fun validate(storyboard: Storyboard): TimelineValidation {
        val errors = mutableListOf<String>()
        if (storyboard.totalDurationSeconds <= 0) {
            errors += "Total duration must be positive."
        }
        if (storyboard.scenes.isEmpty()) {
            errors += "Storyboard must contain at least one scene."
        }
        if (storyboard.scenes.firstOrNull()?.startSeconds != 0) {
            errors += "The first scene must start at 0 seconds."
        }

        storyboard.scenes.forEachIndexed { index, scene ->
            if (scene.startSeconds < 0 || scene.endSeconds <= scene.startSeconds) {
                errors += "Scene ${scene.id} has an invalid time range."
            }
            if (index > 0) {
                val previous = storyboard.scenes[index - 1]
                if (scene.startSeconds != previous.endSeconds) {
                    errors += "Gap or overlap between ${previous.id} and ${scene.id}."
                }
            }
        }

        if (storyboard.scenes.lastOrNull()?.endSeconds != storyboard.totalDurationSeconds) {
            errors += "Scene timeline must end at ${storyboard.totalDurationSeconds} seconds."
        }
        if (storyboard.aspectRatio !in setOf("9:16", "16:9", "1:1")) {
            errors += "Unsupported aspect ratio: ${storyboard.aspectRatio}."
        }
        return TimelineValidation(errors.isEmpty(), errors)
    }
}
