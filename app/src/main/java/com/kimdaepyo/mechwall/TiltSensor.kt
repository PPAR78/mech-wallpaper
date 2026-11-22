package com.kimdaepyo.mechwall

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.exp

/**
 * TYPE_ROTATION_VECTOR 로 기울기를 읽어 -1..1 범위로 돌려준다.
 *
 * - 회전 행렬의 3번째 행(월드 '위' 방향을 기기 좌표로 본 값)을 쓰기 때문에
 *   폰을 세워 들어도 짐벌락으로 튀지 않는다.
 * - 처음 켰을 때의 자세를 '중립'으로 잡고, 중립점은 아주 천천히 현재 자세를 따라가서
 *   누워서 보든 서서 보든 자연스럽게 다시 가운데로 돌아온다.
 * - update(dt) 에서 시간 기반 저역통과 필터로 부드럽게 만든다 (떨림 제거).
 */
class TiltSensor(context: Context) : SensorEventListener {

    private companion object {
        const val RANGE = 0.35f          // 이 정도 기울이면(약 20°) 최대값 1
        const val RECENTER = 0.0015f     // 중립점이 따라오는 속도 (이벤트당)
        const val SMOOTH_TAU = 0.12f     // 저역통과 시간상수(초). 클수록 더 부드럽고 느림
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val vec = FloatArray(4)

    private var rawX = 0f
    private var rawY = 0f
    private var baseX = 0f
    private var baseY = 0f
    private var hasBase = false

    /** 부드럽게 처리된 기울기 (-1..1) */
    var x = 0f
        private set
    var y = 0f
        private set

    fun start() {
        hasBase = false
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** 매 프레임 호출. dt 는 초 단위. */
    fun update(dt: Float) {
        val k = 1f - exp(-dt / SMOOTH_TAU)
        x += (rawX - x) * k
        y += (rawY - y) * k
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 일부 기기는 5개 이상의 값을 보내서 getRotationMatrixFromVector 가 예외를 던짐 → 앞 4개만 사용
        val n = minOf(event.values.size, 4)
        for (i in 0 until n) vec[i] = event.values[i]
        for (i in n until 4) vec[i] = 0f
        SensorManager.getRotationMatrixFromVector(rotation, vec)

        val gx = rotation[6]
        val gy = rotation[7]
        if (!hasBase) {
            baseX = gx
            baseY = gy
            hasBase = true
        }
        baseX += (gx - baseX) * RECENTER
        baseY += (gy - baseY) * RECENTER

        rawX = ((gx - baseX) / RANGE).coerceIn(-1f, 1f)
        rawY = ((gy - baseY) / RANGE).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
