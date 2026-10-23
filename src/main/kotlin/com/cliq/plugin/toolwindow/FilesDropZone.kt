package com.cliq.plugin.toolwindow

import com.cliq.plugin.ui.CliqTheme
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.io.File
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class FilesDropZone(private val onFilesDropped: (List<VirtualFile>) -> Unit) : JPanel(BorderLayout()) {

    private var hovered = false
    private val label = JLabel(
        "<html><center>Drop files here<br/><span style='font-size:smaller'>or drag from Project View / Finder</span></center></html>",
        SwingConstants.CENTER,
    ).apply {
        foreground = CliqTheme.DROPZONE_TEXT
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12)
        preferredSize = Dimension(0, 160)

        add(label, BorderLayout.NORTH)
        DropTarget(this, DnDConstants.ACTION_COPY, Listener(), true)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = 16
            g2.color = if (hovered) CliqTheme.DROPZONE_HOVER_BG else CliqTheme.DROPZONE_IDLE_BG
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.color = CliqTheme.DROPZONE_BORDER
            g2.stroke = java.awt.BasicStroke(
                1.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
                4f, floatArrayOf(5f, 4f), 0f,
            )
            g2.drawRoundRect(1, 1, width - 3, height - 3, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private inner class Listener : DropTargetListener {
        override fun dragEnter(dtde: DropTargetDragEvent) {
            if (canImport(dtde.currentDataFlavors)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY)
                hovered = true
                repaint()
            } else dtde.rejectDrag()
        }

        override fun dragOver(dtde: DropTargetDragEvent) {
            if (canImport(dtde.currentDataFlavors)) dtde.acceptDrag(DnDConstants.ACTION_COPY) else dtde.rejectDrag()
        }

        override fun dropActionChanged(dtde: DropTargetDragEvent) = Unit
        override fun dragExit(dte: DropTargetEvent) { hovered = false; repaint() }

        override fun drop(dtde: DropTargetDropEvent) {
            hovered = false
            repaint()
            if (!canImport(dtde.currentDataFlavors)) {
                dtde.rejectDrop()
                return
            }
            dtde.acceptDrop(DnDConstants.ACTION_COPY)
            val collected = mutableListOf<VirtualFile>()
            val transferable = dtde.transferable
            try {
                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    list.forEach { f ->
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)?.let(collected::add)
                    }
                }
                val vfFlavor = findVirtualFileFlavor(transferable.transferDataFlavors)
                if (collected.isEmpty() && vfFlavor != null) {
                    val data = transferable.getTransferData(vfFlavor)
                    when (data) {
                        is Array<*> -> data.forEach { (it as? VirtualFile)?.let(collected::add) }
                        is VirtualFile -> collected.add(data)
                    }
                }
                dtde.dropComplete(true)
            } catch (t: Throwable) {
                dtde.dropComplete(false)
                return
            }
            if (collected.isNotEmpty()) onFilesDropped(collected.distinctBy { it.path })
        }

        private fun canImport(flavors: Array<DataFlavor>): Boolean =
            flavors.any { it == DataFlavor.javaFileListFlavor } ||
                findVirtualFileFlavor(flavors) != null

        private fun findVirtualFileFlavor(flavors: Array<DataFlavor>): DataFlavor? =
            flavors.firstOrNull {
                it.humanPresentableName.contains("VirtualFile", ignoreCase = true) ||
                    it.representationClass == VirtualFile::class.java ||
                    it.representationClass == Array<VirtualFile>::class.java
            }
    }
}
