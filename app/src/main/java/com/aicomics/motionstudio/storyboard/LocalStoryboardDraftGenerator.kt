package com.aicomics.motionstudio.storyboard

/**
 * Deterministic offline template generator.
 *
 * This is intentionally not described as AI inference. Replace or augment it with
 * a validated on-device LLM adapter when a compatible model/runtime is integrated.
 */
class LocalStoryboardDraftGenerator {
    fun generate(prompt: String, aspectRatio: String = "9:16"): Storyboard {
        require(prompt.isNotBlank()) { "Prompt must not be blank." }
        require(aspectRatio in setOf("9:16", "16:9", "1:1")) { "Unsupported aspect ratio." }

        val beats = listOf(
            Triple("Opening", "Establish the setting and introduce the main character.", "Slow zoom in"),
            Triple("Threat", "Reveal the danger approaching the location.", "Pan right"),
            Triple("Decision", "Show the protagonist preparing to act.", "Slow push in"),
            Triple("Clash", "Present the central action with impact frames.", "Camera shake"),
            Triple("Resolution", "End on a clear emotional image and a dramatic hold.", "Slow zoom out")
        )
        val boundaries = listOf(0, 5, 11, 17, 24, 30)
        val scenes = beats.mapIndexed { index, beat ->
            Scene(
                id = "SC%02d".format(index + 1),
                title = beat.first,
                startSeconds = boundaries[index],
                endSeconds = boundaries[index + 1],
                visualDescription = "${beat.second} Story premise: ${prompt.trim()}",
                cameraMotion = beat.third,
                dialogue = when (index) {
                    0 -> "Narration: The story begins."
                    1 -> "Character: Something is coming."
                    2 -> "Character: I will not back down."
                    3 -> "Character: This ends now!"
                    else -> "Narration: The choice changes everything."
                },
                soundEffects = when (index) {
                    1 -> listOf("low rumble")
                    3 -> listOf("whoosh", "impact")
                    else -> emptyList()
                }
            )
        }
        return Storyboard(
            title = "Untitled Comic Story",
            prompt = prompt.trim(),
            totalDurationSeconds = 30,
            aspectRatio = aspectRatio,
            scenes = scenes
        )
    }
}
