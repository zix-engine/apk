package com.roziq.zixtheserver

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.roziq.zixtheserver.databinding.ActivitySplashBinding
import kotlin.concurrent.thread

/** Layar pembuka dengan animasi loading. Sambil animasi, penyimpanan terenkripsi dipanaskan. */
class SplashActivity : BaseActivity() {

    private lateinit var b: ActivitySplashBinding
    private val animators = mutableListOf<Animator>()
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pemanasan Keystore/EncryptedSharedPreferences di latar agar daftar server cepat muncul.
        thread(name = "warmup") {
            try {
                ServerStore(applicationContext).list()
            } catch (_: Exception) {
            }
        }

        if (!prefs.splash) {
            goMain()
            return
        }

        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        play()
    }

    private fun play() {
        b.logo.scaleX = 0.3f
        b.logo.scaleY = 0.3f
        b.logo.alpha = 0f
        b.tvName.alpha = 0f
        b.tvName.translationY = 40f
        b.tvTag.alpha = 0f
        b.tvTag.translationY = 30f
        b.ringOuter.alpha = 0f
        b.ringInner.alpha = 0f

        b.logo.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(800).setInterpolator(OvershootInterpolator(1.8f)).start()
        b.ringOuter.animate().alpha(1f).setDuration(600).start()
        b.ringInner.animate().alpha(1f).setDuration(900).start()
        b.tvName.animate().alpha(1f).translationY(0f)
            .setStartDelay(350).setDuration(550).start()
        b.tvTag.animate().alpha(1f).translationY(0f)
            .setStartDelay(550).setDuration(550).start()

        spin(b.ringOuter, 0f, 360f, 2600)
        spin(b.ringInner, 360f, 0f, 4200)

        // Logo "bernapas" pelan selama loading.
        val pulse = ObjectAnimator.ofFloat(b.logo, "translationY", 0f, -10f).apply {
            duration = 1100
            startDelay = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators.add(pulse)

        val steps = listOf(
            0 to R.string.splash_step1,
            30 to R.string.splash_step2,
            60 to R.string.splash_step3,
            90 to R.string.splash_step4
        )
        b.tvStatus.setText(R.string.splash_step1)
        val progress = ValueAnimator.ofInt(0, 100).apply {
            duration = 2200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Int
                b.progress.setProgressCompat(v, false)
                steps.lastOrNull { s -> v >= s.first }?.let { s ->
                    if (b.tvStatus.tag != s.second) {
                        b.tvStatus.tag = s.second
                        b.tvStatus.setText(s.second)
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishSplash()
                }
            })
            start()
        }
        animators.add(progress)
    }

    private fun spin(target: android.view.View, from: Float, to: Float, ms: Long) {
        val a = ObjectAnimator.ofFloat(target, "rotation", from, to).apply {
            duration = ms
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        animators.add(a)
    }

    private fun finishSplash() {
        if (launched || isDestroyed) return
        b.root.animate().alpha(0f).setDuration(260).withEndAction { goMain() }.start()
    }

    private fun goMain() {
        if (launched) return
        launched = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        animators.forEach { it.removeAllListeners(); it.cancel() }
        animators.clear()
        super.onDestroy()
    }
}
