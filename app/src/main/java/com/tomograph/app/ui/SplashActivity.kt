package com.tomograph.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tomograph.app.R

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val titleText = findViewById<TextView>(R.id.titleText)
        val numberText = findViewById<TextView>(R.id.numberText)
        val freqText = findViewById<TextView>(R.id.freqText)

        // Kisa acilis sinyal sesi (basit bip - harici dosya gerektirmez)
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        } catch (e: Exception) {
            // Ses cihazi yoksa sessizce devam
        }

        // 3B hissi veren donerek buyume animasyonu: rotationY + scale + alpha
        animateIn(titleText, 150)
        animateIn(numberText, 450)
        animateIn(freqText, 900)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 5000)
    }

    private fun animateIn(view: TextView, startDelay: Long) {
        view.rotationY = 90f
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.alpha = 0f

        view.animate()
            .rotationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setStartDelay(startDelay)
            .setDuration(700)
            .setInterpolator(OvershootInterpolator(1.2f))
            .setListener(null)
            .start()
    }
}
