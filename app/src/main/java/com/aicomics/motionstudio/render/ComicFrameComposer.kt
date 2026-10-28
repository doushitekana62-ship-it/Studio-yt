package com.aicomics.motionstudio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.aicomics.motionstudio.storyboard.Scene

/**
 * Builds a lightweight comic-panel image from storyboard text.
 * This is a local placeholder visual, not AI-generated artwork.
 */
class ComicFrameComposer {
    fun compose(scene: Scene, width: Int, height: Int, sceneIndex: Int): Bitmap {
        require(width > 0 && height > 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(13, 15, 24))

        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette[sceneIndex.mod(palette.size)]
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), panelPaint)

        val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 5, 7, 15)
            style = Paint.Style.FILL
        }
        val horizon = height * 0.58f
        canvas.drawRect(0f, horizon, width.toFloat(), height.toFloat(), horizonPaint)

        val silhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(9, 10, 17)
            style = Paint.Style.FILL
        }
        val cx = width * 0.5f
        val baseY = height * 0.79f
        val headRadius = width * 0.095f
        canvas.drawCircle(cx, baseY - height * 0.25f, headRadius, silhouettePaint)
        canvas.drawRoundRect(
            RectF(cx - width * 0.14f, baseY - height * 0.19f, cx + width * 0.14f, baseY + height * 0.05f),
            width * 0.06f,
            width * 0.06f,
            silhouettePaint
        )
        canvas.drawRect(cx - width * 0.12f, baseY, cx - width * 0.035f, height.toFloat(), silhouettePaint)
        canvas.drawRect(cx + width * 0.035f, baseY, cx + width * 0.12f, height.toFloat(), silhouettePaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            strokeWidth = (width * 0.004f).coerceAtLeast(1f)
        }
        for (i in 0..8) {
            val y = height * (0.12f + i * 0.045f)
            canvas.drawLine(width * 0.05f, y, width * (0.18f + (i % 3) * 0.04f), y - height * 0.035f, linePaint)
            canvas.drawLine(width * 0.82f, y + height * 0.02f, width * 0.95f, y - height * 0.02f, linePaint)
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.065f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
        }
        canvas.drawText(scene.title.take(30), width * 0.07f, height * 0.11f, titlePaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.033f
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        drawWrappedText(
            canvas,
            scene.visualDescription,
            width * 0.07f,
            height * 0.17f,
            width * 0.84f,
            bodyPaint,
            maxLines = 4
        )

        if (scene.dialogue.isNotBlank()) {
            val bubble = RectF(width * 0.06f, height * 0.84f, width * 0.94f, height * 0.96f)
            val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(235, 250, 250, 255)
            }
            canvas.drawRoundRect(bubble, width * 0.025f, width * 0.025f, bubblePaint)
            val dialoguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(18, 20, 29)
                textSize = width * 0.032f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            drawWrappedText(
                canvas,
                scene.dialogue,
                width * 0.09f,
                height * 0.885f,
                width * 0.80f,
                dialoguePaint,
                maxLines = 2
            )
        }
        return bitmap
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        paint: Paint,
        maxLines: Int
    ) {
        var y = startY
        var line = ""
        var count = 0
        for (word in text.replace('\n', ' ').split(Regex("\\s+"))) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line, x, y, paint)
                count++
                if (count >= maxLines) return
                line = word
                y += paint.textSize * 1.25f
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty() && count < maxLines) canvas.drawText(line, x, y, paint)
    }

    private companion object {
        val palette = intArrayOf(
            Color.rgb(48, 58, 91),
            Color.rgb(88, 42, 65),
            Color.rgb(33, 78, 88),
            Color.rgb(79, 65, 113),
            Color.rgb(105, 58, 40)
        )
    }
}
