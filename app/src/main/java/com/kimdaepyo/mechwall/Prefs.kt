package com.kimdaepyo.mechwall

/** 설정 화면과 월페이퍼 엔진이 함께 쓰는 SharedPreferences 키. */
object Prefs {
    const val NAME = "mech_wallpaper"

    /** 깊이 강도, 50..200 → 0.5x..2.0x */
    const val KEY_DEPTH = "depth_pct"
    const val DEF_DEPTH = 100

    /** 기계 회전 속도, 25..300 → 0.25x..3.0x */
    const val KEY_SPEED = "speed_pct"
    const val DEF_SPEED = 100

    const val KEY_PARTICLES = "particles"
    const val KEY_HEARTBEAT = "heartbeat"
}
