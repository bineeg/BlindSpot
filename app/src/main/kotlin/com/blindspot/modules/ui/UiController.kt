package com.blindspot.modules.ui

import com.blindspot.modules.engine.StorageModule
import com.blindspot.modules.engine.UrlModule
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JFileChooser
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
        controlPanelComponent.onLoadWordlist = { runWordlistLoad() }
        controlPanelComponent.onImport = { runHistoryImport() }
        controlPanelComponent.onClear = { host ->
            StorageModule.clear(host)
            if (host == null) controlPanelComponent.setHosts(emptyList())
            else controlPanelComponent.removeHost(host)
            rebuildForHost(controlPanelComponent.selectedHost())
        }

        tableComponent.onToggleIgnore = { paths ->
            val host = controlPanelComponent.selectedHost()
            if (host != null) {
                for (path in paths) {
                    val isCurrentlyIgnored = StorageModule.isIgnored(host, path)
                    StorageModule.setIgnored(host, path, !isCurrentlyIgnored)
                }
                rebuildForHost(host)
            }
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

    private fun runWordlistLoad() {
        val host = controlPanelComponent.selectedHost()
        if (host == null) {
            JOptionPane.showMessageDialog(mainPanel, "Please select or visit a host first before loading a wordlist.")
            return
        }

        val chooser = JFileChooser()
        if (chooser.showOpenDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return

        Thread({
            var addedCount = 0
            var skippedCount = 0
            try {
                file.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine
                    
                    // Split path and sources by the first whitespace character (space or tab)
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    val rawPath = parts[0].trim()
                    val sourcesList = if (parts.size > 1) {
                        parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }

                    val cleanPath = UrlModule.normalizePath(rawPath)
                    val result = StorageModule.registerWordlistEntry(host, cleanPath, sourcesList)
                    if (result) {
                        addedCount++
                    } else {
                        skippedCount++
                    }
                }
                
                SwingUtilities.invokeLater {
                    rebuildForHost(controlPanelComponent.selectedHost())
                    JOptionPane.showMessageDialog(
                        mainPanel,
                        "URLs loaded for $host:\n- Added $addedCount new endpoints\n- Skipped $skippedCount already known/visited endpoints"
                    )
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(mainPanel, "Error loading endpoints: ${e.message}")
                }
            }
        }, "blindspot-wordlist-load").apply { isDaemon = true }.start()
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
                if (controlPanelComponent.isIgnoredOnly()) {
                    // If not ignored, remove it from the Discovered pane.
                    if (!StorageModule.isIgnored(host, path)) {
                        tableComponent.removeDiscoveredRow(path)
                    }
                } else if (controlPanelComponent.isUnvisitedOnly()) {
                    // Now visited — drop it from the Discovered pane.
                    tableComponent.removeDiscoveredRow(path)
                } else if (controlPanelComponent.isWordlistOnly()) {
                    // Now visited — drop it from the Discovered pane.
                    tableComponent.removeDiscoveredRow(path)
                } else {
                    // Proxy-observed path with no JS provenance (source stays blank).
                    if (isNewPath) tableComponent.addDiscoveredRow(path, null)
                    tableComponent.refresh() // flip the discovered row color live
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
            if (controlPanelComponent.isIgnoredOnly() && !StorageModule.isIgnored(host, path)) return@invokeLater
            if (controlPanelComponent.isUnvisitedOnly() && (StorageModule.isVisited(host, path) || StorageModule.isIgnored(host, path))) return@invokeLater
            if (controlPanelComponent.isWordlistOnly() && (!StorageModule.isWordlist(host, path) || StorageModule.isVisited(host, path) || StorageModule.isIgnored(host, path))) return@invokeLater
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
            val discoveredPaths = when {
                controlPanelComponent.isIgnoredOnly() ->
                    shown.filter { StorageModule.isIgnored(host, it) }.sorted()
                controlPanelComponent.isUnvisitedOnly() ->
                    shown.filter { paths[it] != true && !StorageModule.isIgnored(host, it) && !StorageModule.isWordlist(host, it) }.sorted()
                controlPanelComponent.isWordlistOnly() ->
                    shown.filter { StorageModule.isWordlist(host, it) && paths[it] != true && !StorageModule.isIgnored(host, it) }.sorted()
                else ->
                    shown.sorted()
            }
            val discovered = discoveredPaths.map {
                it to StorageModule.getSources(host, it).joinToString(", ").ifEmpty { null }
            }
            tableComponent.rebuild(visited, discovered)
        }
    }

    fun getView(): Component = mainPanel
}
