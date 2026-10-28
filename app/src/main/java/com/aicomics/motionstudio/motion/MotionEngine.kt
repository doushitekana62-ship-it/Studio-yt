package com.aicomics.motionstudio.motion

import kotlin.math.PI
import kotlin.math.sin

enum class MotionPreset {
    STATIC,
    PAN_LEFT,
    PAN_RIGHT,
    PAN_UP,
    PAN_DOWN,
    ZOOM_IN,
    ZOOM_OUT,
    CAMERA_SHAKE
}

data class MotionTransform(
    val scale: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val rotationDegrees: Float = 0f
)

/**
 * Calculates normalized 2D camera transforms for a single scene.
 * Values are deterministic and do not allocate image-sized buffers.
 * A renderer is responsible for applying these transforms to its drawing surface.
 */
object MotionEngine {
    fun evaluate(
        preset: MotionPreset,
        progress: Float,
        intensity: Float = 1f
    ): MotionTransform {
        val t = progress.coerceIn(0f, 1f)
        val strength = intensity.coerceIn(0f, 2f)

        return when (preset) {
            MotionPreset.STATIC -> MotionTransform()
            MotionPreset.PAN_LEFT -> MotionTransform(translateX = -0.12f * t * strength)
            MotionPreset.PAN_RIGHT -> MotionTransform(translateX = 0.12f * t * strength)
            MotionPreset.PAN_UP -> MotionTransform(translateY = -0.12f * t * strength)
            MotionPreset.PAN_DOWN -> MotionTransform(translateY = 0.12f * t * strength)
            MotionPreset.ZOOM_IN -> MotionTransform(scale = 1f + 0.22f * t * strength)
            MotionPreset.ZOOM_OUT -> MotionTransform(scale = 1f + 0.22f * (1f - t) * strength)
            MotionPreset.CAMERA_SHAKE -> {
                val envelope = (1f - t) * strength
                val phase = t * 12f * PI.toFloat()
                MotionTransform(
                    scale = 1.04f,
                    translateX = sin(phase * 1.7f) * 0.018f * envelope,
                    translateY = sin(phase * 2.3f) * 0.014f * envelope,
                    rotationDegrees = sin(phase) * 1.2f * envelope
                )
            }
        }
    }
}
