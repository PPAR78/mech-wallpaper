package com.kimdaepyo.mechwall

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign

/**
 * TYPE_ROTATION_VECTOR 로 기울기를 읽어 -1..1 범위로 돌려준다.
 * - 회전 행렬의 3번째 행(월드 '위' 방향을 기기 좌표로 본 값) 사용 → 세워 들어도 튀지 않음
 * - 중립점은 아주 천천히(약 40초) 현재 자세를 따라감
 * - 작은 기울기도 잘 느껴지도록 감도 곡선 적용
 * - update(dt) 에서 시간 기반 저역통과 필터로 떨림 제거
 */
class TiltSensor(context: Context) : SensorEventListener {

    private companion object {
        const val RANGE = 0.22f          // 약 13° 기울이면 최대값
        const val RECENTER = 0.0005f     // 중립점 따라오는 속도 (이벤트당, ~40초)
        const val SMOOTH_TAU = 0.09f     // 저역통과 시간상수(초)
        const val CURVE = 0.8f           // 1 미만 → 작은 기울기 증폭
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val vec = FloatArray(4)

    @Volatile private var rawX = 0f
    @Volatile private var rawY = 0f
    private var baseX = 0f
    private var baseY = 0f
    private var hasBase = false

    var x = 0f
        private set
    var y = 0f
        private set

    /** 센서 이벤트를 렌더 스레드(handler)로 받는다. */
    fun start(handler: Handler) {
        hasBase = false
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun update(dt: Float) {
        val k = 1f - exp(-dt / SMOOTH_TAU)
        x += (rawX - x) * k
        y += (rawY - y) * k
    }

    override fun onSensorChanged(event: SensorEvent) {
        val n = minOf(event.values.size, 4)
        for (i in 0 until n) vec[i] = event.values[i]
        for (i in n until 4) vec[i] = 0f
        SensorManager.getRotationMatrixFromVector(rotation, vec)

        val gx = rotation[6]
        val gy = rotation[7]
        if (!hasBase) {
            baseX = gx; baseY = gy; hasBase = true
        }
        baseX += (gx - baseX) * RECENTER
        baseY += (gy - baseY) * RECENTER

        rawX = shape((gx - baseX) / RANGE)
        rawY = shape((gy - baseY) / RANGE)
    }

    private fun shape(v: Float): Float {
        val c = v.coerceIn(-1f, 1f)
        return sign(c) * abs(c).pow(CURVE)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
