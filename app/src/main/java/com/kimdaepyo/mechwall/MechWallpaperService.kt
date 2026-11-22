package com.kimdaepyo.mechwall

import android.content.SharedPreferences
import android.graphics.Canvas
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder

/**
 * 라이브 월페이퍼 서비스.
 * - 보일 때만 Choreographer(화면 주사율 동기)로 그리고, 60fps 근처로 제한한다.
 * - 안 보이면(onVisibilityChanged(false)) 센서 해제 + 프레임 콜백 중단 → 배터리 절약.
 */
class MechWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MechEngine()

    inner class MechEngine : Engine(),
        Choreographer.FrameCallback,
        SharedPreferences.OnSharedPreferenceChangeListener {

        /**
         * 120Hz 화면에서는 한 프레임 건너 그려 60fps, 60Hz 에서는 매 프레임.
         * (90Hz 화면은 매 프레임 그려짐)
         */
        private val minFrameNs = 10_000_000L

        private val scene = MachineScene(resources, packageName)
        private val tilt = TiltSensor(this@MechWallpaperService)
        private val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        private var visible = false
        private var lastFrameNs = 0L
        private var useHardwareCanvas = true

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            applyPrefs()
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            stopRendering()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            scene.release()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            scene.setSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            stopRendering()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                tilt.start()
                scene.boot()            // 화면 켤 때마다 '기동' 연출
                lastFrameNs = 0L
                Choreographer.getInstance().removeFrameCallback(this)
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                stopRendering()
            }
        }

        private fun stopRendering() {
            tilt.stop()
            Choreographer.getInstance().removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            Choreographer.getInstance().postFrameCallback(this)

            if (lastFrameNs != 0L && frameTimeNanos - lastFrameNs < minFrameNs) return
            val dt = if (lastFrameNs == 0L) 1f / 60f
            else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceAtMost(0.1f)
            lastFrameNs = frameTimeNanos

            tilt.update(dt)
            drawFrame(dt)
        }

        private fun drawFrame(dt: Float) {
            val holder = surfaceHolder
            val canvas: Canvas = lockCanvas(holder) ?: return
            try {
                scene.draw(canvas, tilt.x, tilt.y, dt)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: IllegalStateException) {
                    // 표면이 이미 사라진 경우 — 다음 프레임에서 정리됨
                }
            }
        }

        private fun lockCanvas(holder: SurfaceHolder): Canvas? {
            if (useHardwareCanvas) {
                try {
                    return holder.lockHardwareCanvas()
                } catch (_: Exception) {
                    useHardwareCanvas = false   // 일부 기기 대비: 소프트웨어 캔버스로 대체
                }
            }
            return try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            applyPrefs()
        }

        private fun applyPrefs() {
            scene.depth = prefs.getInt(Prefs.KEY_DEPTH, Prefs.DEF_DEPTH) / 100f
            scene.speed = prefs.getInt(Prefs.KEY_SPEED, Prefs.DEF_SPEED) / 100f
            scene.particlesEnabled = prefs.getBoolean(Prefs.KEY_PARTICLES, true)
            scene.heartbeat = prefs.getBoolean(Prefs.KEY_HEARTBEAT, true)
        }
    }
}
