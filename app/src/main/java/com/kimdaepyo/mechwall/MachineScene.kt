package com.kimdaepyo.mechwall

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 한 프레임을 그리는 장면.
 *
 * 레이어 (뒤 → 앞) / 기울기에 따른 이동량
 *  1. 배경 background.png         +5%
 *  2. 글로우 glow.png              +12%  (호흡 또는 심장박동 펄스, 투명도 70→100%)
 *  3. 기계 (코드로 그림)           −8%   (회전 링·기어·게이지·빛 입자)
 *  4. 전경 그림자                  전경의 55%만 따라감 + 아래로 살짝 → '떠 있는' 느낌
 *  5. 전경 foreground.png          −25%  (배경과 반대 방향)
 *  6. HUD 글자 + 스캔 라인
 *
 * res/drawable 에 PNG 가 없으면 각 레이어를 코드로 만든 기본 그림으로 대체한다.
 */
class MachineScene(private val res: Resources, private val packageName: String) {

    private companion object {
        const val BG_PCT = 0.05f
        const val GLOW_PCT = 0.12f
        const val MACHINE_PCT = -0.08f
        const val FG_PCT = -0.25f
        const val HUD_PCT = -0.12f
        const val SHADOW_FOLLOW = 0.55f

        /** 이동(px) = 기울기(-1..1) × 비율 × 깊이강도 × 화면짧은변 × SHIFT_SCALE */
        const val SHIFT_SCALE = 0.5f
        const val MAX_DEPTH = 2f

        const val PARTICLE_COUNT = 30
        const val BOOT_SECONDS = 1.6f
        const val PULSE_PERIOD = 4f
        const val SCAN_PERIOD = 5f
        const val SCAN_ACTIVE = 2.2f

        const val CYAN = 0xFF00E5FF.toInt()
        const val ORANGE = 0xFFFF8A3D.toInt()
    }

    // ---- 설정값 (엔진이 넣어줌) -------------------------------------------
    var depth = 1f
    var speed = 1f
    var particlesEnabled = true
    var heartbeat = true

    // ---- 크기 / 비트맵 ------------------------------------------------------
    private var w = 0
    private var h = 0
    private var minDim = 1f
    private var layerW = 0
    private var layerH = 0

    private var bg: Bitmap? = null
    private var glow: Bitmap? = null
    private var fg: Bitmap? = null
    private var shadow: Bitmap? = null
    private var shadowPad = 0
    private var proceduralCore = false

    // ---- 페인트 / 경로 (매 프레임 할당 안 하도록 미리 만듦) --------------------
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }
    private val shadowDst = RectF()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val scanPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val scanLinePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        color = 0x5500E5FF
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = CYAN
        letterSpacing = 0.12f
    }

    private var tickPath = Path()
    private var gearPath = Path()
    private val arcRect = RectF()
    private var scanBand = 1f

    // ---- 애니메이션 상태 -----------------------------------------------------
    private class Particle {
        var angle = 0f
        var radius = 0f
        var inward = 0f
        var size = 0f
    }

    private val particles = Array(PARTICLE_COUNT) { Particle() }
    private val rnd = Random(System.nanoTime())

    private var clock = 0f
    private var ringAngle1 = 0f
    private var ringAngle2 = 0f
    private var ringAngle3 = 0f
    private var bootTime = BOOT_SECONDS
    private var hudTimer = 1f
    private var hudCore = ""
    private var hudRpm = ""
    private var hudSync = ""

    // =========================================================================

    fun setSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == w && height == h && bg != null) return
        release()

        w = width
        h = height
        minDim = min(w, h).toFloat()
        // 배경이 최대로 움직여도 가장자리가 안 보이도록 여유(margin)를 둔 레이어 크기
        val margin = (BG_PCT * MAX_DEPTH * SHIFT_SCALE * minDim).toInt() + 2
        layerW = w + margin * 2
        layerH = h + margin * 2

        bg = loadLayer("background") ?: buildBackground()
        glow = loadLayer("glow") ?: buildGlow()
        val userFg = loadLayer("foreground")
        proceduralCore = userFg == null
        fg = userFg ?: buildCore()
        shadow = fg?.let { buildShadow(it) }

        tickPath = buildTicks(minDim * 0.42f)
        gearPath = buildGear(minDim * 0.275f, minDim * 0.295f, 36)
        scanBand = h * 0.10f
        scanPaint.shader = LinearGradient(
            0f, 0f, 0f, scanBand,
            intArrayOf(0x0000E5FF, 0x2600E5FF, 0x0000E5FF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val coreR = minDim * 0.085f * 1.6f
        corePaint.shader = RadialGradient(
            0f, 0f, coreR,
            intArrayOf(Color.WHITE, 0xCC00E5FF.toInt(), 0x0000E5FF),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        hudPaint.textSize = minDim * 0.03f
        particles.forEach { respawn(it, initial = true) }
    }

    fun release() {
        bg?.recycle(); bg = null
        glow?.recycle(); glow = null
        fg?.recycle(); fg = null
        shadow?.recycle(); shadow = null
    }

    /** 화면이 켜질 때 호출 → 링이 빠르게 돌다가 감속, 글로우 번쩍. */
    fun boot() {
        bootTime = 0f
    }

    // =========================================================================
    // 그리기
    // =========================================================================

    fun draw(c: Canvas, tiltX: Float, tiltY: Float, dt: Float) {
        if (w == 0 || bg == null) {
            c.drawColor(Color.BLACK)
            return
        }
        clock += dt
        if (bootTime < BOOT_SECONDS) bootTime += dt
        val boot = (bootTime / BOOT_SECONDS).coerceIn(0f, 1f)
        val appear = 1f - (1f - boot) * (1f - boot)                 // ease-out
        val spin = speed * (1f + 5f * (1f - boot) * (1f - boot))    // 부팅 땐 6배속 → 정속

        val unit = depth * SHIFT_SCALE * minDim
        val cx = w / 2f
        val cy = h / 2f
        val left = (w - layerW) / 2f
        val top = (h - layerH) / 2f

        // 1) 배경
        c.drawColor(Color.BLACK)
        bg?.let { c.drawBitmap(it, left + tiltX * BG_PCT * unit, top + tiltY * BG_PCT * unit, bitmapPaint) }

        // 2) 글로우 + 펄스 (70% ~ 100%), 부팅 순간엔 번쩍
        val pulse = pulse(clock)
        val flash = (1f - boot) * 0.6f
        glowPaint.alpha = (255 * (0.7f + 0.3f * pulse + flash).coerceAtMost(1f)).toInt()
        glow?.let { c.drawBitmap(it, left + tiltX * GLOW_PCT * unit, top + tiltY * GLOW_PCT * unit, glowPaint) }

        // 3) 기계 레이어
        ringAngle1 = (ringAngle1 + dt * spin * 360f / 24f) % 360f   // 바깥 눈금 링: 24초에 한 바퀴
        ringAngle2 = (ringAngle2 - dt * spin * 360f / 14f) % 360f   // 분절 링: 반대 방향 14초
        ringAngle3 = (ringAngle3 + dt * spin * 360f / 8f) % 360f    // 기어: 8초
        drawMachine(
            c,
            cx + tiltX * MACHINE_PCT * unit,
            cy + tiltY * MACHINE_PCT * unit,
            appear, pulse, dt, spin
        )

        // 4) 전경 그림자 — 전경보다 덜 움직여서 전경이 화면 위로 떠 보임
        shadow?.let {
            val scale = 2f   // 그림자는 절반 해상도로 저장돼 있음
            val sx = left + tiltX * FG_PCT * SHADOW_FOLLOW * unit - shadowPad * scale
            val sy = top + tiltY * FG_PCT * SHADOW_FOLLOW * unit - shadowPad * scale + minDim * 0.035f
            shadowDst.set(sx, sy, sx + it.width * scale, sy + it.height * scale)
            c.drawBitmap(it, null, shadowDst, shadowPaint)
        }

        // 5) 전경 (배경과 반대 방향)
        val fgX = tiltX * FG_PCT * unit
        val fgY = tiltY * FG_PCT * unit
        fg?.let { c.drawBitmap(it, left + fgX, top + fgY, bitmapPaint) }
        if (proceduralCore) drawCoreLight(c, cx + fgX, cy + fgY, pulse)

        // 6) HUD + 스캔 라인
        drawHud(c, cx + tiltX * HUD_PCT * unit, cy + tiltY * HUD_PCT * unit, boot, dt, spin)
        drawScan(c)
    }

    /** 0..1 펄스. heartbeat=true 면 '두-근' 두 번 뛰고 쉼, false 면 부드러운 호흡. 4초 주기. */
    private fun pulse(t: Float): Float {
        val p = t % PULSE_PERIOD
        val breath = 0.5f - 0.5f * cos(2f * PI.toFloat() * p / PULSE_PERIOD)
        if (!heartbeat) return breath
        val d1 = (p - 0.15f) / 0.09f
        val d2 = (p - 0.45f) / 0.10f
        val beat1 = exp(-d1 * d1)
        val beat2 = 0.75f * exp(-d2 * d2)
        return max(max(beat1, beat2), 0.25f * breath)
    }

    private fun drawMachine(
        c: Canvas, x: Float, y: Float,
        appear: Float, pulse: Float, dt: Float, spin: Float
    ) {
        c.save()
        c.translate(x, y)
        val s = 0.9f + 0.1f * appear
        c.scale(s, s)

        // 바깥 눈금 링
        ringPaint.color = CYAN
        ringPaint.strokeCap = Paint.Cap.BUTT
        ringPaint.strokeWidth = max(1f, minDim * 0.002f)
        ringPaint.alpha = (110 * appear).toInt()
        c.drawCircle(0f, 0f, minDim * 0.42f, ringPaint)
        c.save()
        c.rotate(ringAngle1)
        c.drawPath(tickPath, ringPaint)
        c.restore()

        // 분절 링 (반대로 회전) + 주황 포인트
        val r2 = minDim * 0.36f
        arcRect.set(-r2, -r2, r2, r2)
        ringPaint.strokeWidth = minDim * 0.012f
        ringPaint.alpha = (140 * appear).toInt()
        c.save()
        c.rotate(ringAngle2)
        for (i in 0 until 6) c.drawArc(arcRect, i * 60f + 4f, 40f, false, ringPaint)
        ringPaint.color = ORANGE
        ringPaint.alpha = (210 * appear).toInt()
        c.drawArc(arcRect, 48f, 10f, false, ringPaint)
        c.restore()

        // 기어 — 펄스에 맞춰 밝기 변화
        ringPaint.color = CYAN
        ringPaint.strokeWidth = max(1f, minDim * 0.003f)
        ringPaint.alpha = ((90 + 90 * pulse) * appear).toInt()
        c.save()
        c.rotate(ringAngle3)
        c.drawPath(gearPath, ringPaint)
        c.restore()

        // 안쪽 게이지 아크 (출력이 오르내리는 느낌)
        val r4 = minDim * 0.235f
        arcRect.set(-r4, -r4, r4, r4)
        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.strokeWidth = minDim * 0.006f
        ringPaint.alpha = (160 * appear).toInt()
        val sweep = 200f + 120f * sin(clock * 0.7f)
        c.drawArc(arcRect, -90f, sweep, false, ringPaint)

        if (particlesEnabled) drawParticles(c, dt, spin, appear)
        c.restore()
    }

    private fun respawn(p: Particle, initial: Boolean) {
        val minR = minDim * 0.12f
        val maxR = minDim * 0.62f
        p.angle = rnd.nextFloat() * 2f * PI.toFloat()
        p.radius = if (initial) minR + rnd.nextFloat() * (maxR - minR)
        else maxR - rnd.nextFloat() * minDim * 0.08f
        p.inward = minDim * (0.035f + rnd.nextFloat() * 0.05f)
        p.size = minDim * (0.0025f + rnd.nextFloat() * 0.003f)
    }

    /** 빛 입자가 나선을 그리며 코어로 빨려 들어감. 가까울수록 빨리 돎. */
    private fun drawParticles(c: Canvas, dt: Float, spin: Float, appear: Float) {
        val minR = minDim * 0.12f
        val maxR = minDim * 0.62f
        for (p in particles) {
            p.radius -= p.inward * dt * spin
            p.angle += dt * spin * 0.25f * (minDim * 0.3f / max(p.radius, minR))
            if (p.radius < minR) {
                respawn(p, initial = false)
                continue
            }
            val fadeIn = ((maxR - p.radius) / (minDim * 0.08f)).coerceIn(0f, 1f)
            val fadeOut = ((p.radius - minR) / (minDim * 0.06f)).coerceIn(0f, 1f)
            particlePaint.alpha = (220 * fadeIn * fadeOut * appear).toInt()
            c.drawCircle(cos(p.angle) * p.radius, sin(p.angle) * p.radius, p.size, particlePaint)
        }
    }

    /** 기본(코드로 그린) 코어일 때만: 렌즈 중심이 펄스에 맞춰 빛남. */
    private fun drawCoreLight(c: Canvas, x: Float, y: Float, pulse: Float) {
        corePaint.alpha = (120 + 135 * pulse).toInt()
        c.save()
        c.translate(x, y)
        c.drawCircle(0f, 0f, minDim * 0.085f * 1.6f, corePaint)
        c.restore()
    }

    private fun drawHud(c: Canvas, cx: Float, cy: Float, boot: Float, dt: Float, spin: Float) {
        hudTimer += dt
        if (hudTimer > 0.25f) {
            hudTimer = 0f
            hudCore = "CORE %.1f%%".format(97.5f + rnd.nextFloat() * 2f)
            hudRpm = "RPM %04d".format((3200 * spin + rnd.nextInt(-30, 30)).toInt().coerceIn(0, 9999))
            val filled = ((clock * 1.5f).toInt() % 6)
            hudSync = "SYNC [" + "#".repeat(filled) + "-".repeat(5 - minOf(filled, 5)) + "]"
        }
        val status = if (boot < 1f) "BOOT" + ".".repeat(((clock * 6f).toInt() % 4)) else "SYS//ONLINE"
        val lineH = hudPaint.textSize * 1.5f
        val pad = minDim * 0.07f
        val off = minDim * 0.50f

        // 링 오른쪽 위
        hudPaint.textAlign = Paint.Align.RIGHT
        hudPaint.color = CYAN
        hudPaint.alpha = 170
        val rx = w - pad + (cx - w / 2f)
        val ry = cy - off
        c.drawText(status, rx, ry - lineH, hudPaint)
        c.drawText(hudCore, rx, ry, hudPaint)

        // 링 왼쪽 아래
        hudPaint.textAlign = Paint.Align.LEFT
        val lx = pad + (cx - w / 2f)
        val ly = cy + off + hudPaint.textSize
        c.drawText(hudRpm, lx, ly, hudPaint)
        hudPaint.color = ORANGE
        hudPaint.alpha = 200
        c.drawText(hudSync, lx, ly + lineH, hudPaint)
    }

    /** 몇 초마다 위→아래로 빛줄기가 훑고 지나감. */
    private fun drawScan(c: Canvas) {
        val p = clock % SCAN_PERIOD
        if (p > SCAN_ACTIVE) return
        val y = -scanBand + (h + scanBand * 2f) * (p / SCAN_ACTIVE)
        c.save()
        c.translate(0f, y)
        c.drawRect(0f, 0f, w.toFloat(), scanBand, scanPaint)
        c.drawRect(0f, scanBand / 2f - 1f, w.toFloat(), scanBand / 2f + 1f, scanLinePaint)
        c.restore()
    }

    // =========================================================================
    // 레이어 준비
    // =========================================================================

    /** res/drawable/<name>.png 를 화면(+여유)을 꽉 채우도록 잘라 맞춤. 없으면 null. */
    @Suppress("DiscouragedApi")
    private fun loadLayer(name: String): Bitmap? {
        val id = res.getIdentifier(name, "drawable", packageName)
        if (id == 0) return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeResource(res, id, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= layerW && bounds.outHeight / (sample * 2) >= layerH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeResource(res, id, opts) ?: return null

        val out = Bitmap.createBitmap(layerW, layerH, Bitmap.Config.ARGB_8888)
        val s = max(layerW / raw.width.toFloat(), layerH / raw.height.toFloat())
        val dw = raw.width * s
        val dh = raw.height * s
        val dst = RectF((layerW - dw) / 2f, (layerH - dh) / 2f, (layerW + dw) / 2f, (layerH + dh) / 2f)
        Canvas(out).drawBitmap(raw, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        raw.recycle()
        return out
    }

    /** 기본 배경: 어두운 금속 + 희미한 그리드 + 패널 이음새 + 리벳. */
    private fun buildBackground(): Bitmap {
        val b = Bitmap.createBitmap(layerW, layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val cx = layerW / 2f
        val cy = layerH / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = RadialGradient(
            cx, cy, max(layerW, layerH) * 0.75f,
            intArrayOf(0xFF17212B.toInt(), 0xFF0A0F15.toInt(), 0xFF030508.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, layerW.toFloat(), layerH.toFloat(), p)
        p.shader = null

        // 그리드
        val step = minDim / 14f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x1200E5FF
        var x = cx % step
        while (x < layerW) { c.drawLine(x, 0f, x, layerH.toFloat(), p); x += step }
        var y = cy % step
        while (y < layerH) { c.drawLine(0f, y, layerW.toFloat(), y, p); y += step }

        // 패널 이음새 + 리벳
        val r = Random(7)
        val rivet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF }
        p.color = 0x1EFFFFFF
        p.strokeWidth = 1.5f
        repeat(22) {
            val px = (cx % step) + step * r.nextInt(0, (layerW / step).toInt() + 1)
            val py = (cy % step) + step * r.nextInt(0, (layerH / step).toInt() + 1)
            val pw = step * r.nextInt(2, 6)
            val ph = step * r.nextInt(1, 4)
            c.drawRect(px, py, px + pw, py + ph, p)
            val rr = minDim * 0.004f
            c.drawCircle(px + rr * 3, py + rr * 3, rr, rivet)
            c.drawCircle(px + pw - rr * 3, py + ph - rr * 3, rr, rivet)
        }

        // 비네트
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy, max(layerW, layerH) * 0.7f,
            intArrayOf(0x00000000, 0x00000000, 0xB0000000.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, layerW.toFloat(), layerH.toFloat(), p)
        return b
    }

    /** 기본 글로우: 가운데 시안 빛 + 오른쪽 아래 은은한 주황. */
    private fun buildGlow(): Bitmap {
        val b = Bitmap.createBitmap(layerW, layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val cx = layerW / 2f
        val cy = layerH / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = RadialGradient(
            cx, cy, minDim * 0.6f,
            intArrayOf(0xA000E5FF.toInt(), 0x3000B8D4, 0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, minDim * 0.6f, p)

        val ox = cx + minDim * 0.3f
        val oy = cy + minDim * 0.45f
        p.shader = RadialGradient(
            ox, oy, minDim * 0.35f,
            intArrayOf(0x40FF8A3D, 0x00000000),
            null,
            Shader.TileMode.CLAMP
        )
        c.drawCircle(ox, oy, minDim * 0.35f, p)
        return b
    }

    /** 기본 전경: 육각형 금속 코어 플레이트 + 렌즈 + 볼트. */
    private fun buildCore(): Bitmap {
        val b = Bitmap.createBitmap(layerW, layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val cx = layerW / 2f
        val cy = layerH / 2f
        val r = minDim * 0.17f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val hex = Path()
        for (i in 0 until 6) {
            val a = PI / 6 + i * PI / 3
            val x = cx + (cos(a) * r).toFloat()
            val y = cy + (sin(a) * r).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()

        p.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(0xFF3E4A58.toInt(), 0xFF161C24.toInt(), 0xFF2C3642.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        c.drawPath(hex, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = minDim * 0.004f
        p.color = 0xAA00E5FF.toInt()
        c.drawPath(hex, p)

        p.strokeWidth = max(1f, minDim * 0.002f)
        p.color = 0x44FFFFFF
        c.drawCircle(cx, cy, r * 0.72f, p)

        p.style = Paint.Style.FILL
        p.color = 0xFF8A98A8.toInt()
        for (i in 0 until 6) {
            val a = PI / 6 + i * PI / 3
            c.drawCircle(
                cx + (cos(a) * r * 0.84f).toFloat(),
                cy + (sin(a) * r * 0.84f).toFloat(),
                minDim * 0.008f, p
            )
        }

        p.shader = RadialGradient(
            cx, cy, r * 0.55f,
            intArrayOf(0xFF0C3346.toInt(), 0xFF04121A.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 0.55f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = minDim * 0.003f
        p.color = CYAN
        c.drawCircle(cx, cy, r * 0.55f, p)
        return b
    }

    /** 전경의 알파(모양)를 흐리게 만든 그림자. 메모리 절약을 위해 절반 해상도. */
    private fun buildShadow(src: Bitmap): Bitmap? {
        val sw = src.width / 2
        val sh = src.height / 2
        if (sw <= 0 || sh <= 0) return null
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val alpha = small.extractAlpha()
        small.recycle()

        val blur = max(4f, minDim * 0.02f)
        shadowPad = (blur * 2).toInt()
        val out = Bitmap.createBitmap(sw + shadowPad * 2, sh + shadowPad * 2, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(out).drawBitmap(alpha, shadowPad.toFloat(), shadowPad.toFloat(), p)
        alpha.recycle()
        return out
    }

    private fun buildTicks(r: Float): Path {
        val path = Path()
        for (i in 0 until 120) {
            val a = i * 3.0 * PI / 180.0
            val len = when {
                i % 10 == 0 -> minDim * 0.03f
                i % 5 == 0 -> minDim * 0.018f
                else -> minDim * 0.01f
            }
            val cs = cos(a).toFloat()
            val sn = sin(a).toFloat()
            path.moveTo(cs * r, sn * r)
            path.lineTo(cs * (r - len), sn * (r - len))
        }
        return path
    }

    private fun buildGear(rIn: Float, rOut: Float, teeth: Int): Path {
        val path = Path()
        val step = 2.0 * PI / teeth
        var first = true
        fun pt(r: Float, a: Double) {
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        for (i in 0 until teeth) {
            val a0 = i * step
            pt(rIn, a0)
            pt(rOut, a0 + step * 0.2)
            pt(rOut, a0 + step * 0.5)
            pt(rIn, a0 + step * 0.7)
        }
        path.close()
        path.addCircle(0f, 0f, rIn * 0.93f, Path.Direction.CW)
        return path
    }
}
