package com.aicomics.motionstudio.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile

/**
 * Encodes a PCM WAV file to AAC and combines the AAC track with an existing H.264 MP4.
 * Input WAV is expected to be 16-bit PCM as produced by AudioTimelineMixer.
 */
class AudioMp4Pipeline {
    fun encodeAndMux(
        videoOnlyMp4: File,
        pcmWav: File,
        outputMp4: File,
        sampleRate: Int = 48_000,
        channels: Int = 2,
        onProgress: (Float) -> Unit = {}
    ) {
        require(videoOnlyMp4.isFile && videoOnlyMp4.length() > 0) { "Video MP4 is missing." }
        require(pcmWav.isFile && pcmWav.length() > 44) { "PCM WAV is missing or empty." }
        outputMp4.parentFile?.mkdirs()
        val audioOnly = File(outputMp4.parentFile, "${outputMp4.nameWithoutExtension}_audio_tmp.mp4")
        try {
            encodeWavToAac(pcmWav, audioOnly, sampleRate, channels, onProgress)
            muxTracks(videoOnlyMp4, audioOnly, outputMp4)
        } finally {
            audioOnly.delete()
        }
    }

    private fun encodeWavToAac(
        wav: File,
        output: File,
        sampleRate: Int,
        channels: Int,
        onProgress: (Float) -> Unit
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            RandomAccessFile(wav, "r").use { input ->
                val dataOffsetAndSize = findPcmData(input)
                val dataOffset = dataOffsetAndSize.first
                val dataSize = dataOffsetAndSize.second
                input.seek(dataOffset)
                var bytesFed = 0L
                var inputEos = false
                var outputEos = false
                var audioFormatSeen = false
                val info = MediaCodec.BufferInfo()
                while (!outputEos) {
                    if (!inputEos) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            buffer.clear()
                            val remaining = dataSize - bytesFed
                            if (remaining <= 0L) {
                                val pts = bytesFed * 1_000_000L / (sampleRate * channels * 2L)
                                codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                val requested = minOf(buffer.remaining().toLong(), remaining).toInt()
                                val bytes = ByteArray(requested)
                                val actual = input.read(bytes)
                                if (actual <= 0) {
                                    val pts = bytesFed * 1_000_000L / (sampleRate * channels * 2L)
                                    codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    buffer.put(bytes, 0, actual)
                                    val pts = bytesFed * 1_000_000L / (sampleRate * channels * 2L)
                                    codec.queueInputBuffer(inputIndex, 0, actual, pts, 0)
                                    bytesFed += actual
                                    onProgress((bytesFed.toFloat() / dataSize.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }

                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!audioFormatSeen) { "AAC encoder changed output format more than once." }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            audioFormatSeen = true
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (index >= 0) {
                            val encoded = codec.getOutputBuffer(index)
                            if (encoded != null && info.size > 0 && muxerStarted) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, encoded, info)
                            }
                            outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
                check(muxerStarted) { "AAC encoder did not produce an audio track." }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun muxTracks(videoFile: File, audioFile: File, outputFile: File) {
        val video = MediaExtractor()
        val audio = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            video.setDataSource(videoFile.absolutePath)
            audio.setDataSource(audioFile.absolutePath)
            val videoTrack = findTrack(video, "video/")
            val audioTrack = findTrack(audio, "audio/")
            require(videoTrack >= 0) { "Video MP4 contains no video track." }
            require(audioTrack >= 0) { "AAC file contains no audio track." }
            video.selectTrack(videoTrack)
            audio.selectTrack(audioTrack)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputVideoTrack = muxer.addTrack(video.getTrackFormat(videoTrack))
            val outputAudioTrack = muxer.addTrack(audio.getTrackFormat(audioTrack))
            muxer.start()
            started = true

            val maxVideoUs = durationUs(video.getTrackFormat(videoTrack))
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            var videoPts = video.sampleTime
            var audioPts = audio.sampleTime
            while (!videoDone || (!audioDone && (maxVideoUs <= 0L || audioPts < maxVideoUs))) {
                val useVideo = !videoDone && (audioDone || audioPts < 0L || (maxVideoUs > 0L && videoPts >= maxVideoUs) || videoPts <= audioPts)
                val extractor = if (useVideo) video else audio
                val outTrack = if (useVideo) outputVideoTrack else outputAudioTrack
                val pts = extractor.sampleTime
                if (pts < 0L || (!useVideo && maxVideoUs > 0L && pts >= maxVideoUs)) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                info.set(0, size, pts, extractor.sampleFlags)
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
                videoPts = if (videoDone) -1L else video.sampleTime
                audioPts = if (audioDone) -1L else audio.sampleTime
            }
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            video.release()
            audio.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) return index
        }
        return -1
    }

    private fun durationUs(format: MediaFormat): Long =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L

    private fun findPcmData(input: RandomAccessFile): Pair<Long, Long> {
        require(input.length() >= 44) { "Invalid WAV file." }
        input.seek(0)
        val header = ByteArray(12)
        input.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Expected a RIFF/WAVE file." }
        var cursor = 12L
        while (cursor + 8 <= input.length()) {
            input.seek(cursor)
            val chunkHeader = ByteArray(8)
            input.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = (chunkHeader[4].toLong() and 0xff) or
                ((chunkHeader[5].toLong() and 0xff) shl 8) or
                ((chunkHeader[6].toLong() and 0xff) shl 16) or
                ((chunkHeader[7].toLong() and 0xff) shl 24)
            val dataStart = cursor + 8
            if (id == "data") return dataStart to size.coerceAtMost(input.length() - dataStart)
            cursor = dataStart + size + (size and 1L)
        }
        error("WAV data chunk was not found.")
    }

}
