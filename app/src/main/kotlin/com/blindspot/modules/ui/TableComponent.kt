package com.blindspot.modules.ui

import com.blindspot.modules.engine.StorageModule
import java.awt.BorderLayout
import java.awt.Component
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableColumn
import javax.swing.table.TableRowSorter

/**
 * Two side-by-side tables showing a single host's coverage at a time:
 *   left  = paths actually visited through the proxy
 *   right = all discovered endpoints (JS-referenced + observed), colored by
 *           visited state so JS-referenced-but-never-hit routes show red. Its
 *           second column names the JS file each endpoint was found in and is
 *           shown/hidden by the "Show Path" toggle.
 *
 * All public methods assume they are invoked on the Swing EDT — callers
 * (UiController) marshal onto it.
 */
class TableComponent {
    val container = JPanel(BorderLayout())

    /** The host whose data is currently rendered; drives the color renderer. */
    var currentHost: String? = null

    private val leftModel = RefreshableModel(arrayOf("Visited Paths (Proxy Traffic)"))
    private val leftTable = JTable(leftModel)

    private val rightModel = RefreshableModel(arrayOf("Discovered Endpoint", "Found In (JS Source)"))
    private val rightTable = JTable(rightModel)

    // Row sorters back the per-table search filters (and enable header sorting).
    private val leftSorter = TableRowSorter(leftModel)
    private val rightSorter = TableRowSorter(rightModel)

    // Holds the source column while it is hidden so it can be re-added intact.
    private var hiddenSourceColumn: TableColumn? = null

    init {
        val customRenderer = ColoredRowRenderer()
        leftTable.setDefaultRenderer(Any::class.java, customRenderer)
        rightTable.setDefaultRenderer(Any::class.java, customRenderer)
        leftTable.rowSorter = leftSorter
        rightTable.rowSorter = rightSorter

        // Draggable divider between the two panels; individual JTable columns
        // remain resizable by dragging their header borders.
        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            searchable(leftTable, "Search visited paths…") { leftSorter.rowFilter = filterFor(it) },
            searchable(rightTable, "Search discovered endpoints…") { rightSorter.rowFilter = filterFor(it) }
        ).apply {
            resizeWeight = 0.5          // both sides grow/shrink evenly on resize
            isContinuousLayout = true   // repaint tables live while dragging
            isOneTouchExpandable = true // quick collapse arrows on the divider
            dividerSize = 14            // thicker divider → larger, clearer arrows
        }
        container.add(splitPane, BorderLayout.CENTER)
    }

    /** Wraps a table in a scroll pane with a live search field above it. */
    private fun searchable(table: JTable, hint: String, onQuery: (String) -> Unit): JComponent {
        // Fixed-width field, left-aligned, so it doesn't stretch to the split-pane
        // divider and crowd the one-touch collapse arrows.
        val field = JTextField(16).apply { toolTipText = hint }
        field.document.addDocumentListener(object : DocumentListener {
            private fun apply() = onQuery(field.text.trim())
            override fun insertUpdate(e: DocumentEvent) = apply()
            override fun removeUpdate(e: DocumentEvent) = apply()
            override fun changedUpdate(e: DocumentEvent) = apply()
        })

        val top = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
            // Left inset keeps "Search:" clear of the split-pane divider arrows.
            border = BorderFactory.createEmptyBorder(2, 12, 2, 6)
            add(JLabel("Search:"))
            add(field)
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
        }
    }

    // Case-insensitive substring filter across all columns; null shows everything.
    private fun filterFor(query: String): RowFilter<Any, Any>? =
        if (query.isEmpty()) null else RowFilter.regexFilter("(?i)" + Pattern.quote(query))

    fun addVisitedRow(cleanPath: String) = leftModel.addRow(arrayOf(cleanPath))

    fun addDiscoveredRow(cleanPath: String, source: String?) =
        rightModel.addRow(arrayOf(cleanPath, source ?: ""))

    /**
     * Adds a discovered endpoint, or updates its source cell if the row already
     * exists — so a bundle referencing a known endpoint aggregates rather than
     * duplicating the row.
     */
    fun upsertDiscoveredRow(cleanPath: String, source: String) {
        for (r in 0 until rightModel.rowCount) {
            if (rightModel.getValueAt(r, 0) == cleanPath) {
                rightModel.setValueAt(source, r, 1)
                return
            }
        }
        rightModel.addRow(arrayOf(cleanPath, source))
    }

    /** Removes a discovered endpoint row, if present (e.g. once it's visited). */
    fun removeDiscoveredRow(cleanPath: String) {
        for (r in rightModel.rowCount - 1 downTo 0) {
            if (rightModel.getValueAt(r, 0) == cleanPath) rightModel.removeRow(r)
        }
    }

    /** Replaces both tables' contents with the supplied per-host snapshots. */
    fun rebuild(visitedPaths: List<String>, discovered: List<Pair<String, String?>>) {
        leftModel.rowCount = 0
        for (p in visitedPaths) leftModel.addRow(arrayOf(p))
        rightModel.rowCount = 0
        for ((path, source) in discovered) rightModel.addRow(arrayOf(path, source ?: ""))
    }

    /** Shows or hides the "Found In (JS Source)" column without dropping data. */
    fun setSourceColumnVisible(visible: Boolean) {
        val colModel = rightTable.columnModel
        if (visible) {
            hiddenSourceColumn?.let {
                colModel.addColumn(it)
                hiddenSourceColumn = null
            }
        } else if (colModel.columnCount >= 2) {
            val col = colModel.getColumn(1)
            hiddenSourceColumn = col
            colModel.removeColumn(col)
        }
    }

    /** Re-fires the data-changed event so the renderer re-evaluates colors. */
    fun refresh() {
        leftModel.refresh()
        rightModel.refresh()
    }

    fun clearTables() {
        leftModel.rowCount = 0
        rightModel.rowCount = 0
    }

    // --- Model exposing the (otherwise protected) data-changed fire + read-only cells ---
    private class RefreshableModel(columns: Array<String>) : DefaultTableModel(columns, 0) {
        fun refresh() = fireTableDataChanged()
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    // --- INNER CLASS: Custom Color Highlight Cell Renderer ---
    private inner class ColoredRowRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val cell = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

            // Always color a row by its endpoint (view column 0), so the source
            // column shares the row's visited color.
            val pathValue = table.getValueAt(row, 0)?.toString() ?: ""
            val host = currentHost

            if (!isSelected) {
                if (host != null && StorageModule.isVisited(host, pathValue)) {
                    cell.background = java.awt.Color(220, 245, 220) // Visited is Green
                    cell.foreground = java.awt.Color(0, 100, 0)
                } else {
                    cell.background = java.awt.Color(255, 220, 220) // Unvisited is Red
                    cell.foreground = java.awt.Color(150, 0, 0)
                }
            }
            return cell
        }
    }
}
