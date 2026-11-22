package com.kimdaepyo.mechwall

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
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
 * 네온 회로 박스 장면.
 *
 * 화면을 '투명 케이스의 앞유리'로 보고, 그 안쪽으로 깊이 들어가는 박스를 원근으로 그린다.
 *   - 뒷벽 / 네 옆벽: 회로 패턴 텍스처를 원근 변환으로 붙임
 *   - 깊이별 네온 테두리(터널 링), 모서리 네온, 안쪽에서 앞으로 밀려오는 에너지 링
 *   - 벽을 타고 앞으로 달려오는 데이터 패킷
 *   - 박스 안쪽 깊숙한 곳의 회전 리액터
 *   - 깊이가 다른 네온 유리 큐브 6개 (각자 그래프/파형/게이지 애니메이션)
 *   - 유리 반사광, HUD, 스캔 라인
 * 기울이면 소실점이 움직여서, 가까운 것과 먼 것이 서로 다르게 움직인다 → 진짜 3D 깊이감.
 *
 * res/drawable 에 background.png / foreground.png 가 있으면 '사진 모드'로 전환:
 *   배경 +5%, 글로우 +12% (펄스), 전경 -25% + 그림자 (원래 요청 사양).
 */
class MachineScene(private val res: Resources, private val packageName: String) {

    private companion object {
        // 사진 모드 레이어 이동량 (짧은 변 대비, 최대 기울기·깊이 1x 기준)
        const val BG_PCT = 0.05f
        const val GLOW_PCT = 0.12f
        const val FG_PCT = -0.25f
        const val SHADOW_FOLLOW = 0.3f
        const val MAX_DEPTH = 2f

        // 네온 박스
        const val BOX_DEPTH = 1.15f      // 박스 깊이 (짧은 변 배수)
        const val FOCAL = 1.0f           // 초점거리 (짧은 변 배수). 작을수록 원근 강함
        const val PARALLAX = 0.24f       // 깊이 1px 당 기울기 이동량
        const val RINGS = 7
        const val PACKETS = 22
        const val BOOT_SECONDS = 1.6f
        const val PULSE_PERIOD = 4f
        const val SCAN_PERIOD = 5f
        const val SCAN_ACTIVE = 2.2f

        const val PINK = 0xFFFF2E88.toInt()
        const val ORANGE = 0xFFFF8A00.toInt()
        const val CYAN = 0xFF00E5FF.toInt()
        const val LIME = 0xFF39FF14.toInt()
        const val VIOLET = 0xFF9D4DFF.toInt()
        const val YELLOW = 0xFFFFD60A.toInt()

        const val GLYPH_BARS = 0
        const val GLYPH_WAVE = 1
        const val GLYPH_RING = 2
        const val GLYPH_GRID = 3
    }

    // ---- 설정 ----------------------------------------------------------------
    var depth = 1f
    var speed = 1f
    var particlesEnabled = true
    var heartbeat = true

    // ---- 크기 -----------------------------------------------------------------
    private var w = 0
    private var h = 0
    private var minDim = 1f
    private var u = 1f          // 선 굵기 단위
    private var cx = 0f
    private var cy = 0f
    private var halfW = 0f
    private var halfH = 0f
    private var boxD = 1f
    private var focal = 1f

    // ---- 사진 모드 비트맵 -------------------------------------------------------
    private var photoMode = false
    private var layerW = 0
    private var layerH = 0
    private var bg: Bitmap? = null
    private var glow: Bitmap? = null
    private var fg: Bitmap? = null
    private var fgShadow: Bitmap? = null
    private var shadowPad = 0

    // ---- 박스 모드 텍스처 -------------------------------------------------------
    private var wallTex: Bitmap? = null
    private var backTex: Bitmap? = null

    // ---- 페인트 / 재사용 객체 ---------------------------------------------------
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val neonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = add
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val addFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 160 }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private val glarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanPaint = Paint().apply { xfermode = add }
    private val scanLinePaint = Paint().apply { xfermode = add; color = 0x5500E5FF }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }

    private val path = Path()
    private val mtx = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val rect = RectF()
    private val shadowDst = RectF()
    private var tickPath = Path()
    private var scanBand = 1f

    // ---- 장면 요소 --------------------------------------------------------------
    private class Panel(
        val fx: Float, val fy: Float, val fz: Float, val size: Float,
        val color: Int, val glyph: Int, val phase: Float
    )

    private val panels = arrayOf(
        Panel(-0.56f, -0.60f, 0.40f, 0.105f, ORANGE, GLYPH_WAVE, 0.0f),
        Panel(0.54f, -0.56f, 0.30f, 0.11f, PINK, GLYPH_BARS, 1.1f),
        Panel(-0.44f, 0.64f, 0.34f, 0.10f, VIOLET, GLYPH_WAVE, 2.3f),
        Panel(0.60f, 0.12f, 0.24f, 0.115f, CYAN, GLYPH_GRID, 3.1f),
        Panel(-0.62f, 0.06f, 0.18f, 0.12f, LIME, GLYPH_RING, 4.2f),
        Panel(0.46f, 0.66f, 0.12f, 0.125f, YELLOW, GLYPH_BARS, 5.0f),
    ).sortedByDescending { it.fz }   // 먼 것부터 그림

    private class Packet {
        var lane = 0
        var z = 0f
        var speed = 0f
        var color = 0
    }

    private val packets = Array(PACKETS) { Packet() }
    private val laneX = FloatArray(12)
    private val laneY = FloatArray(12)
    private val palette = intArrayOf(CYAN, PINK, ORANGE, LIME, VIOLET, YELLOW)
    private val backTraces = ArrayList<FloatArray>()
    private val rnd = Random(System.nanoTime())

    private var clock = 0f
    private var bootTime = BOOT_SECONDS
    private var tx = 0f
    private var ty = 0f
    private var ringAngle1 = 0f
    private var ringAngle2 = 0f
    private var hudTimer = 1f
    private var hudCore = ""
    private var hudRpm = ""
    private var hudSync = ""

    // =========================================================================

    fun setSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == w && height == h && (wallTex != null || photoMode)) return
        release()

        w = width
        h = height
        minDim = min(w, h).toFloat()
        u = max(1f, minDim / 400f)
        cx = w / 2f
        cy = h / 2f
        halfW = w / 2f
        halfH = h / 2f
        boxD = BOX_DEPTH * minDim
        focal = FOCAL * minDim

        // 사진 모드 확인
        val margin = (BG_PCT * MAX_DEPTH * minDim).toInt() + 2
        layerW = w + margin * 2
        layerH = h + margin * 2
        bg = loadLayer("background")
        fg = loadLayer("foreground")
        photoMode = bg != null || fg != null
        if (photoMode) {
            glow = loadLayer("glow")
            fg?.let { fgShadow = buildShadow(it) }
        } else {
            wallTex = buildCircuit(384, 768, 11, 1f)
            backTex = buildCircuit(512, 512, 23, 1.4f)
            buildBackTraces()
            buildLanes()
            packets.forEach { respawn(it, initial = true) }
        }

        tickPath = buildTicks(minDim * 0.36f)
        scanBand = h * 0.10f
        scanPaint.shader = LinearGradient(
            0f, 0f, 0f, scanBand,
            intArrayOf(0x0000E5FF, 0x2200E5FF, 0x0000E5FF), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        glarePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0x00FFFFFF, 0x12FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x0AFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.18f, 0.3f, 0.62f, 0.7f, 0.8f),
            Shader.TileMode.CLAMP
        )
        haloPaint.shader = RadialGradient(
            0f, 0f, minDim * 0.55f,
            intArrayOf(0x66FF2E88, 0x229D4DFF, 0x00000000), floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        hudPaint.textSize = minDim * 0.028f
    }

    /** 비트맵은 recycle 하지 않고 참조만 끊음 (GPU 가 아직 쓰는 중일 수 있어서 안전하게). */
    fun release() {
        bg = null; glow = null; fg = null; fgShadow = null
        wallTex = null; backTex = null
    }

    fun boot() {
        bootTime = 0f
    }

    // =========================================================================
    // 프레임
    // =========================================================================

    fun draw(c: Canvas, tiltX: Float, tiltY: Float, dt: Float) {
        if (w == 0) {
            c.drawColor(Color.BLACK)
            return
        }
        clock += dt
        if (bootTime < BOOT_SECONDS) bootTime += dt
        val boot = (bootTime / BOOT_SECONDS).coerceIn(0f, 1f)
        val spin = speed * (1f + 5f * (1f - boot) * (1f - boot))
        val pulse = pulse(clock)
        tx = tiltX * depth
        ty = tiltY * depth
        ringAngle1 = (ringAngle1 + dt * spin * 360f / 20f) % 360f
        ringAngle2 = (ringAngle2 - dt * spin * 360f / 11f) % 360f

        if (photoMode) drawPhoto(c, tiltX, tiltY, pulse, boot)
        else drawBox(c, pulse, boot, spin, dt)

        drawHud(c, boot, dt, spin)
        drawGlare(c)
        drawScan(c)
    }

    // ---- 원근 투영 -----------------------------------------------------------
    private fun sc(z: Float) = focal / (focal + z)
    private fun px(x: Float, z: Float) = cx + x * sc(z) + tx * z * PARALLAX
    private fun py(y: Float, z: Float) = cy + y * sc(z) + ty * z * PARALLAX

    private fun pulse(t: Float): Float {
        val p = t % PULSE_PERIOD
        val breath = 0.5f - 0.5f * cos(2f * PI.toFloat() * p / PULSE_PERIOD)
        if (!heartbeat) return breath
        val d1 = (p - 0.15f) / 0.09f
        val d2 = (p - 0.45f) / 0.10f
        return max(max(exp(-d1 * d1), 0.75f * exp(-d2 * d2)), 0.25f * breath)
    }

    // ---- 네온 그리기 ---------------------------------------------------------
    private fun neon(c: Canvas, p: Path, color: Int, width: Float, a: Float) {
        if (a <= 0.01f) return
        neonPaint.color = color
        neonPaint.strokeWidth = width * 5f
        neonPaint.alpha = (24 * a).toInt().coerceIn(0, 255)
        c.drawPath(p, neonPaint)
        neonPaint.strokeWidth = width * 2.4f
        neonPaint.alpha = (64 * a).toInt().coerceIn(0, 255)
        c.drawPath(p, neonPaint)
        neonPaint.strokeWidth = width
        neonPaint.alpha = (230 * a).toInt().coerceIn(0, 255)
        c.drawPath(p, neonPaint)
        neonPaint.color = Color.WHITE
        neonPaint.strokeWidth = width * 0.4f
        neonPaint.alpha = (150 * a).toInt().coerceIn(0, 255)
        c.drawPath(p, neonPaint)
    }

    private fun rectPath(l: Float, t: Float, r: Float, b: Float) {
        path.reset()
        path.moveTo(l, t); path.lineTo(r, t); path.lineTo(r, b); path.lineTo(l, b); path.close()
    }

    private fun linePath(x0: Float, y0: Float, x1: Float, y1: Float) {
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1)
    }

    // =========================================================================
    // 네온 박스 모드
    // =========================================================================

    private fun drawBox(c: Canvas, pulse: Float, boot: Float, spin: Float, dt: Float) {
        c.drawColor(0xFF05030B.toInt())
        val zb = boxD
        val bl = px(-halfW, zb)
        val bt = py(-halfH, zb)
        val br = px(halfW, zb)
        val bb = py(halfH, zb)

        // 1) 뒷벽
        backTex?.let {
            rect.set(bl, bt, br, bb)
            bmpPaint.alpha = 255
            c.drawBitmap(it, null, rect, bmpPaint)
        }
        drawBackTraces(c, bl, bt, br - bl, bb - bt)

        // 2) 옆벽 4면 (회로 텍스처 원근 매핑 + 안쪽으로 갈수록 어둡게)
        val fw = w.toFloat()
        val fh = h.toFloat()
        drawWall(c, 0f, 0f, fw, 0f, br, bt, bl, bt, 0.95f)       // 위
        drawWall(c, fw, fh, 0f, fh, bl, bb, br, bb, 0.75f)       // 아래
        drawWall(c, 0f, fh, 0f, 0f, bl, bt, bl, bb, 0.85f)       // 왼쪽
        drawWall(c, fw, 0f, fw, fh, br, bb, br, bt, 0.85f)       // 오른쪽

        // 3) 벽면 세로 가이드선
        for (i in 0 until 12) {
            if (i < 4) continue // 모서리는 아래에서 더 밝게
            linePath(px(laneX[i], 0f), py(laneY[i], 0f), px(laneX[i], zb), py(laneY[i], zb))
            neon(c, path, VIOLET, u * 0.8f, 0.35f * boot)
        }

        // 4) 깊이별 터널 링 — 뒤에서부터 켜짐
        for (i in RINGS downTo 1) {
            val f = i / RINGS.toFloat()
            val z = zb * f
            val appear = ((boot - (1f - f) * 0.6f) / 0.4f).coerceIn(0f, 1f)
            val col = when {
                f > 0.75f -> CYAN
                f > 0.5f -> VIOLET
                f > 0.25f -> PINK
                else -> ORANGE
            }
            rectPath(px(-halfW, z), py(-halfH, z), px(halfW, z), py(halfH, z))
            neon(c, path, col, u * 1.1f, (1f - 0.45f * f) * 0.7f * appear)
        }

        // 5) 앞으로 밀려오는 에너지 링 2개
        for (k in 0 until 2) {
            val t = ((clock * 0.35f * speed + k * 0.5f) % 1f)
            val z = zb * (1f - t)
            val a = sin(t * PI.toFloat()) * boot
            rectPath(px(-halfW, z), py(-halfH, z), px(halfW, z), py(halfH, z))
            neon(c, path, if (k == 0) CYAN else PINK, u * 1.6f, a)
        }

        // 6) 네 모서리 네온
        for (i in 0 until 4) {
            linePath(px(laneX[i], 0f), py(laneY[i], 0f), px(laneX[i], zb), py(laneY[i], zb))
            neon(c, path, PINK, u * 1.4f, 0.9f * boot)
        }

        // 7) 데이터 패킷
        if (particlesEnabled) drawPackets(c, dt, spin, boot)

        // 8) 리액터 (박스 깊숙이)
        drawReactor(c, zb * 0.62f, pulse, boot)

        // 9) 유리 큐브들
        for ((i, p) in panels.withIndex()) drawPanel(c, p, i, pulse, boot)
    }

    private fun drawWall(
        c: Canvas,
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
        light: Float
    ) {
        val tex = wallTex ?: return
        val tw = tex.width.toFloat()
        val th = tex.height.toFloat()
        src[0] = 0f; src[1] = 0f; src[2] = tw; src[3] = 0f
        src[4] = tw; src[5] = th; src[6] = 0f; src[7] = th
        dst[0] = x0; dst[1] = y0; dst[2] = x1; dst[3] = y1
        dst[4] = x2; dst[5] = y2; dst[6] = x3; dst[7] = y3
        if (!mtx.setPolyToPoly(src, 0, dst, 0, 4)) return
        bmpPaint.alpha = (255 * light).toInt()
        c.drawBitmap(tex, mtx, bmpPaint)

        // 안쪽으로 갈수록 어두워지는 음영
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        shadePaint.shader = LinearGradient(
            (x0 + x1) / 2f, (y0 + y1) / 2f, (x2 + x3) / 2f, (y2 + y3) / 2f,
            0x30000000, 0xD8000000.toInt(), Shader.TileMode.CLAMP
        )
        c.drawPath(path, shadePaint)
    }

    private fun drawBackTraces(c: Canvas, l: Float, t: Float, bw: Float, bh: Float) {
        val dash = DashPathEffect(floatArrayOf(u * 10f, u * 26f), -(clock * speed * u * 60f))
        for ((i, tr) in backTraces.withIndex()) {
            path.reset()
            path.moveTo(l + tr[0] * bw, t + tr[1] * bh)
            var k = 2
            while (k < tr.size) {
                path.lineTo(l + tr[k] * bw, t + tr[k + 1] * bh); k += 2
            }
            neonPaint.pathEffect = null
            neon(c, path, palette[i % palette.size], u * 0.5f, 0.35f)
            neonPaint.pathEffect = dash
            neon(c, path, palette[i % palette.size], u * 0.9f, 1f)
            neonPaint.pathEffect = null
        }
    }

    private fun respawn(p: Packet, initial: Boolean) {
        p.lane = rnd.nextInt(12)
        p.z = if (initial) rnd.nextFloat() * boxD else boxD * (0.9f + rnd.nextFloat() * 0.1f)
        p.speed = boxD * (0.25f + rnd.nextFloat() * 0.45f)
        p.color = palette[rnd.nextInt(palette.size)]
    }

    private fun drawPackets(c: Canvas, dt: Float, spin: Float, boot: Float) {
        for (p in packets) {
            p.z -= p.speed * dt * spin
            if (p.z < 0f) { respawn(p, initial = false); continue }
            val zTail = min(boxD, p.z + boxD * 0.07f)
            val x = laneX[p.lane]
            val y = laneY[p.lane]
            linePath(px(x, p.z), py(y, p.z), px(x, zTail), py(y, zTail))
            val a = (1f - p.z / boxD).coerceIn(0.2f, 1f) * boot
            neon(c, path, p.color, u * 1.3f * sc(p.z), a)
        }
    }

    private fun drawReactor(c: Canvas, z: Float, pulse: Float, boot: Float) {
        val s = sc(z) * (0.85f + 0.15f * boot)
        c.save()
        c.translate(px(0f, z), py(0f, z))
        c.scale(s, s)

        haloPaint.alpha = (140 + 115 * pulse).toInt()
        c.drawCircle(0f, 0f, minDim * 0.55f, haloPaint)

        // 눈금 링
        neonPaint.pathEffect = null
        c.save(); c.rotate(ringAngle1)
        neonPaint.color = CYAN; neonPaint.strokeWidth = u; neonPaint.alpha = (150 * boot).toInt()
        c.drawPath(tickPath, neonPaint)
        c.restore()
        path.reset(); path.addCircle(0f, 0f, minDim * 0.36f, Path.Direction.CW)
        neon(c, path, CYAN, u * 1.2f, 0.8f * boot)

        // 분절 링 (반대 회전)
        val r2 = minDim * 0.29f
        rect.set(-r2, -r2, r2, r2)
        c.save(); c.rotate(ringAngle2)
        for (i in 0 until 6) {
            path.reset(); path.addArc(rect, i * 60f + 5f, 38f)
            neon(c, path, if (i == 0) ORANGE else PINK, u * 3f, boot)
        }
        c.restore()

        // 게이지 아크
        val r3 = minDim * 0.21f
        rect.set(-r3, -r3, r3, r3)
        path.reset(); path.addArc(rect, -90f, 200f + 120f * sin(clock * 0.7f))
        neon(c, path, LIME, u * 1.6f, boot)

        // 코어
        path.reset()
        val r4 = minDim * 0.12f
        for (i in 0 until 6) {
            val a = PI / 6 + i * PI / 3
            val x = (cos(a) * r4).toFloat()
            val y = (sin(a) * r4).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fillPaint.color = 0xE0100A1E.toInt()
        c.drawPath(path, fillPaint)
        neon(c, path, CYAN, u * 1.5f, boot)
        addFill.color = Color.WHITE
        addFill.alpha = (90 + 165 * pulse).toInt()
        c.drawCircle(0f, 0f, r4 * 0.35f, addFill)
        addFill.color = CYAN
        addFill.alpha = (60 + 100 * pulse).toInt()
        c.drawCircle(0f, 0f, r4 * 0.6f, addFill)
        c.restore()
    }

    /** 네온 유리 큐브: 뒷면·옆면·앞면을 각각 다른 깊이로 투영 → 입체 상자. */
    private fun drawPanel(c: Canvas, p: Panel, idx: Int, pulse: Float, boot: Float) {
        val half = p.size * minDim
        val bob = sin(clock * 0.8f + p.phase) * minDim * 0.01f
        val x = p.fx * halfW
        val y = p.fy * halfH + bob
        val zf = p.fz * boxD
        val zk = zf + half * 1.3f
        val appear = ((boot - 0.2f - idx * 0.08f) / 0.4f).coerceIn(0f, 1f)
        if (appear <= 0f) return

        val fl = px(x - half, zf); val ft = py(y - half, zf)
        val fr = px(x + half, zf); val fb = py(y + half, zf)
        val kl = px(x - half, zk); val kt = py(y - half, zk)
        val kr = px(x + half, zk); val kb = py(y + half, zk)

        // 옆면 (희미한 유리)
        addFill.color = p.color
        addFill.alpha = (22 * appear).toInt()
        quad(fl, ft, fr, ft, kr, kt, kl, kt); c.drawPath(path, addFill)
        quad(fl, fb, fr, fb, kr, kb, kl, kb); c.drawPath(path, addFill)
        quad(fl, ft, fl, fb, kl, kb, kl, kt); c.drawPath(path, addFill)
        quad(fr, ft, fr, fb, kr, kb, kr, kt); c.drawPath(path, addFill)

        // 뒷면 + 연결 모서리
        rectPath(kl, kt, kr, kb)
        neon(c, path, p.color, u * 0.8f, 0.45f * appear)
        path.reset()
        path.moveTo(fl, ft); path.lineTo(kl, kt)
        path.moveTo(fr, ft); path.lineTo(kr, kt)
        path.moveTo(fl, fb); path.lineTo(kl, kb)
        path.moveTo(fr, fb); path.lineTo(kr, kb)
        neon(c, path, p.color, u * 0.9f, 0.7f * appear)

        // 앞면 유리
        fillPaint.color = p.color
        fillPaint.alpha = (34 * appear).toInt()
        c.drawRect(fl, ft, fr, fb, fillPaint)
        rectPath(fl, ft, fr, fb)
        neon(c, path, p.color, u * 1.4f, (0.85f + 0.15f * pulse) * appear)
        // 윗변 하이라이트
        linePath(fl + (fr - fl) * 0.1f, ft + u * 2f, fr - (fr - fl) * 0.1f, ft + u * 2f)
        neonPaint.color = Color.WHITE; neonPaint.strokeWidth = u * 0.6f; neonPaint.alpha = (120 * appear).toInt()
        c.drawPath(path, neonPaint)

        drawGlyph(c, p, fl, ft, fr, fb, appear)
    }

    private fun quad(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float
    ) {
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
    }

    private fun drawGlyph(c: Canvas, p: Panel, l: Float, t: Float, r: Float, b: Float, a: Float) {
        val gw = r - l
        val gh = b - t
        val pad = gw * 0.2f
        val gl = l + pad
        val gt = t + pad
        val iw = gw - pad * 2
        val ih = gh - pad * 2
        val lw = u * 1.1f * (gw / (minDim * 0.2f)).coerceIn(0.6f, 1.6f)
        when (p.glyph) {
            GLYPH_BARS -> {
                path.reset()
                for (i in 0 until 5) {
                    val bx = gl + iw * (i + 0.5f) / 5f
                    val v = 0.2f + 0.65f * (0.5f + 0.5f * sin(clock * 2.2f * speed + i * 1.3f + p.phase))
                    path.moveTo(bx, gt + ih)
                    path.lineTo(bx, gt + ih * (1f - v))
                }
                neon(c, path, p.color, lw * 1.6f, a)
            }
            GLYPH_WAVE -> {
                path.reset()
                for (i in 0..24) {
                    val f = i / 24f
                    val yy = gt + ih * (0.5f + 0.32f * sin(f * 2f * PI.toFloat() * 1.5f + clock * 3f * speed + p.phase))
                    if (i == 0) path.moveTo(gl + iw * f, yy) else path.lineTo(gl + iw * f, yy)
                }
                neon(c, path, p.color, lw, a)
            }
            GLYPH_RING -> {
                val rr = min(iw, ih) * 0.5f
                val ccx = gl + iw / 2f
                val ccy = gt + ih / 2f
                path.reset(); path.addCircle(ccx, ccy, rr, Path.Direction.CW)
                neon(c, path, p.color, lw * 0.6f, 0.5f * a)
                rect.set(ccx - rr, ccy - rr, ccx + rr, ccy + rr)
                path.reset(); path.addArc(rect, (clock * 140f * speed) % 360f, 250f)
                neon(c, path, p.color, lw * 1.3f, a)
            }
            else -> {
                for (i in 0 until 9) {
                    val gx = gl + iw * ((i % 3) + 0.5f) / 3f
                    val gy = gt + ih * ((i / 3) + 0.5f) / 3f
                    val v = 0.5f + 0.5f * sin(clock * 3f * speed + i * 0.9f + p.phase)
                    addFill.color = p.color
                    addFill.alpha = (60 + 195 * v * a).toInt().coerceIn(0, 255)
                    c.drawCircle(gx, gy, lw * 1.6f, addFill)
                }
            }
        }
    }

    // =========================================================================
    // 사진 모드 (사용자 PNG)
    // =========================================================================

    private fun drawPhoto(c: Canvas, tiltX: Float, tiltY: Float, pulse: Float, boot: Float) {
        val unit = depth * minDim
        val left = (w - layerW) / 2f
        val top = (h - layerH) / 2f
        c.drawColor(Color.BLACK)
        bg?.let { c.drawBitmap(it, left + tiltX * BG_PCT * unit, top + tiltY * BG_PCT * unit, bmpPaint) }
        glow?.let {
            glowPaint.alpha = (255 * (0.7f + 0.3f * pulse + (1f - boot) * 0.6f).coerceAtMost(1f)).toInt()
            c.drawBitmap(it, left + tiltX * GLOW_PCT * unit, top + tiltY * GLOW_PCT * unit, glowPaint)
        }
        fgShadow?.let {
            val sx = left + tiltX * FG_PCT * SHADOW_FOLLOW * unit - shadowPad * 2f
            val sy = top + tiltY * FG_PCT * SHADOW_FOLLOW * unit - shadowPad * 2f + minDim * 0.04f
            shadowDst.set(sx, sy, sx + it.width * 2f, sy + it.height * 2f)
            c.drawBitmap(it, null, shadowDst, shadowPaint)
        }
        fg?.let { c.drawBitmap(it, left + tiltX * FG_PCT * unit, top + tiltY * FG_PCT * unit, bmpPaint) }
    }

    // =========================================================================
    // 공통 오버레이
    // =========================================================================

    private fun drawHud(c: Canvas, boot: Float, dt: Float, spin: Float) {
        hudTimer += dt
        if (hudTimer > 0.25f) {
            hudTimer = 0f
            hudCore = "CORE %.1f%%".format(97.5f + rnd.nextFloat() * 2f)
            hudRpm = "RPM %04d".format((3200 * spin + rnd.nextInt(-30, 30)).toInt().coerceIn(0, 9999))
            val filled = (clock * 1.5f).toInt() % 6
            hudSync = "SYNC [" + "#".repeat(filled) + "-".repeat(5 - minOf(filled, 5)) + "]"
        }
        val status = if (boot < 1f) "BOOT" + ".".repeat((clock * 6f).toInt() % 4) else "SYS//ONLINE"
        val zh = -boxD * 0.1f
        val ox = tx * zh * PARALLAX
        val oy = ty * zh * PARALLAX
        val lineH = hudPaint.textSize * 1.5f
        val pad = minDim * 0.08f

        hudPaint.textAlign = Paint.Align.LEFT
        hudPaint.color = CYAN
        hudPaint.alpha = 190
        c.drawText(status, pad + ox, h * 0.24f + oy, hudPaint)
        c.drawText(hudCore, pad + ox, h * 0.24f + lineH + oy, hudPaint)

        hudPaint.textAlign = Paint.Align.RIGHT
        hudPaint.color = ORANGE
        hudPaint.alpha = 210
        c.drawText(hudRpm, w - pad + ox, h * 0.8f + oy, hudPaint)
        c.drawText(hudSync, w - pad + ox, h * 0.8f + lineH + oy, hudPaint)
    }

    /** 투명 케이스 앞유리 반사광 — 기울기 반대로 살짝 미끄러짐. */
    private fun drawGlare(c: Canvas) {
        c.save()
        c.translate(-tx * minDim * 0.06f, -ty * minDim * 0.06f)
        c.drawRect(-minDim, -minDim, w + minDim, h + minDim, glarePaint)
        c.restore()
    }

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
    // 준비 작업
    // =========================================================================

    private fun buildLanes() {
        // 0..3 모서리, 4..9 위/아래 벽, 10..11 왼/오른 벽
        val xs = floatArrayOf(-halfW, halfW, halfW, -halfW)
        val ys = floatArrayOf(-halfH, -halfH, halfH, halfH)
        for (i in 0 until 4) { laneX[i] = xs[i]; laneY[i] = ys[i] }
        val fr = floatArrayOf(-0.5f, 0f, 0.5f)
        for (i in 0 until 3) {
            laneX[4 + i] = fr[i] * halfW; laneY[4 + i] = -halfH
            laneX[7 + i] = fr[i] * halfW; laneY[7 + i] = halfH
        }
        laneX[10] = -halfW; laneY[10] = 0f
        laneX[11] = halfW; laneY[11] = 0f
    }

    /** 뒷벽 위를 달리는 회로선 (정규화 좌표 0..1, 직각/45° 꺾임). */
    private fun buildBackTraces() {
        backTraces.clear()
        val r = Random(5)
        repeat(12) {
            val pts = ArrayList<Float>()
            var x = 0.5f + (r.nextFloat() - 0.5f) * 0.25f
            var y = 0.5f + (r.nextFloat() - 0.5f) * 0.25f
            pts.add(x); pts.add(y)
            val dirX = if (r.nextBoolean()) 1f else -1f
            val dirY = if (r.nextBoolean()) 1f else -1f
            repeat(5) { step ->
                val len = 0.06f + r.nextFloat() * 0.14f
                when ((step + it) % 3) {
                    0 -> x += dirX * len
                    1 -> y += dirY * len
                    else -> { x += dirX * len * 0.7f; y += dirY * len * 0.7f }
                }
                x = x.coerceIn(0.02f, 0.98f); y = y.coerceIn(0.02f, 0.98f)
                pts.add(x); pts.add(y)
            }
            backTraces.add(pts.toFloatArray())
        }
    }

    /** 회로기판 텍스처 (어두운 기판 + 은은한 배선 + 칩). */
    private fun buildCircuit(bw: Int, bh: Int, seed: Int, density: Float): Bitmap {
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = Random(seed)
        c.drawColor(0xFF0A0716.toInt())

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // 미세 그리드
        p.color = 0x149D4DFF
        p.strokeWidth = 1f
        val g = 16f
        var gx = 0f
        while (gx < bw) { c.drawLine(gx, 0f, gx, bh.toFloat(), p); gx += g }
        var gy = 0f
        while (gy < bh) { c.drawLine(0f, gy, bw.toFloat(), gy, p); gy += g }

        val colors = intArrayOf(0x6600E5FF, 0x66FF2E88, 0x669D4DFF, 0x5539FF14, 0x55FF8A00)
        val pad = Paint(Paint.ANTI_ALIAS_FLAG)
        val trace = Path()
        repeat((60 * density).toInt()) {
            var x = (r.nextInt(bw / 16) * 16).toFloat()
            var y = (r.nextInt(bh / 16) * 16).toFloat()
            val col = colors[r.nextInt(colors.size)]
            p.color = col
            p.strokeWidth = if (r.nextInt(4) == 0) 3f else 1.6f
            trace.reset(); trace.moveTo(x, y)
            var dir = r.nextInt(4)
            repeat(3 + r.nextInt(8)) {
                val len = 16f * (1 + r.nextInt(4))
                when (dir) {
                    0 -> x += len
                    1 -> y += len
                    2 -> { x += len * 0.7f; y += len * 0.7f }
                    else -> { x -= len * 0.7f; y += len * 0.7f }
                }
                trace.lineTo(x, y)
                if (r.nextInt(3) == 0) dir = r.nextInt(4)
            }
            c.drawPath(trace, p)
            pad.color = col or 0xFF000000.toInt()
            pad.alpha = 150
            c.drawCircle(x, y, 3.2f, pad)
        }
        // 칩
        val chip = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat((7 * density).toInt()) {
            val cw = 40f + r.nextInt(70)
            val ch = 30f + r.nextInt(50)
            val x = r.nextFloat() * (bw - cw)
            val y = r.nextFloat() * (bh - ch)
            chip.style = Paint.Style.FILL
            chip.color = 0xFF151028.toInt()
            c.drawRoundRect(x, y, x + cw, y + ch, 4f, 4f, chip)
            chip.style = Paint.Style.STROKE
            chip.strokeWidth = 1.5f
            chip.color = 0x559D4DFF
            c.drawRoundRect(x, y, x + cw, y + ch, 4f, 4f, chip)
            chip.color = 0x44FFFFFF
            var pxx = x + 6f
            while (pxx < x + cw - 4f) {
                c.drawLine(pxx, y - 5f, pxx, y, chip)
                c.drawLine(pxx, y + ch, pxx, y + ch + 5f, chip)
                pxx += 8f
            }
        }
        return b
    }

    private fun buildTicks(r: Float): Path {
        val p = Path()
        for (i in 0 until 120) {
            val a = i * 3.0 * PI / 180.0
            val len = when {
                i % 10 == 0 -> minDim * 0.03f
                i % 5 == 0 -> minDim * 0.018f
                else -> minDim * 0.01f
            }
            val cs = cos(a).toFloat()
            val sn = sin(a).toFloat()
            p.moveTo(cs * r, sn * r)
            p.lineTo(cs * (r - len), sn * (r - len))
        }
        return p
    }

    @Suppress("DiscouragedApi")
    private fun loadLayer(name: String): Bitmap? {
        val id = res.getIdentifier(name, "drawable", packageName)
        if (id == 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(res, id, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= layerW && bounds.outHeight / (sample * 2) >= layerH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeResource(res, id, opts) ?: return null
        val out = Bitmap.createBitmap(layerW, layerH, Bitmap.Config.ARGB_8888)
        val s = max(layerW / raw.width.toFloat(), layerH / raw.height.toFloat())
        val dw = raw.width * s
        val dh = raw.height * s
        Canvas(out).drawBitmap(
            raw, null,
            RectF((layerW - dw) / 2f, (layerH - dh) / 2f, (layerW + dw) / 2f, (layerH + dh) / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    private fun buildShadow(src: Bitmap): Bitmap? {
        val sw = src.width / 2
        val sh = src.height / 2
        if (sw <= 0 || sh <= 0) return null
        val alpha = Bitmap.createScaledBitmap(src, sw, sh, true).extractAlpha()
        val blur = max(4f, minDim * 0.02f)
        shadowPad = (blur * 2).toInt()
        val out = Bitmap.createBitmap(sw + shadowPad * 2, sh + shadowPad * 2, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(out).drawBitmap(alpha, shadowPad.toFloat(), shadowPad.toFloat(), p)
        return out
    }
}
