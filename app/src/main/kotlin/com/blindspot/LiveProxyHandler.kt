package com.blindspot

import com.blindspot.modules.engine.CaptureEngine
import com.blindspot.modules.ui.UiController

import burp.api.montoya.proxy.http.*

class LiveProxyHandler : ProxyRequestHandler, ProxyResponseHandler {

    override fun handleRequestReceived(interceptedRequest: InterceptedRequest): ProxyRequestReceivedAction {
        if (UiController.shouldFilterRun()) {
            // Scope gating, host attribution, and storage all live in CaptureEngine
            // so live capture and history import behave identically.
            CaptureEngine.ingestRequest(interceptedRequest, notifyUi = true)
        }
        return ProxyRequestReceivedAction.continueWith(interceptedRequest)
    }

    override fun handleRequestToBeSent(interceptedRequest: InterceptedRequest): ProxyRequestToBeSentAction {
        return ProxyRequestToBeSentAction.continueWith(interceptedRequest)
    }

    override fun handleResponseReceived(interceptedResponse: InterceptedResponse): ProxyResponseReceivedAction {
        if (UiController.shouldFilterRun()) {
            CaptureEngine.ingestResponse(
                interceptedResponse.initiatingRequest(), interceptedResponse, notifyUi = true
            )
        }
        return ProxyResponseReceivedAction.continueWith(interceptedResponse)
    }

    override fun handleResponseToBeSent(interceptedResponse: InterceptedResponse): ProxyResponseToBeSentAction {
        return ProxyResponseToBeSentAction.continueWith(interceptedResponse)
    }
}
