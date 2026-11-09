package com.eollas.sleepmonitor.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Mesure les mouvements transmis au téléphone par le matelas (accéléromètre). */
class MotionMonitor(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null

    private val lock = Any()
    private var sum = 0.0
    private var count = 0
    private var lp = SensorManager.GRAVITY_EARTH
    private val gravity = FloatArray(3)
    private var init = false

    fun start() {
        val t = HandlerThread("motion").apply { start() }
        thread = t
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper))
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val m = sqrt(x * x + y * y + z * z)
        synchronized(lock) {
            if (!init) {
                lp = m; gravity[0] = x; gravity[1] = y; gravity[2] = z; init = true
            }
            // Écart à la moyenne glissante = vibration / mouvement
            sum += abs(m - lp)
            count++
            lp += (m - lp) * 0.02f
            gravity[0] += (x - gravity[0]) * 0.02f
            gravity[1] += (y - gravity[1]) * 0.02f
            gravity[2] += (z - gravity[2]) * 0.02f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Mouvement moyen depuis le dernier appel, en milli-g. */
    fun takeEpoch(): Float = synchronized(lock) {
        val v = if (count > 0) (sum / count / SensorManager.GRAVITY_EARTH * 1000.0).toFloat() else 0f
        sum = 0.0; count = 0
        v
    }

    fun gravity(): FloatArray = synchronized(lock) { gravity.copyOf() }

    companion object {
        /** Angle (degrés) entre deux orientations du téléphone. */
        fun angle(a: FloatArray, b: FloatArray): Float {
            val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (na < 1e-3f || nb < 1e-3f) return 0f
            val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(c).toDouble()).toFloat()
        }
    }
}
