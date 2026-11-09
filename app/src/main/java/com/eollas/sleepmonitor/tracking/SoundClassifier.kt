package com.eollas.sleepmonitor.tracking

import android.content.Context
import com.eollas.sleepmonitor.data.SoundCategory
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Classement des sons avec YAMNet (modèle de Google, 521 catégories),
 * exécuté entièrement sur le téléphone.
 */
class SoundClassifier(ctx: Context) : Closeable {
    private val interpreter: Interpreter
    private val outCount: Int
    private val input = ByteBuffer.allocateDirect(WINDOW * 4).order(ByteOrder.nativeOrder())
    private val output: ByteBuffer

    init {
        val fd = ctx.assets.openFd("yamnet.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
        val inSize = interpreter.getInputTensor(0).shape().fold(1) { a, b -> a * b }
        if (inSize != WINDOW) {
            interpreter.resizeInput(0, intArrayOf(WINDOW))
            interpreter.allocateTensors()
        }
        outCount = interpreter.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
        output = ByteBuffer.allocateDirect(outCount * 4).order(ByteOrder.nativeOrder())
    }

    /** Renvoie les 521 probabilités pour une fenêtre de 0,975 s à 16 kHz. */
    fun classify(samples: FloatArray): FloatArray {
        input.rewind()
        for (i in 0 until WINDOW) input.putFloat(if (i < samples.size) samples[i] else 0f)
        input.rewind(); output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val scores = FloatArray(CLASSES)
        // Si le modèle renvoie plusieurs trames, on garde le maximum par catégorie.
        for (i in 0 until outCount) {
            val v = output.getFloat()
            val c = i % CLASSES
            if (v > scores[c]) scores[c] = v
        }
        return scores
    }

    override fun close() = interpreter.close()

    companion object {
        const val WINDOW = 15600
        const val CLASSES = 521
    }
}

/** Correspondance entre les catégories YAMNet et celles de l'application. */
object SoundRules {
    val SNORE = intArrayOf(38, 41)
    val SPEECH = intArrayOf(0, 1, 2, 3, 4, 6, 7, 9, 11, 12, 13, 15, 18, 19, 20, 21, 22, 23, 32, 33, 34)
    val COUGH = intArrayOf(42, 43, 44)

    /** Bruits de fond continus ou mouvements du lit : jamais signalés comme perturbants. */
    val IGNORED: Set<Int> = buildSet {
        addAll(listOf(36, 37, 39, 40, 45, 46, 47, 48, 56, 59, 60, 279, 290, 400, 401, 402, 406, 407))
        addAll(listOf(470, 471, 481, 482, 485, 490, 494, 495, 496, 499))
        addAll(500..517)
        add(520)
    }

    data class Detection(val category: SoundCategory, val index: Int, val confidence: Float)

    fun decide(scores: FloatArray, levelAboveFloor: Float, sensitivity: Int): Detection? {
        val f = when (sensitivity) { 0 -> 1.3f; 2 -> 0.75f; else -> 1f }
        val noiseGate = when (sensitivity) { 0 -> 15f; 2 -> 9f; else -> 12f }
        fun best(ids: IntArray): Pair<Int, Float> {
            var bi = ids[0]; var bv = 0f
            for (i in ids) if (scores[i] > bv) { bv = scores[i]; bi = i }
            return bi to bv
        }
        val (si, sv) = best(SNORE)
        if (sv >= 0.25f * f) return Detection(SoundCategory.SNORING, si, sv)
        val (pi, pv) = best(SPEECH)
        if (pv >= 0.35f * f) return Detection(SoundCategory.SPEECH, pi, pv)
        val (ci, cv) = best(COUGH)
        if (cv >= 0.3f * f) return Detection(SoundCategory.COUGH, ci, cv)
        if (levelAboveFloor >= noiseGate) {
            var bi = -1; var bv = 0f
            for (i in scores.indices) if (i !in IGNORED && scores[i] > bv) { bv = scores[i]; bi = i }
            if (bi >= 0 && bv >= 0.25f * f && bi !in SNORE && bi !in SPEECH && bi !in COUGH) {
                return Detection(SoundCategory.NOISE, bi, bv)
            }
        }
        return null
    }
}
