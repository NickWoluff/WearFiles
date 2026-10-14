package com.nickwoluff.wearfiles

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

object WatchHaptics {
    const val FEEDBACK_CONSTANT = 18
    const val ROTARY_THRESHOLD = 0.4f

    fun perform(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(FEEDBACK_CONSTANT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}

@Composable
fun WatchHapticFeedbackProvider(content: @Composable () -> Unit) {
    val feedback = remember {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}
