package com.aicomics.motionstudio.render

import com.aicomics.motionstudio.storyboard.Storyboard

enum class RenderStage {
    VALIDATING,
    PREPARING_ASSETS,
    RENDERING_VIDEO,
    MIXING_AUDIO,
    FINALIZING,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class RenderProgress(
    val stage: RenderStage,
    val completedUnits: Long = 0,
    val totalUnits: Long = 0,
    val message: String = ""
) {
    val fraction: Float?
        get() = if (totalUnits > 0) {
            (completedUnits.toFloat() / totalUnits).coerceIn(0f, 1f)
        } else null
}

/**
 * Contract only: a concrete Android encoder pipeline must implement this interface.
 * The initial prototype intentionally does not claim that MP4 encoding is available.
 */
interface ComicVideoRenderer {
    suspend fun render(
        request: RenderRequest,
        onProgress: (RenderProgress) -> Unit
    ): RenderResult
}

data class RenderRequest(
    val storyboard: Storyboard,
    val outputWidth: Int = 720,
    val outputHeight: Int = 1280,
    val framesPerSecond: Int = 24,
    val includeSubtitles: Boolean = true,
    val includeAudio: Boolean = false
)

sealed interface RenderResult {
    data class Success(val outputPath: String, val durationMilliseconds: Long) : RenderResult
    data class Failure(val reason: String, val recoverable: Boolean = true) : RenderResult
    data object Cancelled : RenderResult
}
