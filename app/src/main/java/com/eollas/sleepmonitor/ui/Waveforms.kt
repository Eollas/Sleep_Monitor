package com.eollas.sleepmonitor.ui

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import kotlin.math.log10

/**
 * Courbe du son d'un extrait (aperçu affiché sous chaque son).
 * L'extrait AAC est décodé une fois sur le téléphone, le résultat est gardé en mémoire.
 */
object Waveforms {
    const val BARS = 90
    private const val RANGE_DB = 40f
    val cache = LruCache<String, FloatArray>(200)

    /** Hauteurs 0..1 de [BARS] barres, ou null si l'extrait est illisible. */
    fun load(path: String): FloatArray? {
        cache.get(path)?.let { return it }
        val r = runCatching { decode(path) }.getOrNull() ?: return null
        cache.put(path, r)
        return r
    }

    private fun decode(path: String): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            // Énergie par tranche de 50 ms, regroupée ensuite en barres
            val energies = ArrayList<Double>()
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val per = rate / 20
            var acc = 0.0; var cnt = 0
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false; var outDone = false
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        while (buf.hasRemaining()) {
                            val s = buf.get().toDouble()
                            acc += s * s; cnt++
                            if (cnt == per) { energies += acc / cnt; acc = 0.0; cnt = 0 }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (cnt > 0) energies += acc / cnt
            return bars(energies)
        } finally {
            ex.release()
        }
    }

    /** Regroupe les énergies en barres, échelle en dB (les sons faibles restent visibles). */
    fun bars(energies: List<Double>): FloatArray? {
        if (energies.isEmpty()) return null
        val out = FloatArray(BARS)
        for (b in 0 until BARS) {
            val from = b * energies.size / BARS
            val to = maxOf(from + 1, (b + 1) * energies.size / BARS).coerceAtMost(energies.size)
            var m = 0.0
            for (i in from until to) m = maxOf(m, energies[i])
            out[b] = (10 * log10(m + 1e-3)).toFloat()
        }
        val top = out.max()
        for (i in out.indices) out[i] = ((out[i] - (top - RANGE_DB)) / RANGE_DB).coerceIn(0.04f, 1f)
        return out
    }
}
