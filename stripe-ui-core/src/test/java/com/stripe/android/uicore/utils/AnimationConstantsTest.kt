package com.stripe.android.uicore.utils

import android.app.Activity
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

@RunWith(RobolectricTestRunner::class)
internal class AnimationConstantsTest {
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `fadeOut uses default animations before API 34`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        activity.fadeOut()

        assertPendingTransition(
            activity = activity,
            expectedFadeIn = AnimationConstants.FADE_IN,
            expectedFadeOut = AnimationConstants.FADE_OUT,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `fadeOut uses default animations on API 34`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        activity.fadeOut()

        assertPendingTransition(
            activity = activity,
            expectedFadeIn = AnimationConstants.FADE_IN,
            expectedFadeOut = AnimationConstants.FADE_OUT,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `fadeOut uses custom animations`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        activity.fadeOut(
            fadeIn = android.R.anim.slide_in_left,
            fadeOut = android.R.anim.slide_out_right,
        )

        assertPendingTransition(
            activity = activity,
            expectedFadeIn = android.R.anim.slide_in_left,
            expectedFadeOut = android.R.anim.slide_out_right,
        )
    }

    private fun assertPendingTransition(
        activity: Activity,
        expectedFadeIn: Int,
        expectedFadeOut: Int,
    ) {
        val shadowActivity = shadowOf(activity) as ShadowActivity
        assertThat(shadowActivity.pendingTransitionEnterAnimationResourceId).isEqualTo(expectedFadeIn)
        assertThat(shadowActivity.pendingTransitionExitAnimationResourceId).isEqualTo(expectedFadeOut)
    }
}
