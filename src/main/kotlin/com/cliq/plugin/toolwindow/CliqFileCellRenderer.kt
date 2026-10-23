package com.cliq.plugin.toolwindow

import com.cliq.plugin.ui.CliqTheme
import com.cliq.plugin.util.PathUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class CliqFileCellRenderer(
    private val basePath: String?,
) : JPanel(BorderLayout()), ListCellRenderer<VirtualFile> {

    private val nameLabel = JBLabel().apply { font = CliqTheme.captionFont(font) }
    private val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

    private val sendBtn = JLabel(AllIcons.Actions.Execute)
    private val deleteBtn = JLabel(AllIcons.Actions.GC)

    companion object {
        const val ACTION_SEND = 0
        const val ACTION_DELETE = 1

        fun actionZones(): RowActionZones =
            RowActionZones(listOf(AllIcons.Actions.Execute, AllIcons.Actions.GC))
    }

    init {
        isOpaque = true
        border = JBUI.Borders.empty(4, 8)
        add(nameLabel, BorderLayout.CENTER)
        add(actionsPanel, BorderLayout.EAST)
        actionsPanel.add(sendBtn)
        actionsPanel.add(deleteBtn)
    }

    override fun getListCellRendererComponent(
        list: JList<out VirtualFile>, value: VirtualFile, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val relPath = PathUtil.toRelativePosix(basePath, value.path) ?: value.name
        nameLabel.text = value.name
        this.toolTipText = relPath
        nameLabel.icon = value.fileType.icon

        background = if (isSelected) CliqTheme.SURFACE_HOVER else CliqTheme.SURFACE
        nameLabel.foreground = if (isSelected) CliqTheme.PRIMARY_TEXT else CliqTheme.SECONDARY_TEXT

        actionsPanel.isVisible = isSelected
        return this
    }
}
