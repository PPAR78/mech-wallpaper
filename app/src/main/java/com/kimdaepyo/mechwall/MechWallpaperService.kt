package com.kimdaepyo.mechwall

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 라이브 월페이퍼 서비스.
 *
 * - 그리기·센서·무거운 준비 작업은 전부 전용 렌더 스레드에서 → 메인 스레드가 막혀 멈추는 일 방지
 * - 보일 때만 화면 주사율에 맞춰(최대 ~60fps) 그림
 * - 안 보이면 센서 해제 + 프레임 중단 → 배터리 절약
 * - 그리다가 예외가 나도 서비스가 죽지 않고 다음 프레임 계속
 */
class MechWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MechEngine()

    inner class MechEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val minFrameNs = 10_000_000L

        private val thread = HandlerThread("mech-render").apply { start() }
        private val handler = Handler(thread.looper)
        private var choreographer: Choreographer? = null

        private val scene = MachineScene(resources, packageName)
        private val tilt = TiltSensor(this@MechWallpaperService)
        private val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        // 아래 값들은 렌더 스레드에서만 읽고 씀
        private var visible = false
        private var hasSurface = false
        private var lastFrameNs = 0L
        private var useHardwareCanvas = true

        private val frameCallback = Choreographer.FrameCallback { t -> onFrame(t) }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            handler.post {
                choreographer = Choreographer.getInstance()
                applyPrefs()
            }
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            runOnRenderThread {
                stopRendering()
                scene.release()
            }
            thread.quitSafely()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            handler.post {
                hasSurface = true
                scene.setSize(width, height)
                if (visible) startRendering()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // 표면이 사라지기 전에 렌더 스레드가 확실히 손을 떼도록 기다림
            runOnRenderThread {
                hasSurface = false
                stopRendering()
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            handler.post {
                visible = isVisible
                if (isVisible) {
                    scene.boot()
                    startRendering()
                } else {
                    stopRendering()
                }
            }
        }

        private fun startRendering() {
            if (!hasSurface) return
            tilt.start(handler)
            lastFrameNs = 0L
            choreographer?.removeFrameCallback(frameCallback)
            choreographer?.postFrameCallback(frameCallback)
        }

        private fun stopRendering() {
            tilt.stop()
            choreographer?.removeFrameCallback(frameCallback)
        }

        private fun onFrame(frameTimeNanos: Long) {
            if (!visible || !hasSurface) return
            choreographer?.postFrameCallback(frameCallback)

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
            } catch (e: Exception) {
                Log.e("MechWallpaper", "draw failed", e)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }

        private fun lockCanvas(holder: SurfaceHolder): Canvas? {
            if (useHardwareCanvas) {
                try {
                    return holder.lockHardwareCanvas()
                } catch (_: Exception) {
                    useHardwareCanvas = false
                }
            }
            return try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            }
        }

        /** 렌더 스레드에서 실행하고 끝날 때까지(최대 1초) 기다림. */
        private fun runOnRenderThread(block: () -> Unit) {
            val latch = CountDownLatch(1)
            val posted = handler.post {
                try { block() } finally { latch.countDown() }
            }
            if (posted) latch.await(1, TimeUnit.SECONDS)
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            handler.post { applyPrefs() }
        }

        private fun applyPrefs() {
            scene.depth = prefs.getInt(Prefs.KEY_DEPTH, Prefs.DEF_DEPTH) / 100f
            scene.speed = prefs.getInt(Prefs.KEY_SPEED, Prefs.DEF_SPEED) / 100f
            scene.particlesEnabled = prefs.getBoolean(Prefs.KEY_PARTICLES, true)
            scene.heartbeat = prefs.getBoolean(Prefs.KEY_HEARTBEAT, true)
        }
    }
}
