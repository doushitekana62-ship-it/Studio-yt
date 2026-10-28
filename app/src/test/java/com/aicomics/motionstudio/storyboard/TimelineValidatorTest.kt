package com.aicomics.motionstudio.storyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineValidatorTest {
    @Test
    fun generatedDraftHasValidThirtySecondTimeline() {
        val storyboard = LocalStoryboardDraftGenerator().generate("A hero protects a city.")
        val result = TimelineValidator.validate(storyboard)
        assertTrue(result.errors.joinToString(), result.isValid)
        assertEqualsThirtySeconds(storyboard)
    }

    @Test
    fun validatorRejectsTimelineGap() {
        val storyboard = LocalStoryboardDraftGenerator().generate("A hero protects a city.")
        val broken = storyboard.copy(
            scenes = storyboard.scenes.mapIndexed { index, scene ->
                if (index == 1) scene.copy(startSeconds = scene.startSeconds + 1) else scene
            }
        )
        assertFalse(TimelineValidator.validate(broken).isValid)
    }

    private fun assertEqualsThirtySeconds(storyboard: Storyboard) {
        assertTrue(storyboard.scenes.sumOf { it.durationSeconds } == 30)
    }
}
