package com.voiceflow.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Records 16kHz, 16-bit mono PCM audio entirely in memory and wraps it in a standard
 * 44-byte WAV header (mime_type = "audio/wav"). Nothing is ever written to phone storage.
 */
class AudioWavRecorder(
    private val onAmplitudeChanged: (Float) -> Unit = {}
) {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val pcmStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording.get()) return true

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) return false

        val bufferSize = maxOf(minBuffer * 2, 4096)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        synchronized(pcmStream) {
            pcmStream.reset()
        }

        audioRecord = record
        isRecording.set(true)
        record.startRecording()

        recordingThread = Thread {
            val shortBuffer = ShortArray(1024)
            while (isRecording.get()) {
                val readCount = record.read(shortBuffer, 0, shortBuffer.size)
                if (readCount > 0) {
                    val byteBuf = ByteBuffer.allocate(readCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var sumSquares = 0.0
                    for (i in 0 until readCount) {
                        val sample = shortBuffer[i]
                        byteBuf.putShort(sample)
                        sumSquares += (sample.toDouble() * sample.toDouble())
                    }
                    synchronized(pcmStream) {
                        pcmStream.write(byteBuf.array())
                    }
                    val rms = sqrt(sumSquares / readCount) / 32768.0
                    val normalized = (rms * 4.5).coerceIn(0.0, 1.0).toFloat()
                    onAmplitudeChanged(normalized)
                }
            }
        }.apply {
            name = "VoiceFlowAudioRecorder"
            start()
        }

        return true
    }

    fun stopAndGetWavBytes(): ByteArray {
        if (!isRecording.getAndSet(false)) {
            return ByteArray(0)
        }

        try {
            recordingThread?.join(600)
        } catch (_: InterruptedException) {
        }
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        val pcmBytes: ByteArray
        synchronized(pcmStream) {
            pcmBytes = pcmStream.toByteArray()
            pcmStream.reset()
        }

        if (pcmBytes.isEmpty()) return ByteArray(0)
        return buildWavFile(pcmBytes)
    }

    private fun buildWavFile(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 1 * 16 / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt sub-chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM sub-chunk size
        header.putShort(1) // AudioFormat 1 = PCM
        header.putShort(1) // Channels = 1 (Mono)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign = 1 * 16 / 8
        header.putShort(16) // BitsPerSample = 16

        // data sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val wavBytes = ByteArray(44 + pcmData.size)
        System.arraycopy(header.array(), 0, wavBytes, 0, 44)
        System.arraycopy(pcmData, 0, wavBytes, 44, pcmData.size)
        return wavBytes
    }
}
