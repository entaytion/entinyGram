package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils

class NicheSettingsActivity : SettingsPageActivity() {
    private var angleSlider: SliderCell? = null
    private var intensitySlider: SliderCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuNicheSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNicheSettingsInfo)))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLiquidGlass)))
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_GLASS_GLARE,
                LocaleController.getString(R.string.InuDisableGlassGlare),
            ).setChecked(InuConfig.DISABLE_GLASS_GLARE.value)
        )
        if (angleSlider == null) {
            angleSlider = SliderCell(
                context, min = 0f, max = 360f,
                defaultValue = InuConfig.LIQUID_GLASS_ANGLE.default.toFloat(),
                initialValue = InuConfig.LIQUID_GLASS_ANGLE.value.toFloat(),
                step = 1f,
                title = LocaleController.getString(R.string.InuLiquidGlassAngle),
                format = { "${it.toInt()}°" },
                onChanged = {
                    InuConfig.LIQUID_GLASS_ANGLE.value = it.toInt()
                    refreshLiquidGlassEffects()
                },
            )
        } else {
            angleSlider?.updateColors()
        }
        items.add(UItem.asCustom(angleSlider))
        if (intensitySlider == null) {
            intensitySlider = SliderCell(
                context, min = 0f, max = 150f,
                defaultValue = InuConfig.LIQUID_GLASS_INTENSITY.default.toFloat(),
                initialValue = InuConfig.LIQUID_GLASS_INTENSITY.value.toFloat(),
                step = 1f,
                title = LocaleController.getString(R.string.InuLiquidGlassIntensity),
                format = { "${it.toInt()}%" },
                onChanged = {
                    InuConfig.LIQUID_GLASS_INTENSITY.value = it.toInt()
                    refreshLiquidGlassEffects()
                },
            )
        } else {
            intensitySlider?.updateColors()
        }
        items.add(UItem.asCustom(intensitySlider))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == TOGGLE_DISABLE_GLASS_GLARE) {
            (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_GLASS_GLARE.toggle()
        }
    }

    private fun refreshLiquidGlassEffects() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BlurredBackgroundDrawableRenderNode.inu_invalidateLiquidGlassDrawables()
        }
    }

    companion object {
        private val TOGGLE_DISABLE_GLASS_GLARE = InuUtils.generateId()
    }
}
