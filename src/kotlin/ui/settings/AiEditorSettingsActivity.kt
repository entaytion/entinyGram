package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiRolesHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiEditorSettingsActivity : SettingsPageActivity() {

    private var temperatureBar: AvatarCornerPreviewCell.AltSeekbar? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiEditorTitle)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    private fun reasoningLabel(): String = when {
        !InuConfig.AI_REASONING_ENABLED.value -> LocaleController.getString(R.string.PasswordOff)
        InuConfig.AI_REASONING_EFFORT.value == "low" -> LocaleController.getString(R.string.InuAiReasoningLow)
        InuConfig.AI_REASONING_EFFORT.value == "high" -> LocaleController.getString(R.string.InuAiReasoningHigh)
        else -> LocaleController.getString(R.string.InuAiReasoningMedium)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val provider = AiFeatureSupport.effectiveProvider(InuConfig.AI_EDITOR_PROVIDER_ID.value, false)
        val ready = AiComposeHelper.useOwnEditor()
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuAiEditorTitle,
                if (ready) R.string.InuAiEditorButtonInfo else R.string.InuAiEditorButtonSetupInfo,
                !InuConfig.HIDE_AI_EDITOR.value,
            )
        )
        items.add(UItem.asShadow(if (provider == null) LocaleController.getString(R.string.InuAiAddProviderFirst) else null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionProvider)))
        items.add(
            UItem.asButton(
                BUTTON_PROVIDER,
                LocaleController.getString(R.string.InuAiFeatureProvider),
                AiFeatureSupport.providerLabel(InuConfig.AI_EDITOR_PROVIDER_ID.value, false),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MODEL,
                LocaleController.getString(R.string.InuAiTranscribeModel),
                AiFeatureSupport.modelLabel(InuConfig.AI_EDITOR_MODEL.value, provider, false),
            ).setEnabled(provider != null)
        )
        items.add(
            UItem.asButton(
                BUTTON_ROLE,
                LocaleController.getString(R.string.InuAiRoles),
                AiRolesHelper.activeRoleText().trim().ifEmpty { LocaleController.getString(R.string.InuAiRolesAssistant) },
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionGeneration)))
        items.add(mkTwoLineCheckItem(TOGGLE_STREAM, R.string.InuAiStream, R.string.InuAiStreamInfo, InuConfig.AI_STREAM_ENABLED.value))
        items.add(mkTwoLineCheckItem(TOGGLE_ONLY_ANSWER, R.string.InuAiOnlyAnswer, R.string.InuAiOnlyAnswerInfo, InuConfig.AI_ONLY_ANSWER.value))
        items.add(mkTwoLineCheckItem(TOGGLE_QUOTE, R.string.InuAiInsertQuote, R.string.InuAiInsertQuoteInfo, InuConfig.AI_INSERT_QUOTE.value))
        items.add(UItem.asButton(BUTTON_REASONING, LocaleController.getString(R.string.InuAiReasoning), reasoningLabel()))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiReasoningInfo)))

        val ctx = context ?: return
        val bar = temperatureBar ?: AvatarCornerPreviewCell.AltSeekbar(
            ctx,
            onDrag = { value, _ -> InuConfig.AI_TEMPERATURE.value = Math.round(value) / 10f },
            min = 0,
            max = 20,
            header = LocaleController.getString(R.string.InuAiSectionTemperature),
            leftText = "0.0",
            rightText = "2.0",
            formatValue = { String.format(java.util.Locale.US, "%.1f", it / 10f) },
        ).also {
            it.setProgress(InuConfig.AI_TEMPERATURE.value / 2f)
            temperatureBar = it
        }
        items.add(UItem.asCustom(bar))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiTemperatureInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val hidden = InuConfig.HIDE_AI_EDITOR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = !hidden
            }
            BUTTON_PROVIDER -> AiFeatureSupport.pickProvider(this, false, InuConfig.AI_EDITOR_PROVIDER_ID.value) {
                InuConfig.AI_EDITOR_PROVIDER_ID.value = it
                InuConfig.AI_EDITOR_MODEL.value = ""
                listView?.adapter?.update(true)
            }
            BUTTON_MODEL -> AiFeatureSupport.pickModel(
                this,
                AiFeatureSupport.effectiveProvider(InuConfig.AI_EDITOR_PROVIDER_ID.value, false),
                false,
                InuConfig.AI_EDITOR_MODEL.value,
            ) {
                InuConfig.AI_EDITOR_MODEL.value = it
            }
            BUTTON_ROLE -> presentFragment(AiRolesSettingsActivity())
            TOGGLE_STREAM -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_STREAM_ENABLED.toggle()
            TOGGLE_ONLY_ANSWER -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_ONLY_ANSWER.toggle()
            TOGGLE_QUOTE -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_INSERT_QUOTE.toggle()
            BUTTON_REASONING -> {
                val ctx = parentActivity ?: return
                val labels = arrayOf<CharSequence>(
                    LocaleController.getString(R.string.PasswordOff),
                    LocaleController.getString(R.string.InuAiReasoningLow),
                    LocaleController.getString(R.string.InuAiReasoningMedium),
                    LocaleController.getString(R.string.InuAiReasoningHigh),
                )
                val efforts = arrayOf("", "low", "medium", "high")
                val selected = if (!InuConfig.AI_REASONING_ENABLED.value) 0 else efforts.indexOf(InuConfig.AI_REASONING_EFFORT.value).coerceAtLeast(2)
                showDialog(
                    RadioDialogBuilder(ctx, resourceProvider)
                        .setTitle(LocaleController.getString(R.string.InuAiReasoning))
                        .setItems(labels, selected) { dialog, which ->
                            if (which == 0) {
                                InuConfig.AI_REASONING_ENABLED.value = false
                            } else {
                                InuConfig.AI_REASONING_ENABLED.value = true
                                InuConfig.AI_REASONING_EFFORT.value = efforts[which]
                            }
                            dialog.dismiss()
                            listView?.adapter?.update(true)
                        }
                        .create()
                )
            }
        }
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_PROVIDER = InuUtils.generateId()
        private val BUTTON_MODEL = InuUtils.generateId()
        private val BUTTON_ROLE = InuUtils.generateId()
        private val TOGGLE_STREAM = InuUtils.generateId()
        private val TOGGLE_ONLY_ANSWER = InuUtils.generateId()
        private val TOGGLE_QUOTE = InuUtils.generateId()
        private val BUTTON_REASONING = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-editor",
            titleRes = R.string.InuAiEditorTitle,
            iconRes = R.drawable.inu_tabler_sparkles,
            factory = ::AiEditorSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-editor-button", R.string.InuAiEditorTitle, TOGGLE_ENABLED),
                SearchRegistry.Entry("ai-editor-provider", R.string.InuAiFeatureProvider, BUTTON_PROVIDER),
                SearchRegistry.Entry("ai-editor-model", R.string.InuAiTranscribeModel, BUTTON_MODEL),
                SearchRegistry.Entry("ai-role", R.string.InuAiRoles, BUTTON_ROLE),
                SearchRegistry.Entry("ai-stream", R.string.InuAiStream, TOGGLE_STREAM),
                SearchRegistry.Entry("ai-only-answer", R.string.InuAiOnlyAnswer, TOGGLE_ONLY_ANSWER),
                SearchRegistry.Entry("ai-insert-quote", R.string.InuAiInsertQuote, TOGGLE_QUOTE),
                SearchRegistry.Entry("ai-reasoning", R.string.InuAiReasoning, BUTTON_REASONING),
            ),
        )
    }
}
