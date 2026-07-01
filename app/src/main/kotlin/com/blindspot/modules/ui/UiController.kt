package com.blindspot.modules.ui

import com.blindspot.modules.engine.StorageModule
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Owns the composite view and mediates between the proxy handlers and the
 * Swing widgets. The tables are a live view of exactly one selected host;
 * everything is marshaled onto the EDT here so the widgets stay single-threaded.
 */
object UiController {
    private val mainPanel = JPanel(BorderLayout())
    private val tableComponent = TableComponent()
    private val controlPanelComponent = ControlPanelComponent()

    // Backfill action supplied by the extension (has the MontoyaApi); returns the
    // number of history items processed.
    private var historyImporter: (() -> Int)? = null

    init {
        mainPanel.add(controlPanelComponent.container, BorderLayout.NORTH)
        mainPanel.add(tableComponent.container, BorderLayout.CENTER)

        controlPanelComponent.onHostSelected = { host -> rebuildForHost(host) }
        controlPanelComponent.onRefresh = { rebuildForHost(controlPanelComponent.selectedHost()) }
        controlPanelComponent.onImport = { runHistoryImport() }
        controlPanelComponent.onClear = { host ->
            StorageModule.clear(host)
            if (host == null) controlPanelComponent.setHosts(emptyList())
            else controlPanelComponent.removeHost(host)
            rebuildForHost(controlPanelComponent.selectedHost())
        }
    }

    fun shouldFilterRun(): Boolean = controlPanelComponent.scopeToggle.isSelected

    fun getExclusionFilterList(): List<String> {
        return controlPanelComponent.excludeField.text
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Whether a path is hidden by the current exclude filter. This is purely a
     * view concern — excluded paths are still captured and persisted, so editing
     * the filter re-reveals them without any data loss.
     *
     * Dotted entries (".png", ".json") match as a file-extension suffix, so
     * ".json" hides /foo.json but not the route /api/jsonify. Any other entry
     * ("/static/", "analytics") matches as a substring anywhere in the path.
     */
    fun isExcluded(path: String): Boolean {
        val lowerPath = path.lowercase()
        for (filter in getExclusionFilterList()) {
            val matches = if (filter.startsWith(".")) lowerPath.endsWith(filter) else lowerPath.contains(filter)
            if (matches) return true
        }
        return false
    }

    /** Repopulates the host selector from persisted state after load. */
    fun refreshHostList() {
        SwingUtilities.invokeLater {
            controlPanelComponent.setHosts(StorageModule.getHosts().toList())
        }
    }

    /** Supplied by the extension: backfills the store and returns items processed. */
    fun setHistoryImporter(block: () -> Int) {
        historyImporter = block
    }

    private fun runHistoryImport() {
        val importer = historyImporter ?: return
        controlPanelComponent.setImportBusy(true)
        // Off the EDT: importer only touches the (thread-safe) store; we rebuild once
        // it finishes so a large history doesn't post per-endpoint UI updates.
        Thread({
            val count = try {
                importer()
            } catch (_: Exception) {
                -1
            }
            SwingUtilities.invokeLater {
                controlPanelComponent.setImportBusy(false)
                StorageModule.getHosts().forEach { controlPanelComponent.ensureHost(it) }
                rebuildForHost(controlPanelComponent.selectedHost())
                if (count >= 0) {
                    JOptionPane.showMessageDialog(
                        mainPanel, "Imported $count proxy history items into the current scope."
                    )
                } else {
                    JOptionPane.showMessageDialog(mainPanel, "Proxy history import failed.")
                }
            }
        }, "blindspot-history-import").apply { isDaemon = true }.start()
    }

    /**
     * Live proxy request observed.
     * @param isNewPath    first time this (host, path) is seen at all
     * @param newlyVisited transitioned from unvisited → visited
     */
    fun logVisitedRequest(host: String, path: String, isNewPath: Boolean, newlyVisited: Boolean) {
        SwingUtilities.invokeLater {
            controlPanelComponent.ensureHost(host)
            if (host != controlPanelComponent.selectedHost()) return@invokeLater
            if (isExcluded(path)) return@invokeLater // captured, just hidden by the filter

            if (newlyVisited) {
                tableComponent.addVisitedRow(path) // left pane always lists visited
                if (controlPanelComponent.isUnvisitedOnly()) {
                    // Now visited — drop it from the Discovered pane.
                    tableComponent.removeDiscoveredRow(path)
                } else {
                    // Proxy-observed path with no JS provenance (source stays blank).
                    if (isNewPath) tableComponent.addDiscoveredRow(path, null)
                    tableComponent.refresh() // flip the discovered row red → green live
                }
            }
        }
    }

    /** An endpoint extracted from a JS body (new, or gaining another source). */
    fun logDiscoveredEndpoint(host: String, path: String) {
        SwingUtilities.invokeLater {
            controlPanelComponent.ensureHost(host)
            if (host != controlPanelComponent.selectedHost()) return@invokeLater
            if (isExcluded(path)) return@invokeLater // captured, just hidden by the filter
            if (controlPanelComponent.isUnvisitedOnly() && StorageModule.isVisited(host, path)) return@invokeLater
            tableComponent.upsertDiscoveredRow(path, StorageModule.getSources(host, path).joinToString(", "))
        }
    }

    private fun rebuildForHost(host: String?) {
        SwingUtilities.invokeLater {
            tableComponent.currentHost = host
            // "Show Path" reveals the JS source column on the discovered table.
            tableComponent.setSourceColumnVisible(controlPanelComponent.isShowPathEnabled())
            if (host == null) {
                tableComponent.clearTables()
                return@invokeLater
            }
            val paths = StorageModule.getPathsForHost(host)
            // Apply the exclude list as a view filter over the full captured set.
            val shown = paths.keys.filterNot { isExcluded(it) }
            val visited = shown.filter { paths[it] == true }.sorted()
            val discoveredPaths = if (controlPanelComponent.isUnvisitedOnly())
                shown.filter { paths[it] != true }.sorted()
            else
                shown.sorted()
            val discovered = discoveredPaths.map {
                it to StorageModule.getSources(host, it).joinToString(", ").ifEmpty { null }
            }
            tableComponent.rebuild(visited, discovered)
        }
    }

    fun getView(): Component = mainPanel
}
