package com.cliq.plugin.toolwindow

import com.intellij.util.ui.JBUI
import javax.swing.Icon

class RowActionZones(private val icons: List<Icon>) {

    companion object {
        const val ICON_GAP = 6
        const val TRAILING_INSET = 8
    }

    fun hitIndex(rowWidth: Int, x: Int): Int? {
        var right = rowWidth - JBUI.scale(TRAILING_INSET)
        for (index in icons.indices.reversed()) {
            val left = right - icons[index].iconWidth
            if (x in left..right) return index
            right = left - JBUI.scale(ICON_GAP)
        }
        return null
    }
}
