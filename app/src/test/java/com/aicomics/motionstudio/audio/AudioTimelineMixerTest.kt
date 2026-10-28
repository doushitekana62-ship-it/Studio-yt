package com.aicomics.motionstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class AudioTimelineMixerTest {
    @Test
    fun overlaysClipsAtTheirTimelineOffsets() {
        val directory = Files.createTempDirectory("audio-mix-test").toFile()
        val first = File(directory, "first.wav")
        val second = File(directory, "second.wav")
        val output = File(directory, "mix.wav")
        try {
            writeWav(first, List(4_000) { 10_000 })
            writeWav(second, listOf(5_000, 5_000))
            val result = AudioTimelineMixer(sampleRate = 8_000, channels = 1).mix(
                clips = listOf(
                    AudioTimelineMixer.Clip(first, startMilliseconds = 0),
                    AudioTimelineMixer.Clip(second, startMilliseconds = 250, gain = 1f)
                ),
                outputFile = output,
                durationMilliseconds = 750
            )
            val samples = readSamples(output)
            assertEquals(750L, result.durationMilliseconds)
            assertEquals(6_000, samples.size)
            assertEquals(10_000, samples[0])
            assertEquals(15_000, samples[2_000])
            assertEquals(0, samples[5_999])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun clampsMixedSamplesToPcmRange() {
        val directory = Files.createTempDirectory("audio-clamp-test").toFile()
        val source = File(directory, "loud.wav")
        val output = File(directory, "mix.wav")
        try {
            writeWav(source, listOf(30_000, -30_000))
            AudioTimelineMixer(sampleRate = 8_000, channels = 1).mix(
                clips = listOf(
                    AudioTimelineMixer.Clip(source, 0),
                    AudioTimelineMixer.Clip(source, 0)
                ),
                outputFile = output,
                durationMilliseconds = 1
            )
            val samples = readSamples(output)
            assertEquals(32767, samples[0])
            assertEquals(-32767, samples[1])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun appliesLinearFadeIn() {
        val directory = Files.createTempDirectory("audio-fade-test").toFile()
        val source = File(directory, "source.wav")
        val output = File(directory, "mix.wav")
        try {
            writeWav(source, List(8) { 12_000 })
            AudioTimelineMixer(sampleRate = 8_000, channels = 1).mix(
                clips = listOf(AudioTimelineMixer.Clip(source, 0, fadeInMilliseconds = 1)),
                outputFile = output,
                durationMilliseconds = 1
            )
            val samples = readSamples(output)
            assertEquals(0, samples[0])
            assertTrue(samples.last() > samples.first())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeWav(file: File, samples: List<Int>) {
        val data = ByteArrayOutputStream()
        samples.forEach { sample ->
            data.write(sample and 0xff)
            data.write((sample shr 8) and 0xff)
        }
        val pcm = data.toByteArray()
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, 36 + pcm.size)
            out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, 16)
            writeShortLe(out, 1)
            writeShortLe(out, 1)
            writeIntLe(out, 8_000)
            writeIntLe(out, 16_000)
            writeShortLe(out, 2)
            writeShortLe(out, 16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, pcm.size)
            out.write(pcm)
        }
    }

    private fun readSamples(file: File): List<Int> {
        val bytes = file.readBytes()
        val dataStart = 44
        return (dataStart until bytes.size step 2).map { offset ->
            ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort().toInt()
        }
    }

    private fun writeIntLe(out: FileOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun writeShortLe(out: FileOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }
}
