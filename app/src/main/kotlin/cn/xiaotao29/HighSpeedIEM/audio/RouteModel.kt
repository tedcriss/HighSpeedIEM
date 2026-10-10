package cn.xiaotao29.HighSpeedIEM.audio

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Publish the entire observable table atomically after an edit. Rows and old
 * lists never change, so a cached LazyColumn always has consistent contents.
 * Only this model can add, remove or update a row.
 */
class RouteModel {
    @Immutable
    @ConsistentCopyVisibility
    data class Row internal constructor(val id: Long, val input: String?, val output: String?) {
        fun blank() = input == null && output == null
        fun complete() = input != null && output != null
    }

    private data class Table(val rows: List<Row>, val compactRowId: Long)

    private var nextId = 0L
    private fun blankRow() = Row(++nextId, null, null)
    private val initialRow = blankRow()
    private var table by mutableStateOf(Table(listOf(initialRow), initialRow.id))

    val rows: List<Row> get() = table.rows
    val compactRow: Row get() = table.rows.first { it.id == table.compactRowId }

    /** Resolve against current state; late callbacks cannot resurrect deleted rows. */
    fun setSide(rowId: Long, input: Boolean, value: String?): Boolean {
        val current = rows.firstOrNull { it.id == rowId } ?: return false
        if ((if (input) current.input else current.output) == value) return false
        val updated = if (input) current.copy(input = value) else current.copy(output = value)
        publish(rows.map { if (it.id == rowId) updated else it })
        return true
    }

    fun setCompactSide(input: Boolean, value: String?): Boolean =
        setSide(compactRow.id, input, value)

    /** Device removal/disable removes its entire pair, including partial rows. */
    fun prune(inputs: Set<String>, outputs: Set<String>) {
        publish(rows.filter { row ->
            (row.input == null || row.input in inputs) &&
                (row.output == null || row.output in outputs)
        })
    }

    fun clear() = publish(rows.takeLast(1).filter { it.blank() })

    fun validCount(): Int = rows.count { it.complete() }

    /** Keep partial rows, first occurrences of complete pairs, and one trailing blank. */
    private fun publish(candidate: List<Row>) {
        val insertion = candidate.lastOrNull()?.takeIf { it.blank() } ?: blankRow()
        val seen = mutableSetOf<Pair<String?, String?>>()
        val configured = candidate.filter { row ->
            !row.blank() && (!row.complete() || seen.add(row.input to row.output))
        }
        val normalized = configured + insertion
        val binding = table.compactRowId.takeIf { id -> normalized.any { it.id == id } }
            ?: insertion.id
        table = Table(normalized, binding)
    }
}
