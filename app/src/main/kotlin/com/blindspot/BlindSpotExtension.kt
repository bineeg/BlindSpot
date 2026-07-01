package com.blindspot

import com.blindspot.modules.engine.CaptureEngine
import com.blindspot.modules.engine.StorageModule
import com.blindspot.modules.ui.UiController // 🎯 Import the parent UI controller
import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi

class BlindSpotExtension : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("BlindSpot - JS Endpoint Coverage Finder")

        // Load any persisted per-host discoveries before traffic starts flowing,
        // then seed the host selector from that restored state.
        StorageModule.init(api.persistence())
        // Backfill action for the "Import Proxy History" button.
        UiController.setHistoryImporter { CaptureEngine.importHistory(api.proxy()) }
        UiController.refreshHostList()

        val handler = LiveProxyHandler()
        api.proxy().registerRequestHandler(handler)
        api.proxy().registerResponseHandler(handler)

        // Mount the controller's composite layout view directly into the tab index row
        api.userInterface().registerSuiteTab("BlindSpot", UiController.getView())

        // Flush pending state and stop the background flusher on unload/crash-reload.
        api.extension().registerUnloadingHandler { StorageModule.shutdown() }

        api.logging().logToOutput("[+] Modular BlindSpot Engine & UI Stack Loaded Successfully.")
        api.logging().logToOutput("[+] Author: bineeg")
    }
}
