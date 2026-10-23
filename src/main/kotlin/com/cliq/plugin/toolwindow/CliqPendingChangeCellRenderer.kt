package com.cliq.plugin.toolwindow

import com.cliq.plugin.ui.CliqTheme
import com.cliq.plugin.util.PathUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class CliqPendingChangeCellRenderer(
    private val basePath: String?,
) : JPanel(BorderLayout()), ListCellRenderer<String> {

    private val nameLabel = JBLabel().apply { font = CliqTheme.captionFont(font) }
    private val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

    private val acceptBtn = JLabel(AllIcons.Actions.Checked)
    private val rejectBtn = JLabel(AllIcons.Actions.Cancel)

    companion object {
        const val ACTION_ACCEPT = 0
        const val ACTION_REJECT = 1

        fun actionZones(): RowActionZones =
            RowActionZones(listOf(AllIcons.Actions.Checked, AllIcons.Actions.Cancel))
    }

    init {
        isOpaque = true
        border = JBUI.Borders.empty(4, 8)
        add(nameLabel, BorderLayout.CENTER)
        add(actionsPanel, BorderLayout.EAST)
        actionsPanel.add(acceptBtn)
        actionsPanel.add(rejectBtn)
    }

    override fun getListCellRendererComponent(
        list: JList<out String>, value: String, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val relPath = PathUtil.toRelativePosix(basePath, value) ?: value
        val fileName = value.substringAfterLast('/').substringAfterLast('\\')

        nameLabel.text = fileName
        nameLabel.icon = LocalFileSystem.getInstance().findFileByPath(value)?.fileType?.icon
            ?: FileTypeRegistry.getInstance().getFileTypeByFileName(fileName).icon
        this.toolTipText = relPath

        background = if (isSelected) CliqTheme.SURFACE_HOVER else CliqTheme.SURFACE
        nameLabel.foreground = if (isSelected) CliqTheme.PRIMARY_TEXT else CliqTheme.SECONDARY_TEXT

        actionsPanel.isVisible = isSelected
        return this
    }
}
