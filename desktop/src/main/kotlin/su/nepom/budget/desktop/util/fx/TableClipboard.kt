package su.nepom.budget.desktop.util.fx

import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

private const val CLIPBOARD_VALUE_KEY = "clipboardValue"

/**
 * Overrides the text used by [enableCopySelectionToClipboard] for this column, instead of its
 * regular (on-screen) cell value. Use this for columns whose displayed text isn't Excel-friendly,
 * e.g. money formatted with a grouping separator - Excel needs a plain number to paste it as one.
 */
fun <S> TableColumn<S, *>.setClipboardValue(extractor: (S) -> String) {
    properties[CLIPBOARD_VALUE_KEY] = extractor
}

@Suppress("UNCHECKED_CAST")
private fun <S> TableColumn<S, *>.clipboardValue(item: S): String {
    val extractor = properties[CLIPBOARD_VALUE_KEY] as? (S) -> String
    return extractor?.invoke(item) ?: getCellData(item)?.toString() ?: ""
}

// leaf columns only - a group column (with nested columns) has no cell value of its own, its
// name is instead used as a header prefix for its children
private fun <S> TableColumn<S, *>.leavesWithHeader(prefix: String = ""): List<Pair<String, TableColumn<S, *>>> {
    val header = if (prefix.isEmpty()) text else "$prefix $text"
    return if (columns.isEmpty()) listOf(header to this) else columns.flatMap { it.leavesWithHeader(header) }
}

private fun String.forClipboardCell() = replace('\t', ' ').replace("\r\n", " ").replace('\n', ' ')

/**
 * Adds Ctrl+C and a right-click "Копировать" item that copy the selected rows as tab-separated
 * text (one line per row, header first) - pastes into Excel / Google Sheets as a regular table.
 * Reads each leaf column's own cell value (or its [setClipboardValue] override), so the copied
 * text follows whatever columns are actually shown, including nested (grouped) columns.
 */
fun <S> TableView<S>.enableCopySelectionToClipboard() {
    val copyCombination = KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN)
    setOnKeyPressed { event ->
        if (copyCombination.match(event)) {
            copySelectionToClipboard()
            event.consume()
        }
    }
    contextMenu = ContextMenu(MenuItem("Копировать").apply {
        accelerator = copyCombination
        setOnAction { copySelectionToClipboard() }
    })
}

private fun <S> TableView<S>.copySelectionToClipboard() {
    val selected = selectionModel.selectedIndices.sorted().mapNotNull { items.getOrNull(it) }
    if (selected.isEmpty()) return
    val leaves = columns.flatMap { it.leavesWithHeader() }
    val header = leaves.joinToString("\t") { (title, _) -> title.forClipboardCell() }
    val body = selected.joinToString("\n") { item ->
        leaves.joinToString("\t") { (_, column) -> column.clipboardValue(item).forClipboardCell() }
    }
    Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString("$header\n$body") })
}
