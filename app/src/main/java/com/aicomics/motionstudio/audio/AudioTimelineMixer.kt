package com.aicomics.motionstudio.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Offline PCM WAV timeline mixer.
 *
 * Accepts uncompressed 16-bit PCM WAV sources with a common sample rate and channel count.
 * Mixes clips at millisecond offsets, applies clip gain and linear fades, and writes a PCM WAV.
 * This is the audio preparation stage; AAC encoding and MP4 muxing are separate pipeline steps.
 */
class AudioTimelineMixer(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channels: Int = DEFAULT_CHANNELS
) {
    data class Clip(
        val file: File,
        val startMilliseconds: Long,
        val gain: Float = 1f,
        val fadeInMilliseconds: Long = 0,
        val fadeOutMilliseconds: Long = 0
    ) {
        init {
            require(startMilliseconds >= 0) { "Clip start time cannot be negative." }
            require(gain >= 0f && gain <= 4f) { "Clip gain must be between 0 and 4." }
            require(fadeInMilliseconds >= 0 && fadeOutMilliseconds >= 0) { "Fade durations cannot be negative." }
        }
    }

    data class MixResult(
        val outputFile: File,
        val durationMilliseconds: Long,
        val clipCount: Int,
        val sampleRate: Int,
        val channels: Int
    )

    fun mix(
        clips: List<Clip>,
        outputFile: File,
        durationMilliseconds: Long,
        onProgress: (Float) -> Unit = {}
    ): MixResult {
        require(clips.isNotEmpty()) { "At least one audio clip is required." }
        require(durationMilliseconds > 0) { "Timeline duration must be positive." }
        require(sampleRate in 8_000..96_000) { "Unsupported output sample rate." }
        require(channels in 1..2) { "Output must be mono or stereo." }
        clips.forEach { require(it.file.isFile && it.file.canRead()) { "Audio file cannot be read: ${it.file.name}" } }

        val totalFrames = millisecondsToFrames(durationMilliseconds)
        val bytesPerFrame = channels * BYTES_PER_SAMPLE
        val outputDataBytes = totalFrames * bytesPerFrame
        require(outputDataBytes <= MAX_WAV_DATA_BYTES) {
            "PCM WAV output would exceed the 4 GiB WAV format limit."
        }
        outputFile.parentFile?.mkdirs()

        val readers = mutableListOf<ClipReader>()
        try {
            clips.forEach { clip ->
                val reader = WavPcmReader(clip.file)
                require(reader.sampleRate == sampleRate && reader.channels == channels) {
                    "Audio files must match output format: ${sampleRate} Hz, $channels channel(s)."
                }
                readers += ClipReader(clip, reader, millisecondsToFrames(clip.startMilliseconds))
            }

            BufferedOutputStream(outputFile.outputStream(), IO_BUFFER_SIZE).use { output ->
                writeWavHeader(output, outputDataBytes.toInt(), sampleRate, channels)
                val chunkFrames = CHUNK_BYTES / bytesPerFrame
                val mixBuffer = FloatArray(chunkFrames * channels)
                val pcmBuffer = ByteArray(chunkFrames * bytesPerFrame)
                var frameCursor = 0L
                while (frameCursor < totalFrames) {
                    val framesThisChunk = minOf(chunkFrames.toLong(), totalFrames - frameCursor).toInt()
                    val samplesThisChunk = framesThisChunk * channels
                    java.util.Arrays.fill(mixBuffer, 0, samplesThisChunk, 0f)

                    readers.forEach { clipReader ->
                        val clipStart = clipReader.startFrame
                        val chunkEnd = frameCursor + framesThisChunk
                        val activeStart = maxOf(frameCursor, clipStart)
                        val activeEnd = minOf(chunkEnd, clipStart + clipReader.reader.frameCount)
                        if (activeEnd <= activeStart) return@forEach

                        val sourceOffsetFrames = activeStart - clipStart
                        val destinationOffsetFrames = activeStart - frameCursor
                        val activeFrames = (activeEnd - activeStart).toInt()
                        clipReader.reader.readFramesAt(sourceOffsetFrames, activeFrames, pcmBuffer)

                        for (frame in 0 until activeFrames) {
                            val clipFrame = sourceOffsetFrames + frame
                            val gain = clipGain(clipReader.clip, clipFrame, clipReader.reader.frameCount)
                            for (channel in 0 until channels) {
                                val byteIndex = (frame * channels + channel) * 2
                                val low = pcmBuffer[byteIndex].toInt() and 0xff
                                val high = pcmBuffer[byteIndex + 1].toInt()
                                val sample = ((high shl 8) or low).toShort().toInt() / 32768f
                                val targetIndex = ((destinationOffsetFrames + frame) * channels + channel).toInt()
                                mixBuffer[targetIndex] += sample * gain
                            }
                        }
                    }

                    for (sampleIndex in 0 until samplesThisChunk) {
                        val pcm = (mixBuffer[sampleIndex].coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
                        pcmBuffer[sampleIndex * 2] = (pcm and 0xff).toByte()
                        pcmBuffer[sampleIndex * 2 + 1] = ((pcm shr 8) and 0xff).toByte()
                    }
                    output.write(pcmBuffer, 0, samplesThisChunk * 2)
                    frameCursor += framesThisChunk
                    onProgress(frameCursor.toFloat() / totalFrames.toFloat())
                }
            }
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        } finally {
            readers.forEach { runCatching { it.reader.close() } }
        }

        return MixResult(outputFile, durationMilliseconds, clips.size, sampleRate, channels)
    }

    private fun clipGain(clip: Clip, frame: Long, totalFrames: Long): Float {
        var gain = clip.gain
        val fadeInFrames = millisecondsToFrames(clip.fadeInMilliseconds)
        if (fadeInFrames > 0 && frame < fadeInFrames) {
            gain *= frame.toFloat() / fadeInFrames.toFloat()
        }
        val fadeOutFrames = millisecondsToFrames(clip.fadeOutMilliseconds)
        if (fadeOutFrames > 0 && frame >= totalFrames - fadeOutFrames) {
            val remaining = (totalFrames - frame).coerceAtLeast(0L)
            gain *= remaining.toFloat() / fadeOutFrames.toFloat()
        }
        return gain.coerceIn(0f, 4f)
    }

    private fun millisecondsToFrames(milliseconds: Long): Long =
        (milliseconds * sampleRate) / 1_000L

    private fun writeWavHeader(output: BufferedOutputStream, dataSize: Int, rate: Int, channelCount: Int) {
        val byteRate = rate * channelCount * BYTES_PER_SAMPLE
        val blockAlign = channelCount * BYTES_PER_SAMPLE
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, 36 + dataSize)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, 16)
        writeShortLe(output, 1)
        writeShortLe(output, channelCount)
        writeIntLe(output, rate)
        writeIntLe(output, byteRate)
        writeShortLe(output, blockAlign)
        writeShortLe(output, 16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLe(output, dataSize)
    }

    private fun writeIntLe(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private fun writeShortLe(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private data class ClipReader(
        val clip: Clip,
        val reader: WavPcmReader,
        val startFrame: Long
    )

    private class WavPcmReader(file: File) : AutoCloseable {
        private val random = RandomAccessFile(file, "r")
        val sampleRate: Int
        val channels: Int
        private val dataOffset: Long
        private val dataSize: Long
        val frameCount: Long

        init {
            require(random.length() >= 44) { "Invalid WAV file: ${file.name}" }
            require(readAscii(0, 4) == "RIFF" && readAscii(8, 4) == "WAVE") {
                "Only RIFF/WAVE PCM files are supported: ${file.name}"
            }
            var cursor = 12L
            var foundFormat = false
            var foundData = false
            var parsedRate = 0
            var parsedChannels = 0
            var parsedBits = 0
            var parsedEncoding = 0
            var parsedDataOffset = 0L
            var parsedDataSize = 0L

            while (cursor + 8 <= random.length()) {
                val chunkName = readAscii(cursor, 4)
                val chunkSize = readUnsignedInt(cursor + 4)
                val payload = cursor + 8
                if (chunkSize < 0 || payload + chunkSize > random.length()) break
                when (chunkName) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "Invalid WAV format chunk." }
                        parsedEncoding = readUnsignedShort(payload)
                        parsedChannels = readUnsignedShort(payload + 2)
                        parsedRate = readUnsignedInt(payload + 4).toInt()
                        parsedBits = readUnsignedShort(payload + 14)
                        foundFormat = true
                    }
                    "data" -> {
                        parsedDataOffset = payload
                        parsedDataSize = chunkSize
                        foundData = true
                    }
                }
                cursor = payload + chunkSize + (chunkSize and 1L)
                if (foundFormat && foundData) break
            }

            require(foundFormat && foundData) { "WAV is missing a format or data chunk." }
            require(parsedEncoding == 1 && parsedBits == 16) { "Only 16-bit integer PCM WAV is supported." }
            require(parsedChannels in 1..2) { "Only mono and stereo WAV clips are supported." }
            require(parsedRate in 8_000..96_000) { "Unsupported WAV sample rate." }
            val frameBytes = parsedChannels * BYTES_PER_SAMPLE
            require(parsedDataSize % frameBytes == 0L) { "WAV data is not aligned to complete audio frames." }
            sampleRate = parsedRate
            channels = parsedChannels
            dataOffset = parsedDataOffset
            dataSize = parsedDataSize
            frameCount = dataSize / frameBytes
        }

        fun readFramesAt(sourceFrame: Long, frames: Int, destination: ByteArray) {
            require(sourceFrame >= 0 && frames >= 0)
            val frameBytes = channels * BYTES_PER_SAMPLE
            val byteCount = frames * frameBytes
            require(destination.size >= byteCount)
            val byteOffset = sourceFrame * frameBytes
            require(byteOffset + byteCount <= dataSize) { "Audio read exceeds clip duration." }
            random.seek(dataOffset + byteOffset)
            random.readFully(destination, 0, byteCount)
        }

        private fun readAscii(offset: Long, length: Int): String {
            val bytes = ByteArray(length)
            random.seek(offset)
            random.readFully(bytes)
            return String(bytes, Charsets.US_ASCII)
        }

        private fun readUnsignedShort(offset: Long): Int {
            random.seek(offset)
            return random.readUnsignedByte() or (random.readUnsignedByte() shl 8)
        }

        private fun readUnsignedInt(offset: Long): Long {
            random.seek(offset)
            return random.readUnsignedByte().toLong() or
                (random.readUnsignedByte().toLong() shl 8) or
                (random.readUnsignedByte().toLong() shl 16) or
                (random.readUnsignedByte().toLong() shl 24)
        }

        override fun close() = random.close()
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 44_100
        const val DEFAULT_CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES = 32 * 1024
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val MAX_WAV_DATA_BYTES = 0xffff_ffffL - 36L
    }
}
