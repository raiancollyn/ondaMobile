package net.dpbr.onda

import android.util.Log
import com.audx.android.Audx
import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer

/**
 * Processador de captura do LiveKit usando RNNoise.
 *
 * O LiveKit entrega PCM16 no ByteBuffer. O Audx cuida do frame de 10 ms e do
 * resample para 48 kHz quando o aparelho usa outra taxa.
 */
class RnnoiseProcessor : AudioProcessorInterface {
    @Volatile
    private var enabled = true

    private var audx: Audx? = null
    private var sampleRate = 48_000
    private var channels = 1
    private var inputBuffer = ShortArray(0)
    private var outputBuffer = ShortArray(0)

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun close() {
        try {
            audx?.close()
        } catch (error: Throwable) {
            Log.w(TAG, "Falha ao liberar o RNNoise.", error)
        } finally {
            audx = null
        }
    }

    override fun isEnabled(): Boolean = enabled

    override fun getName(): String = "RNNoise"

    override fun initializeAudioProcessing(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        this.channels = channels.coerceAtLeast(1)
        close()

        try {
            audx = Audx.Builder()
                .inputRate(this.sampleRate)
                .resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_VOIP)
                .build()
            Log.i(TAG, "RNNoise ativo em ${this.sampleRate} Hz, ${this.channels} canal(is).")
        } catch (error: Throwable) {
            audx = null
            Log.w(TAG, "RNNoise indisponível neste aparelho.", error)
        }
    }

    override fun resetAudioProcessing(sampleRate: Int) {
        initializeAudioProcessing(sampleRate, channels)
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!enabled) {
            return
        }

        val processor = audx ?: return
        if (numFrames <= 0 || !buffer.hasRemaining()) {
            return
        }

        try {
            val source = buffer.asFloatBuffer()
            val sampleCount = minOf(numFrames, source.remaining())
            if (sampleCount <= 0) {
                return
            }

            if (inputBuffer.size < sampleCount) {
                inputBuffer = ShortArray(sampleCount)
                outputBuffer = ShortArray(sampleCount)
            }

            for (index in 0 until sampleCount) {
                inputBuffer[index] = (source.get(index) * 32767f)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            processor.process(inputBuffer, outputBuffer) { }

            buffer.rewind()
            val target = buffer.asFloatBuffer()
            for (index in 0 until sampleCount) {
                target.put(index, outputBuffer[index] / 32767f)
            }
            buffer.position(0)
        } catch (error: Throwable) {
            Log.w(TAG, "Falha ao processar áudio com RNNoise.", error)
        }
    }

    private companion object {
        const val TAG = "OndaRnnoise"
    }
}
