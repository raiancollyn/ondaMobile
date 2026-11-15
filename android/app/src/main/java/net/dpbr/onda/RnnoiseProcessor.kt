package net.dpbr.onda

import android.util.Log
import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer

/**
 * RNNoise nativo (C/JNI) aplicado depois do cancelamento de eco do WebRTC.
 *
 * O LiveKit entrega FloatS16 ([-32768, 32767]) e o RNNoise usa exatamente essa
 * escala, então o buffer é processado no lugar, sem conversão.
 */
class RnnoiseProcessor : AudioProcessorInterface {
    @Volatile
    private var enabled = true

    @Volatile
    private var state = 0L

    private var sampleRate = 0
    private var channels = 0
    private var frames = 0L

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isAvailable(): Boolean = state != 0L

    fun close() {
        val handle = state
        state = 0L
        if (handle != 0L) {
            try {
                nativeDestroy(handle)
            } catch (error: Throwable) {
                Log.w(TAG, "Falha ao liberar o RNNoise.", error)
            }
        }
    }

    override fun isEnabled(): Boolean = enabled

    override fun getName(): String = "RNNoise"

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
        close()
        sampleRate = sampleRateHz
        channels = numChannels

        // O RNNoise só trabalha em 48 kHz, mono, quadros de 10 ms.
        if (sampleRateHz == 48_000 && numChannels == 1) {
            state = nativeCreate()
            if (state == 0L) {
                Log.w(TAG, "Não foi possível criar o estado do RNNoise.")
            } else {
                Log.i(TAG, "RNNoise nativo ativo em 48000 Hz, 1 canal.")
            }
        } else {
            Log.i(TAG, "RNNoise aguardando 48 kHz mono; recebido $sampleRateHz Hz, $numChannels canal(is).")
        }
    }

    override fun resetAudioProcessing(newRate: Int) {
        initializeAudioProcessing(newRate, channels.coerceAtLeast(1))
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!enabled || state == 0L || numFrames != FRAME_SIZE) {
            return
        }

        try {
            if (!nativeProcess(state, buffer, numFrames)) {
                return
            }
            frames++
            if (frames == 1L || frames % 100L == 0L) {
                Log.i(TAG, "frames=$frames processados")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Falha no processamento nativo do RNNoise.", error)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeProcess(handle: Long, buffer: ByteBuffer, frames: Int): Boolean
    private external fun nativeDestroy(handle: Long)

    private companion object {
        const val TAG = "OndaRnnoise"
        const val FRAME_SIZE = 480

        init {
            System.loadLibrary("onda_rnnoise")
        }
    }
}
