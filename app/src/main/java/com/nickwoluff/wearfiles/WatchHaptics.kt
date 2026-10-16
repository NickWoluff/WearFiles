package com.nickwoluff.wearfiles

import android.content.ContextWrapper
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

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
    val view = LocalView.current
    val activity = remember(view) {
        generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<MainActivity>().firstOrNull()
    }
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(view, dialogWindow, activity) {
        // Wear 列表绕过 LocalHapticFeedback，直接向承载 View 请求滚动震动。
        // 只禁用 Compose 内容 View，Activity 的 decorView 保留全局反馈。
        val wasEnabled = view.isHapticFeedbackEnabled
        view.isHapticFeedbackEnabled = false
        val originalCallback = dialogWindow?.callback
        val callback = if (originalCallback != null && activity != null) {
            object : Window.Callback by originalCallback {
                override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                    // Dialog 拥有独立窗口，表冠事件也交给 Activity 的同一个累加器。
                    activity.handleRotaryHaptics(event)
                    return originalCallback.dispatchGenericMotionEvent(event)
                }
            }
        } else null
        if (callback != null) dialogWindow?.callback = callback
        onDispose {
            view.isHapticFeedbackEnabled = wasEnabled
            if (callback != null && dialogWindow?.callback === callback) {
                dialogWindow.callback = originalCallback
            }
        }
    }
    val feedback = remember {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}
