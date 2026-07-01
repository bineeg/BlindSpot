package com.blindspot.modules.engine

import com.blindspot.modules.ui.UiController
import burp.api.montoya.http.message.MimeType
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.proxy.Proxy

/**
 * Single capture pipeline shared by live proxy handlers and the on-demand
 * "Import Proxy History" action, so both produce identical results.
 *
 * Live handlers pass `notifyUi = true` to stream rows into the tables as traffic
 * flows. The history import runs quietly (`notifyUi = false`) — it only fills the
 * store, and the caller does a single view rebuild at the end so a large history
 * doesn't flood the EDT with per-endpoint updates.
 */
object CaptureEngine {

    /** Records a requested path as discovered + visited for its (in-scope) host. */
    fun ingestRequest(request: HttpRequest, notifyUi: Boolean) {
        if (!request.isInScope()) return
        val host = request.httpService().host()
        val cleanPath = UrlModule.normalizePath(request.url())
        val isNewPath = StorageModule.registerDiscovery(host, cleanPath).isNew
        val newlyVisited = StorageModule.markAsVisited(host, cleanPath)
        if (notifyUi) UiController.logVisitedRequest(host, cleanPath, isNewPath, newlyVisited)
    }

    /** Scans an in-scope JS response for endpoints, attributing them to its host. */
    fun ingestResponse(initiatingRequest: HttpRequest, response: HttpResponse, notifyUi: Boolean) {
        if (!initiatingRequest.isInScope()) return
        if (response.mimeType() != MimeType.SCRIPT) return

        val host = initiatingRequest.httpService().host()
        val jsSource = UrlModule.normalizePath(initiatingRequest.url())
        val candidates = JsScannerModule.extractEnrichedEndpoints(response.bodyToString())

        for ((path, _) in candidates) {
            val result = StorageModule.registerDiscovery(host, path, jsSource)
            if (notifyUi && (result.isNew || result.sourceAdded)) {
                UiController.logDiscoveredEndpoint(host, path)
            }
        }
    }

    /**
     * Backfills the store from Burp's existing Proxy HTTP history using the same
     * scope + scan logic as live capture. Intended for when BlindSpot is loaded
     * after browsing has already happened. Runs quietly; call a view rebuild after.
     * @return the number of history items processed.
     */
    fun importHistory(proxy: Proxy): Int {
        val history = proxy.history()
        for (item in history) {
            val request = item.finalRequest() ?: continue
            ingestRequest(request, notifyUi = false)
            val response = item.response()
            if (response != null) ingestResponse(request, response, notifyUi = false)
        }
        return history.size
    }
}
