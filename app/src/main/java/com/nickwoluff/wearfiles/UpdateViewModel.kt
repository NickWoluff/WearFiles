package com.nickwoluff.wearfiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

internal data class UpdateFeedback(val message: String, val error: Boolean)

internal interface UpdateRequest {
    fun check(callback: UpdateChecker.Callback)
    fun close()
}

private class NetworkUpdateRequest : UpdateRequest {
    private val checker = UpdateChecker()
    override fun check(callback: UpdateChecker.Callback) = checker.check(callback)
    override fun close() = checker.close()
}

/** 每次启动只自动检查一次，页面重建保留请求状态，离开应用时释放连接。 */
class UpdateViewModel internal constructor(private val request: UpdateRequest) : ViewModel() {
    constructor() : this(NetworkUpdateRequest())
    private var started = false
    private var cleared = false
    var checking by mutableStateOf(false)
        private set
    var availableVersion by mutableStateOf<String?>(null)
        private set
    internal var feedback by mutableStateOf<UpdateFeedback?>(null)
        private set

    fun checkOnce(localVersion: String, autoEnabled: () -> Boolean) {
        if (started) return
        started = true
        if (autoEnabled()) check(localVersion, false, autoEnabled)
    }

    fun checkManually(localVersion: String) = check(localVersion, true) { true }

    private fun check(localVersion: String, manual: Boolean, autoEnabled: () -> Boolean) {
        if (checking || cleared) return
        checking = true
        feedback = null
        availableVersion = null
        request.check(object : UpdateChecker.Callback {
            override fun onResult(tagName: String) {
                if (cleared) return
                checking = false
                if (!manual && !autoEnabled()) return
                try {
                    if (isNewerVersion(tagName, localVersion)) availableVersion = tagName
                    else if (manual) feedback = UpdateFeedback("已是最新版本", false)
                } catch (_: IllegalArgumentException) {
                    if (manual) feedback = UpdateFeedback(updateErrorMessage(-1), true)
                }
            }

            override fun onError(statusCode: Int) {
                if (cleared) return
                checking = false
                // 自动检查失败或无正式版本时，不打断用户浏览文件。
                if (manual) feedback = UpdateFeedback(updateErrorMessage(statusCode), true)
            }
        })
    }

    fun dismissRelease() { availableVersion = null }
    internal fun consumeFeedback() { feedback = null }

    override fun onCleared() {
        cleared = true
        request.close()
    }
}
