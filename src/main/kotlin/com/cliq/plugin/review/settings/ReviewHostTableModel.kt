package com.cliq.plugin.review.settings

import javax.swing.table.AbstractTableModel

class ReviewHostRow(
    val original: ReviewHost?,
    val host: ReviewHost,
    val newToken: String? = null,
    val forgetToken: Boolean = false,
) {
    val identityChanged: Boolean
        get() = original != null && (original.url != host.url || original.kind != host.kind)

    val isPending: Boolean
        get() = original == null || identityChanged || newToken != null || forgetToken

    val tokenState: String
        get() = when {
            newToken != null -> "Will be saved"
            forgetToken -> "Will be removed"
            identityChanged && original?.tokenStored == true -> "Will be moved"
            host.tokenStored -> "Stored"
            else -> "None"
        }
}

class ReviewHostTableModel : AbstractTableModel() {

    private companion object {
        val COLUMNS = arrayOf("Provider", "Host URL", "Token")
    }

    private val rows = mutableListOf<ReviewHostRow>()

    fun rows(): List<ReviewHostRow> = rows.toList()

    fun rowAt(index: Int): ReviewHostRow = rows[index]

    fun urls(): Set<String> = rows.map { it.host.url }.toSet()

    fun replaceAll(hosts: List<ReviewHost>) {
        val copy = hosts.map { ReviewHostRow(it, it) }
        rows.clear()
        rows.addAll(copy)
        fireTableDataChanged()
    }

    fun add(row: ReviewHostRow) {
        rows.add(row)
        fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
    }

    fun update(index: Int, row: ReviewHostRow) {
        rows[index] = row
        fireTableRowsUpdated(index, index)
    }

    fun remove(index: Int) {
        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.host.kind.displayName
            1 -> row.host.url
            else -> row.tokenState
        }
    }
}
