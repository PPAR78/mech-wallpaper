package com.kimdaepyo.mechwall

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** 깊이 강도 / 회전 속도 / 입자 / 펄스 모드 설정 + '월페이퍼로 설정하기' 버튼. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val depthLabel = findViewById<TextView>(R.id.depthLabel)
        val depthBar = findViewById<SeekBar>(R.id.depthBar)
        depthBar.progress = prefs.getInt(Prefs.KEY_DEPTH, Prefs.DEF_DEPTH)
        depthLabel.text = getString(R.string.depth_label, depthBar.progress / 100f)
        depthBar.onChange { v ->
            depthLabel.text = getString(R.string.depth_label, v / 100f)
            prefs.edit().putInt(Prefs.KEY_DEPTH, v).apply()
        }

        val speedLabel = findViewById<TextView>(R.id.speedLabel)
        val speedBar = findViewById<SeekBar>(R.id.speedBar)
        speedBar.progress = prefs.getInt(Prefs.KEY_SPEED, Prefs.DEF_SPEED)
        speedLabel.text = getString(R.string.speed_label, speedBar.progress / 100f)
        speedBar.onChange { v ->
            speedLabel.text = getString(R.string.speed_label, v / 100f)
            prefs.edit().putInt(Prefs.KEY_SPEED, v).apply()
        }

        val particles = findViewById<Switch>(R.id.particlesSwitch)
        particles.isChecked = prefs.getBoolean(Prefs.KEY_PARTICLES, true)
        particles.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(Prefs.KEY_PARTICLES, on).apply()
        }

        val heartbeat = findViewById<Switch>(R.id.heartbeatSwitch)
        heartbeat.isChecked = prefs.getBoolean(Prefs.KEY_HEARTBEAT, true)
        heartbeat.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(Prefs.KEY_HEARTBEAT, on).apply()
        }

        findViewById<Button>(R.id.applyButton).setOnClickListener { openWallpaperPicker() }
    }

    private fun openWallpaperPicker() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, MechWallpaperService::class.java)
        )
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            // 일부 런처는 직접 지정을 지원하지 않음 → 라이브 월페이퍼 목록으로
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun SeekBar.onChange(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = block(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }
}
