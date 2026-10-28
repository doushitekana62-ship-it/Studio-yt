package com.aicomics.motionstudio.render

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import com.aicomics.motionstudio.motion.MotionEngine
import com.aicomics.motionstudio.motion.MotionPreset
import com.aicomics.motionstudio.storyboard.Scene
import com.aicomics.motionstudio.storyboard.Storyboard
import com.aicomics.motionstudio.storyboard.TimelineValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Video-only local MP4 renderer. It creates comic-panel placeholder art from storyboard
 * text, animates it with GLES camera transforms, and encodes H.264 through MediaCodec.
 *
 * Audio muxing, user-provided artwork, and burn-in subtitle styling are future adapters.
 */
class MediaCodecComicVideoRenderer(
    context: Context,
    private val composer: ComicFrameComposer = ComicFrameComposer()
) : ComicVideoRenderer {
    private val outputDirectory = File(context.filesDir, "exports").apply { mkdirs() }

    override suspend fun render(
        request: RenderRequest,
        onProgress: (RenderProgress) -> Unit
    ): RenderResult = withContext(Dispatchers.IO) {
        val validation = TimelineValidator.validate(request.storyboard)
        if (!validation.isValid) {
            return@withContext RenderResult.Failure(validation.errors.joinToString(" "))
        }
        if (request.outputWidth < 2 || request.outputHeight < 2 ||
            request.outputWidth % 2 != 0 || request.outputHeight % 2 != 0) {
            return@withContext RenderResult.Failure("Output dimensions must be positive even numbers.")
        }
        if (request.framesPerSecond !in 1..60) {
            return@withContext RenderResult.Failure("Frame rate must be between 1 and 60 FPS.")
        }

        val output = File(outputDirectory, "comic_motion_${System.currentTimeMillis()}.mp4")
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var egl: EglVideoSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var framesSubmitted = 0L
        val totalFrames = request.storyboard.totalDurationSeconds.toLong() * request.framesPerSecond
        var outputEos = false

        try {
            currentCoroutineContext().ensureActive()
            onProgress(RenderProgress(RenderStage.VALIDATING, message = "Storyboard timeline validated"))
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                request.outputWidth,
                request.outputHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (request.outputWidth * request.outputHeight * request.framesPerSecond * 0.10f).toInt().coerceAtLeast(500_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, request.framesPerSecond)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder.start()
            egl = EglVideoSurface(inputSurface, request.outputWidth, request.outputHeight)
            val gl = egl
            val mux = muxer
            val codec = encoder

            onProgress(RenderProgress(RenderStage.RENDERING_VIDEO, 0, totalFrames, "Rendering comic motion frames"))

            request.storyboard.scenes.forEachIndexed { sceneIndex, scene ->
                currentCoroutineContext().ensureActive()
                val bitmap = composer.compose(scene, request.outputWidth, request.outputHeight, sceneIndex)
                try {
                    val texture = gl.uploadTexture(bitmap)
                    val preset = presetFor(scene.cameraMotion)
                    val sceneFrames = scene.durationSeconds * request.framesPerSecond
                    repeat(sceneFrames) { localFrame ->
                        currentCoroutineContext().ensureActive()
                        val progress = if (sceneFrames <= 1) 1f else localFrame.toFloat() / (sceneFrames - 1)
                        val transform = MotionEngine.evaluate(preset, progress)
                        gl.draw(texture, transform.scale, transform.translateX, transform.translateY, transform.rotationDegrees)
                        val presentationNs = (framesSubmitted * 1_000_000_000L) / request.framesPerSecond
                        gl.setPresentationTime(presentationNs)
                        gl.swapBuffers()
                        framesSubmitted++
                        while (true) {
                            val drain = drainEncoder(codec, mux, muxerStarted, trackIndex, false)
                            if (drain.trackIndex >= 0) trackIndex = drain.trackIndex
                            if (drain.muxerStarted) muxerStarted = true
                            if (drain.outputEos) outputEos = true
                            if (!drain.hadOutput) break
                        }
                        if (framesSubmitted % request.framesPerSecond == 0L || framesSubmitted == totalFrames) {
                            onProgress(
                                RenderProgress(
                                    RenderStage.RENDERING_VIDEO,
                                    framesSubmitted,
                                    totalFrames,
                                    "Rendering scene ${sceneIndex + 1}/${request.storyboard.scenes.size}"
                                )
                            )
                        }
                    }
                    gl.deleteTexture(texture)
                } finally {
                    bitmap.recycle()
                }
            }

            currentCoroutineContext().ensureActive()
            onProgress(RenderProgress(RenderStage.FINALIZING, framesSubmitted, totalFrames, "Finalizing MP4"))
            codec.signalEndOfInputStream()
            while (!outputEos) {
                currentCoroutineContext().ensureActive()
                val drain = drainEncoder(codec, mux, muxerStarted, trackIndex, true)
                if (drain.trackIndex >= 0) trackIndex = drain.trackIndex
                if (drain.muxerStarted) muxerStarted = true
                outputEos = drain.outputEos
            }
            if (!muxerStarted || !output.exists() || output.length() <= 0L) {
                return@withContext RenderResult.Failure("Encoder did not produce a valid MP4 stream.")
            }
            onProgress(RenderProgress(RenderStage.COMPLETED, totalFrames, totalFrames, "Video export complete"))
            RenderResult.Success(output.absolutePath, request.storyboard.totalDurationSeconds * 1_000L)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            output.delete()
            RenderResult.Cancelled
        } catch (error: Throwable) {
            output.delete()
            RenderResult.Failure(error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { egl?.release() }
            runCatching { inputSurface?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private data class DrainResult(
        val trackIndex: Int = -1,
        val muxerStarted: Boolean = false,
        val outputEos: Boolean = false,
        val hadOutput: Boolean = false
    )

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        alreadyStarted: Boolean,
        currentTrackIndex: Int,
        waitForOutput: Boolean
    ): DrainResult {
        var started = alreadyStarted
        var track = currentTrackIndex
        var eos = false
        var hadOutput = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            val timeoutUs = if (waitForOutput) 10_000L else 0L
            when (val index = encoder.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForOutput || hadOutput) break
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "Encoder output format changed more than once." }
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    hadOutput = true
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && started) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) break
                }
            }
        }
        return DrainResult(track, started, eos, hadOutput)
    }

    private fun presetFor(cameraMotion: String): MotionPreset {
        val value = cameraMotion.lowercase(Locale.ROOT)
        return when {
            "left" in value || "pan left" in value -> MotionPreset.PAN_LEFT
            "right" in value || "pan right" in value -> MotionPreset.PAN_RIGHT
            "up" in value -> MotionPreset.PAN_UP
            "down" in value -> MotionPreset.PAN_DOWN
            "zoom out" in value -> MotionPreset.ZOOM_OUT
            "zoom" in value || "push in" in value -> MotionPreset.ZOOM_IN
            "shake" in value || "impact" in value -> MotionPreset.CAMERA_SHAKE
            else -> MotionPreset.STATIC
        }
    }
}

private class EglVideoSurface(surface: Surface, private val width: Int, private val height: Int) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val program: Int
    private val positionHandle: Int
    private val texCoordHandle: Int
    private val matrixHandle: Int
    private val textureHandle: Int
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        require(display != EGL14.EGL_NO_DISPLAY) { "Could not acquire EGL display." }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Could not initialize EGL." }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No EGL config supports video encoding."
        }
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context." }
        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not create EGL window surface." }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "Could not bind EGL context." }

        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPosition; attribute vec2 aTexCoord; uniform mat4 uMatrix; varying vec2 vTexCoord; void main(){ gl_Position=uMatrix*aPosition; vTexCoord=aTexCoord; }"
        )
        val fragmentShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; uniform sampler2D uTexture; varying vec2 vTexCoord; void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }"
        )
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            val status = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Could not link GLES shader program." }
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        vertexBuffer = floatBuffer(floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        ))
        texCoordBuffer = floatBuffer(floatArrayOf(
            0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
        ))
    }

    fun uploadTexture(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Could not upload scene artwork texture." }
        return texture
    }

    fun draw(texture: Int, scale: Float, x: Float, y: Float, rotation: Float) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, x * 2f, -y * 2f, 0f)
        Matrix.rotateM(matrix, 0, rotation, 0f, 0f, 1f)
        Matrix.scaleM(matrix, 0, scale, scale, 1f)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(textureHandle, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun setPresentationTime(nanoseconds: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanoseconds)
    }

    fun swapBuffers() {
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "Could not submit frame to video encoder." }
    }

    fun deleteTexture(texture: Int) {
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Could not compile GLES shader." }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .apply { position(0) }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
