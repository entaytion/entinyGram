package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiSummarySettingsActivity : SettingsPageActivity() {

    private var temperatureBar: AvatarCornerPreviewCell.AltSeekbar? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiSummary)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val provider = AiFeatureSupport.effectiveProvider(InuConfig.AI_SUMMARY_PROVIDER_ID.value, false)
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuAiSummary,
                R.string.InuAiSummaryInfo,
                InuConfig.AI_SUMMARY_ENABLED.value,
            )
        )
        items.add(UItem.asShadow(if (provider == null) LocaleController.getString(R.string.InuAiAddProviderFirst) else null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionProvider)))
        items.add(
            UItem.asButton(
                BUTTON_PROVIDER,
                LocaleController.getString(R.string.InuAiFeatureProvider),
                AiFeatureSupport.providerLabel(InuConfig.AI_SUMMARY_PROVIDER_ID.value, false),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MODEL,
                LocaleController.getString(R.string.InuAiTranscribeModel),
                AiFeatureSupport.modelLabel(InuConfig.AI_SUMMARY_MODEL.value, provider, false),
            ).setEnabled(provider != null)
        )
        items.add(UItem.asShadow(null))

        val ctx = context ?: return
        val bar = temperatureBar ?: AvatarCornerPreviewCell.AltSeekbar(
            ctx,
            onDrag = { value, _ -> InuConfig.AI_SUMMARY_TEMPERATURE.value = Math.round(value) / 10f },
            min = 0,
            max = 20,
            header = LocaleController.getString(R.string.InuAiSectionTemperature),
            leftText = "0.0",
            rightText = "2.0",
            formatValue = { String.format(java.util.Locale.US, "%.1f", it / 10f) },
        ).also {
            it.setProgress(AiComposeHelper.temperatureFor(AiComposeHelper.Feature.SUMMARY) / 2f)
            temperatureBar = it
        }
        items.add(UItem.asCustom(bar))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiSummaryTemperatureInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_SUMMARY_ENABLED.toggle()
            BUTTON_PROVIDER -> AiFeatureSupport.pickProvider(this, false, InuConfig.AI_SUMMARY_PROVIDER_ID.value) {
                InuConfig.AI_SUMMARY_PROVIDER_ID.value = it
                InuConfig.AI_SUMMARY_MODEL.value = ""
                listView?.adapter?.update(true)
            }
            BUTTON_MODEL -> AiFeatureSupport.pickModel(
                this,
                AiFeatureSupport.effectiveProvider(InuConfig.AI_SUMMARY_PROVIDER_ID.value, false),
                false,
                InuConfig.AI_SUMMARY_MODEL.value,
            ) {
                InuConfig.AI_SUMMARY_MODEL.value = it
            }
        }
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_PROVIDER = InuUtils.generateId()
        private val BUTTON_MODEL = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-summary-page",
            titleRes = R.string.InuAiSummary,
            iconRes = R.drawable.msg_text_outlined,
            factory = ::AiSummarySettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-summary", R.string.InuAiSummary, TOGGLE_ENABLED),
                SearchRegistry.Entry("ai-summary-provider", R.string.InuAiFeatureProvider, BUTTON_PROVIDER),
                SearchRegistry.Entry("ai-summary-model", R.string.InuAiTranscribeModel, BUTTON_MODEL),
            ),
        )
    }
}
