package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import desu.inugram.helpers.theme.M3SectionsHelper

class SectionRowsLinearLayout(context: Context) : LinearLayout(context) {
    override fun dispatchDraw(canvas: Canvas) {
        M3SectionsHelper.drawOwnSectionsBackgrounds(canvas, this)
        super.dispatchDraw(canvas)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        canvas.save()
        M3SectionsHelper.clipOwnSectionChild(canvas, this, child)
        val r = super.drawChild(canvas, child, drawingTime)
        canvas.restore()
        return r
    }
}
