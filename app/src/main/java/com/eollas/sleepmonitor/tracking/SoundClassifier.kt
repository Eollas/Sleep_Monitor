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

    /** Sons de respiration (pour repérer les pauses respiratoires). */
    const val BREATHING = 36
    const val GASP = 39
    const val SNORT = 41
    private val BREATH_SOUNDS = setOf(36, 37, 39, 40, 45)

    /**
     * Fond sonore « normal » d'une chambre : silence, souffle du micro, ventilateur,
     * climatisation, froissement des draps… Un son n'est retenu que s'il est jugé
     * plus probable que ce fond.
     */
    val BACKGROUND = intArrayOf(494, 505, 506, 507, 508, 509, 510, 514, 515, 516, 517, 406, 407, 481)

    /**
     * Bruits perturbants retenus : uniquement des sons précis et nets.
     * Les catégories vagues (« Animal », moteur, grondement…) sont ignorées car le
     * modèle y range souvent des respirations ou des bruits du lit.
     */
    private val VAGUE = setOf(67, 68, 103, 117, 118, 121, 125, 282, 283, 288, 294, 300, 337, 338, 342, 343, 346, 442, 487)
    val DISTURBING: Set<Int> = Labels.known().filter {
        it !in VAGUE && it !in SNORE && it !in SPEECH && it !in COUGH && it !in BREATH_SOUNDS && it !in BACKGROUND
    }.toSet() + (132..140) // musique

    /**
     * Seuils selon le curseur de sensibilité (0 = moins sensible, 1 = plus sensible).
     * Au milieu (0,5), l'app est un peu plus stricte que l'ancien réglage « Normale ».
     */
    class Tuning(sensitivity: Float) {
        private val k = sensitivity.coerceIn(0f, 1f)
        private fun lerp(least: Float, most: Float) = least + (most - least) * k

        /** Écart minimal (dB) entre le niveau de la dernière seconde et le bruit de fond. */
        val gateDb = lerp(10f, 3f)
        val snore = lerp(0.45f, 0.20f)
        val speech = lerp(0.65f, 0.35f)
        val cough = lerp(0.55f, 0.30f)
        val noise = lerp(0.65f, 0.35f)
        /** Un bruit perturbant doit être nettement plus fort que le fond. */
        val noiseDb = lerp(20f, 10f)
        private val extraVotes = when { k < 0.34f -> 1; k > 0.67f -> -1; else -> 0 }

        /**
         * Nombre d'analyses concordantes exigées dans la fenêtre de 6 s.
         * Ronflements et paroles durent : 3 sur 12. Toux et bruits sont brefs : 2.
         */
        fun votes(c: SoundCategory): Int = (
            when (c) {
                SoundCategory.SNORING, SoundCategory.SPEECH -> 3
                else -> 2
            } + extraVotes
        ).coerceAtLeast(1)
    }

    data class Detection(val category: SoundCategory, val index: Int, val confidence: Float)

    /** Les [n] catégories YAMNet les plus probables, de la plus à la moins probable. */
    fun top(scores: FloatArray, n: Int = 3): List<Pair<Int, Float>> =
        scores.indices.sortedByDescending { scores[it] }.take(n).map { it to scores[it] }

    /** Résultat d'une seule analyse (≈ 1 s de son). L'événement est confirmé ensuite par la fenêtre de 6 s. */
    fun decide(scores: FloatArray, levelAboveFloor: Float, t: Tuning): Detection? {
        fun best(ids: IntArray): Pair<Int, Float> {
            var bi = ids[0]; var bv = 0f
            for (i in ids) if (scores[i] > bv) { bv = scores[i]; bi = i }
            return bi to bv
        }
        val background = BACKGROUND.maxOf { scores[it] }
        // Pour les paroles et la toux, une simple respiration compte aussi comme « fond »
        val backgroundOrBreath = maxOf(background, scores[BREATHING])
        val (si, sv) = best(SNORE)
        if (sv >= t.snore && sv > background) return Detection(SoundCategory.SNORING, si, sv)
        val (pi, pv) = best(SPEECH)
        if (pv >= t.speech && pv > backgroundOrBreath) return Detection(SoundCategory.SPEECH, pi, pv)
        val (ci, cv) = best(COUGH)
        if (cv >= t.cough && cv > backgroundOrBreath) return Detection(SoundCategory.COUGH, ci, cv)
        if (levelAboveFloor >= t.noiseDb) {
            // Le son le plus probable doit être un bruit perturbant précis et assez sûr
            var bi = -1; var bv = 0f
            for (i in scores.indices) if (scores[i] > bv) { bv = scores[i]; bi = i }
            if (bi in DISTURBING && bv >= t.noise) return Detection(SoundCategory.NOISE, bi, bv)
        }
        return null
    }

    /** « 38:0.620;36:0.210;494:0.080 » : format enregistré en base et exporté. */
    fun encodeTop(top: List<Pair<Int, Float>>): String =
        top.joinToString(";") { (i, v) -> String.format(java.util.Locale.US, "%d:%.3f", i, v) }

    fun decodeTop(s: String?): List<Pair<Int, Float>> = s.orEmpty().split(';').mapNotNull { part ->
        val (i, v) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val idx = i.toIntOrNull() ?: return@mapNotNull null
        val score = v.toFloatOrNull() ?: return@mapNotNull null
        idx to score
    }
}
