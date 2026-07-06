package com.blindspot.modules.ui

import com.blindspot.modules.engine.StorageModule
import java.awt.FlowLayout
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Top control bar. Owns the host selector and exposes callbacks that
 * UiController wires to rebuild/clear logic, keeping all map access in one place.
 */
class ControlPanelComponent {
    companion object {
        private const val FILTER_ALL = "Show All"
        private const val FILTER_UNVISITED = "Unvisited"
        private const val FILTER_IGNORED = "Ignored"
        private const val FILTER_WORDLIST = "Wordlist"
    }

    val container = JPanel(FlowLayout(FlowLayout.LEFT))

    val scopeToggle = JToggleButton("ENABLED: OFF", false)

    private val hostLabel = JLabel("Host:")
    val hostSelector = JComboBox<String>()

    private val excludeLabel = JLabel("Exclude:")
    val excludeField = JTextField(".png, .css, .jpg, .jpeg, .gif, .json", 16).apply {
        toolTipText = "View filter only — everything in scope is still captured. " +
            "Comma-separated. Dotted entries (.png) match file extensions; others match anywhere in the path."
    }

    private val showPathCheckbox = JCheckBox("Show Path", true).apply {
        toolTipText = "Show the JavaScript file each discovered endpoint was found in."
    }
    private val filterLabel = JLabel("Discovered:")
    private val filterSelector = JComboBox(arrayOf(FILTER_ALL, FILTER_UNVISITED, FILTER_IGNORED, FILTER_WORDLIST)).apply {
        toolTipText = "Filter the Discovered pane. Visited paths already appear in the left pane."
    }
    private val loadWordlistButton = JButton("Load URLs from other sources").apply {
        toolTipText = "Load list of API endpoints from other URL miner tools"
    }
    private val importButton = JButton("Scan Existing Traffic").apply {
        toolTipText = "Scan the traffic already in Burp's Proxy history (current scope) and backfill BlindSpot. " +
            "Use when BlindSpot was loaded after browsing already started."
    }
    private val clearButton = JButton("Clear")
    private val exportButton = JButton("Export")

    /** Fired when the user picks a different host (or the selection is cleared). */
    var onHostSelected: ((String?) -> Unit)? = null
    /** Fired by view-affecting controls (filters, Show Path) to rebuild the view. */
    var onRefresh: (() -> Unit)? = null
    /** Fired by Load Wordlist to seed the store from a local file. */
    var onLoadWordlist: (() -> Unit)? = null
    /** Fired by Import Proxy History to backfill the store from Burp's history. */
    var onImport: (() -> Unit)? = null
    /** Fired by Clear; argument is the host to clear (null = everything). */
    var onClear: ((String?) -> Unit)? = null

    // Suppresses selection callbacks while we repopulate the combo programmatically.
    private var suppressEvents = false

    init {
        scopeToggle.toolTipText =
            "Master switch. When ON, only traffic inside Burp Target → Scope is captured. Configure Target → Scope first."
        scopeToggle.addActionListener {
            scopeToggle.text = if (scopeToggle.isSelected) "ENABLED: ON" else "ENABLED: OFF"
        }

        hostSelector.toolTipText = "Select which host's discovered endpoints to view."
        hostSelector.addActionListener {
            if (!suppressEvents) onHostSelected?.invoke(selectedHost())
        }

        // Toggles re-filter the current view immediately.
        showPathCheckbox.addActionListener { onRefresh?.invoke() }
        filterSelector.addActionListener { onRefresh?.invoke() }

        // Editing the exclude list re-applies the view filter live (data is never
        // dropped — only what's shown changes).
        excludeField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onRefresh?.invoke() ?: Unit
            override fun removeUpdate(e: DocumentEvent) = onRefresh?.invoke() ?: Unit
            override fun changedUpdate(e: DocumentEvent) = onRefresh?.invoke() ?: Unit
        })

        loadWordlistButton.addActionListener { onLoadWordlist?.invoke() }
        importButton.addActionListener { onImport?.invoke() }
        clearButton.addActionListener { onClear?.invoke(selectedHost()) }
        exportButton.addActionListener { exportSelectedHost() }

        container.add(scopeToggle)
        container.add(hostLabel)
        container.add(hostSelector)
        container.add(excludeLabel)
        container.add(excludeField)
        container.add(showPathCheckbox)
        container.add(filterLabel)
        container.add(filterSelector)
        container.add(loadWordlistButton)
        container.add(importButton)
        container.add(clearButton)
        container.add(exportButton)
    }

    fun selectedHost(): String? = hostSelector.selectedItem as String?

    /** Disables the import button and shows progress text while a backfill runs. */
    fun setImportBusy(busy: Boolean) {
        importButton.isEnabled = !busy
        importButton.text = if (busy) "Scanning…" else "Scan Existing Traffic"
    }

    /** Adds a newly seen host to the selector (no-op if already present). */
    fun ensureHost(host: String) {
        for (i in 0 until hostSelector.itemCount) {
            if (hostSelector.getItemAt(i) == host) return
        }
        hostSelector.addItem(host) // first item auto-selects → fires onHostSelected
    }

    fun removeHost(host: String) = hostSelector.removeItem(host)

    /** Replaces the full host list (used after loading persisted state). */
    fun setHosts(hosts: List<String>) {
        suppressEvents = true
        hostSelector.removeAllItems()
        for (h in hosts) hostSelector.addItem(h)
        suppressEvents = false
        if (hostSelector.itemCount > 0) hostSelector.selectedIndex = 0 else onHostSelected?.invoke(null)
    }

    fun isShowPathEnabled(): Boolean = showPathCheckbox.isSelected

    /** True when the Discovered pane should show only unvisited endpoints. */
    fun isUnvisitedOnly(): Boolean = filterSelector.selectedItem == FILTER_UNVISITED

    /** True when the Discovered pane should show only ignored endpoints. */
    fun isIgnoredOnly(): Boolean = filterSelector.selectedItem == FILTER_IGNORED

    /** True when the Discovered pane should show only wordlist endpoints. */
    fun isWordlistOnly(): Boolean = filterSelector.selectedItem == FILTER_WORDLIST

    private fun exportSelectedHost() {
        val host = selectedHost()
        if (host == null) {
            JOptionPane.showMessageDialog(container, "No host selected to export.")
            return
        }
        val paths = StorageModule.getPathsForHost(host)
        if (paths.isEmpty()) {
            JOptionPane.showMessageDialog(container, "Nothing discovered for $host yet.")
            return
        }

        val chooser = JFileChooser()
        chooser.selectedFile = File("blindspot-${host}.json")
        if (chooser.showSaveDialog(container) != JFileChooser.APPROVE_OPTION) return

        val visited = paths.filterValues { it }.keys.sorted()
        val missing = paths.filterValues { !it }.keys.sorted()
        val json = buildString {
            append("{\n")
            append("  \"host\": ${quote(host)},\n")
            append("  \"visited\": [\n")
            append(visited.joinToString(",\n") { "    ${quote(it)}" })
            append("\n  ],\n")
            append("  \"missing\": [\n")
            append(missing.joinToString(",\n") { "    ${quote(it)}" })
            append("\n  ]\n")
            append("}\n")
        }
        try {
            chooser.selectedFile.writeText(json)
            JOptionPane.showMessageDialog(
                container, "Exported ${visited.size} visited / ${missing.size} missing for $host."
            )
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(container, "Export failed: ${e.message}")
        }
    }

    private fun quote(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
