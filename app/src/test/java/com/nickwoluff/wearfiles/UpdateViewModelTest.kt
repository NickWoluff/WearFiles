package com.nickwoluff.wearfiles

import org.junit.Assert.*
import org.junit.Test

class UpdateViewModelTest {
    private class FakeRequest : UpdateRequest {
        var calls = 0
        lateinit var callback: UpdateChecker.Callback
        override fun check(callback: UpdateChecker.Callback) { calls++; this.callback = callback }
        override fun close() {}
    }

    @Test fun startupChecksOnceAndOnlyPromptsForNewerVersion() {
        val request = FakeRequest()
        val model = UpdateViewModel(request)
        model.checkOnce("0.3.0") { true }
        model.checkOnce("0.3.0") { true }
        assertEquals(1, request.calls)
        assertTrue(model.checking)
        request.callback.onResult("v0.4.0")
        assertFalse(model.checking)
        assertEquals("v0.4.0", model.availableVersion)
        assertNull(model.feedback)
        model.dismissRelease()
        assertNull(model.availableVersion)
    }

    @Test fun disablingAutomaticChecksStillAllowsManualCheck() {
        val request = FakeRequest()
        val model = UpdateViewModel(request)
        model.checkOnce("0.3.0") { false }
        assertEquals(0, request.calls)
        model.checkManually("0.3.0")
        model.checkManually("0.3.0")
        assertEquals(1, request.calls)
        request.callback.onResult("v0.3.0")
        assertEquals(UpdateFeedback("已是最新版本", false), model.feedback)
        model.consumeFeedback()
        assertNull(model.feedback)
    }

    @Test fun automaticResultsAreSilentForSameVersionOrFailure() {
        val request = FakeRequest()
        val model = UpdateViewModel(request)
        model.checkOnce("0.3.0") { true }
        request.callback.onResult("0.3.0")
        assertNull(model.availableVersion)
        assertNull(model.feedback)
        val failedRequest = FakeRequest()
        val failed = UpdateViewModel(failedRequest)
        failed.checkOnce("0.3.0") { true }
        failedRequest.callback.onError(404)
        assertFalse(failed.checking)
        assertNull(failed.availableVersion)
        assertNull(failed.feedback)
    }

    @Test fun disablingSwitchDuringRequestSuppressesAutomaticPrompt() {
        val request = FakeRequest()
        val model = UpdateViewModel(request)
        var enabled = true
        model.checkOnce("0.3.0") { enabled }
        enabled = false
        request.callback.onResult("v1.0.0")
        assertFalse(model.checking)
        assertNull(model.availableVersion)
        assertNull(model.feedback)
    }

    @Test fun manualFailuresCanBeRetriedAndNewVersionRemainsAvailable() {
        val request = FakeRequest()
        val model = UpdateViewModel(request)
        model.checkManually("0.3.0")
        request.callback.onError(429)
        assertEquals(UpdateFeedback(updateErrorMessage(429), true), model.feedback)
        assertFalse(model.checking)
        model.checkManually("0.3.0")
        assertNull(model.feedback)
        request.callback.onResult("bad-tag")
        assertEquals(UpdateFeedback(updateErrorMessage(-1), true), model.feedback)
        model.checkManually("0.3.0")
        request.callback.onResult("v0.4.0")
        assertEquals("v0.4.0", model.availableVersion)
        assertNull(model.feedback)
    }
}
