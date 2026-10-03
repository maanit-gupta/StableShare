package com.maanit.stableshare.ui.mascot

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.maanit.stableshare.R

enum class MascotFace(@param:DrawableRes val drawable: Int) {
    IDLE(R.drawable.mascot_face_idle),
    FOCUSED(R.drawable.mascot_face_focused),
    HAPPY(R.drawable.mascot_face_happy),
    WORRIED(R.drawable.mascot_face_worried),
    SLEEPY(R.drawable.mascot_face_sleepy),
    SEARCHING(R.drawable.mascot_face_searching),
    SAD(R.drawable.mascot_face_sad),
    CALM(R.drawable.mascot_face_calm),
}

/** The idle motions of UI-SPEC §4.1. All of them stop under reduced motion (§10). */
enum class MascotMotion {
    NONE,

    /** Scale 1 → 1.02 → 1 over 3 s. */
    BREATHE,

    /** The same, over 5 s (paused). */
    BREATHE_SLOW,

    /** The face layer slides ±4 dp over 2 s (waiting for the network). */
    FACE_SLIDE,

    /** Rain drops fall 6 dp and fade, staggered 200 ms, 1.4 s loop. */
    RAIN,

    /** The raised right arm waves ±12° on a 1.2 s loop (splash, onboarding). */
    WAVE_LOOP,

    /** The raised right arm waves ±12° three times when the mood arrives, then rests raised. */
    WAVE_ARRIVAL,
}

/** One row of the mood table (UI-SPEC §4.1) plus its accessibility text (§4.2). */
enum class MascotMood(
    val face: MascotFace,
    val armRaised: Boolean,
    val rain: Boolean,
    val motion: MascotMotion,
    @param:StringRes val description: Int,
) {
    IDLE(MascotFace.IDLE, armRaised = true, rain = false, MascotMotion.WAVE_LOOP, R.string.mascot_idle),
    FOCUSED(MascotFace.FOCUSED, armRaised = false, rain = false, MascotMotion.BREATHE, R.string.mascot_focused),
    WORRIED(MascotFace.WORRIED, armRaised = false, rain = false, MascotMotion.BREATHE, R.string.mascot_worried),
    SEARCHING(MascotFace.SEARCHING, armRaised = false, rain = false, MascotMotion.FACE_SLIDE, R.string.mascot_searching),
    SLEEPY(MascotFace.SLEEPY, armRaised = false, rain = false, MascotMotion.BREATHE_SLOW, R.string.mascot_sleepy),
    SAD(MascotFace.SAD, armRaised = false, rain = true, MascotMotion.RAIN, R.string.mascot_sad),
    HAPPY(MascotFace.HAPPY, armRaised = true, rain = false, MascotMotion.WAVE_ARRIVAL, R.string.mascot_happy),
    CALM(MascotFace.CALM, armRaised = false, rain = false, MascotMotion.NONE, R.string.mascot_calm),
}
