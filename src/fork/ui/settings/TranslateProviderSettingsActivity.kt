package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiModelsHelper
import desu.inugram.helpers.translate.engine.EntinyTranslate
import desu.inugram.helpers.translate.engine.TranslationProviders
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TranslateProviderSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslateProvider)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val provider = InuConfig.TRANSLATE_PROVIDER.value
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTranslateProviderSection)))
        items.add(UItem.asRadio(PROVIDER_BASE + TranslationProviders.PROVIDER_TELEGRAM, LocaleController.getString(R.string.InuTranslateProviderTelegram)).also { it.checked = provider == TranslationProviders.PROVIDER_TELEGRAM })
        for (p in TranslationProviders.all) {
            val name = if (p.isConfigured()) p.displayName() else TranslationProviders.missingConfiguration(p)
            items.add(UItem.asRadio(PROVIDER_BASE + p.id, name).also { it.checked = provider == p.id })
        }
        // entiny: providers that plugins registered, picked the same way
        TranslationProviders.plugins().forEachIndexed { index, p ->
            val key = p.pluginKey
            items.add(UItem.asRadio(PLUGIN_BASE + index, p.displayName()).also {
                it.checked = provider == TranslationProviders.PROVIDER_PLUGIN && InuConfig.TRANSLATION_PROVIDER.value == key
            })
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateProviderInfo)))

        when (provider) {
            TranslationProviders.PROVIDER_DEEPL -> {
                fieldRow(items, deeplKey)
                items.add(UItem.asShadow(null))
            }

            TranslationProviders.PROVIDER_LLM -> {
                fieldRow(items, llmUrl)
                items.add(UItem.asButton(BUTTON_DETECT_LLM, LocaleController.getString(R.string.InuAiProviderDetect)))
                fieldRow(items, llmKey)
                fieldRow(items, llmModel)
                items.add(UItem.asButton(BUTTON_PICK_MODEL, LocaleController.getString(R.string.InuAiModelPickerTitle)))
                fieldRow(items, llmPrompt)
                items.add(UItem.asShadow(null))

                // entiny: keep slider instances as fields so rebuilding items does not reset thumb mid-drag
                if (contextSlider == null) contextSlider = SliderCell(
                    context,
                    min = 0f,
                    max = 20f,
                    defaultValue = InuConfig.TRANSLATE_LLM_CONTEXT.default.toFloat(),
                    initialValue = InuConfig.TRANSLATE_LLM_CONTEXT.value.toFloat(),
                    step = 1f,
                    title = LocaleController.getString(R.string.InuTranslateLlmContext),
                    format = { if (it <= 0f) LocaleController.getString(R.string.NotificationsOff) else it.toInt().toString() },
                    onChanged = { InuConfig.TRANSLATE_LLM_CONTEXT.value = it.toInt() },
                )
                items.add(UItem.asCustom(contextSlider))
                items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateLlmContextInfo)))

                if (temperatureSlider == null) temperatureSlider = SliderCell(
                    context,
                    min = 0f,
                    max = 1f,
                    defaultValue = InuConfig.TRANSLATE_LLM_TEMPERATURE.default,
                    initialValue = InuConfig.TRANSLATE_LLM_TEMPERATURE.value,
                    step = 0.05f,
                    title = LocaleController.getString(R.string.InuTranslateLlmTemperature),
                    format = { String.format(java.util.Locale.US, "%.2f", it) },
                    onChanged = { InuConfig.TRANSLATE_LLM_TEMPERATURE.value = it },
                )
                items.add(UItem.asCustom(temperatureSlider))
                items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateLlmTemperatureInfo)))
            }

            TranslationProviders.PROVIDER_MICROSOFT -> {
                fieldRow(items, microsoftKey)
                fieldRow(items, microsoftRegion)
                items.add(UItem.asShadow(null))
            }
        }
    }

    private var contextSlider: SliderCell? = null
    private var temperatureSlider: SliderCell? = null

    private val BUTTON_DETECT_LLM = InuUtils.generateId()
    private val BUTTON_PICK_MODEL = InuUtils.generateId()

    // entiny: a value is edited in a dialog, not inline, so the keyboard never covers a field in the middle of the list
    private class Field(
        val titleRes: Int,
        val config: InuConfig.StringItem,
        val icon: Int,
        val secret: Boolean = false,
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
        val multiline: Boolean = false,
    ) {
        val id: Int = InuUtils.generateId()
    }

    private val deeplKey = Field(R.string.InuTranslateDeepLApiKey, InuConfig.TRANSLATE_DEEPL_KEY, R.drawable.msg_secret, secret = true, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val llmUrl = Field(R.string.InuTranslateLlmEndpointUrl, InuConfig.TRANSLATE_LLM_URL, R.drawable.inu_tabler_world, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    private val llmKey = Field(R.string.InuTranslateLlmApiKey, InuConfig.TRANSLATE_LLM_KEY, R.drawable.msg_secret, secret = true, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val llmModel = Field(R.string.InuTranslateLlmModel, InuConfig.TRANSLATE_LLM_MODEL, R.drawable.inu_tabler_sparkles)
    private val llmPrompt = Field(R.string.InuTranslateLlmPrompt, InuConfig.TRANSLATE_LLM_PROMPT, R.drawable.inu_tabler_code, multiline = true)
    private val microsoftKey = Field(R.string.InuTranslateMicrosoftApiKey, InuConfig.TRANSLATE_MICROSOFT_KEY, R.drawable.msg_secret, secret = true, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val microsoftRegion = Field(R.string.InuTranslateMicrosoftRegion, InuConfig.TRANSLATE_MICROSOFT_REGION, R.drawable.inu_tabler_world)
    private val fields = listOf(deeplKey, llmUrl, llmKey, llmModel, llmPrompt, microsoftKey, microsoftRegion)

    private fun fieldRow(items: ArrayList<UItem>, field: Field) {
        val value = field.config.value
        val shown = when {
            value.isEmpty() -> LocaleController.getString(R.string.None)
            field.secret -> "••••" + value.takeLast(4)
            else -> value
        }
        items.add(mkTwoLineEntry(field.id, field.icon, LocaleController.getString(field.titleRes), shown))
    }

    private fun editField(field: Field) {
        showInputDialog(
            this,
            LocaleController.getString(field.titleRes),
            initialText = field.config.value,
            selectAll = true,
            inputType = field.inputType,
            multiline = field.multiline,
        ) { input ->
            field.config.value = input.trim()
            listView?.adapter?.update(true)
            true
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        fields.firstOrNull { it.id == item.id }?.let {
            editField(it)
            return
        }
        if (item.id == BUTTON_DETECT_LLM) {
            detectLlmEndpoint()
            return
        }
        if (item.id == BUTTON_PICK_MODEL) {
            pickLlmModel()
            return
        }
        if (item.id in PROVIDER_BASE..(PROVIDER_BASE + 10)) {
            val newProvider = item.id - PROVIDER_BASE
            // entiny: a built-in provider hands Telegram's own translation requests back to the app's engine
            InuConfig.TRANSLATION_PROVIDER.value = ""
            if (newProvider != InuConfig.TRANSLATE_PROVIDER.value) {
                InuConfig.TRANSLATE_PROVIDER.value = newProvider
                EntinyTranslate.onProviderChanged()
                unlockStockTranslateButton()
            }
            listView.adapter.update(true)
        } else if (item.id in PLUGIN_BASE..(PLUGIN_BASE + 99)) {
            val key = TranslationProviders.plugins().getOrNull(item.id - PLUGIN_BASE)?.pluginKey
            if (key != null) {
                InuConfig.TRANSLATION_PROVIDER.value = key
                InuConfig.TRANSLATE_PROVIDER.value = TranslationProviders.PROVIDER_PLUGIN
                EntinyTranslate.onProviderChanged()
                unlockStockTranslateButton()
            }
            listView.adapter.update(true)
        }
    }

    private fun detectLlmEndpoint() {
        val url = InuConfig.TRANSLATE_LLM_URL.value.trim()
        val key = InuConfig.TRANSLATE_LLM_KEY.value.trim()
        if (url.isBlank()) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuTranslateLlmEndpointUrl)).show()
            return
        }
        AiModelsHelper.detectEndpoint(url, key) { result ->
            result.onSuccess { base ->
                InuConfig.TRANSLATE_LLM_URL.value = base
                listView?.adapter?.update(true)
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.formatString(R.string.InuAiProviderDetected, base)).show()
            }.onFailure {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuAiProviderDetectFailed)).show()
            }
        }
    }

    private fun pickLlmModel() {
        val url = AiComposeHelper.normalizeBase(InuConfig.TRANSLATE_LLM_URL.value.trim())
        val key = InuConfig.TRANSLATE_LLM_KEY.value.trim()
        if (url.isBlank()) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuTranslateLlmEndpointUrl)).show()
            return
        }
        AiModelsHelper.fetchOpenAiCompatModels(url, key) { result ->
            val models = result.getOrNull().orEmpty()
            if (models.isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin(result.exceptionOrNull()?.message ?: LocaleController.getString(R.string.InuAiTranscribeFetchModelsFailed)).show()
                return@fetchOpenAiCompatModels
            }
            val builder = android.app.AlertDialog.Builder(parentActivity)
            builder.setTitle(LocaleController.getString(R.string.InuAiModelPickerTitle))
            builder.setItems(models.toTypedArray()) { _, which ->
                InuConfig.TRANSLATE_LLM_MODEL.value = models[which]
                listView?.adapter?.update(true)
            }
            builder.show()
        }
    }

    // entiny: enable stock translate buttons automatically so configured provider is immediately accessible
    private fun unlockStockTranslateButton() {
        val controller = MessagesController.getInstance(UserConfig.selectedAccount).translateController
        if (!controller.isContextTranslateEnabled) controller.setContextTranslateEnabled(true)
        if (!controller.isChatTranslateEnabled) controller.setChatTranslateEnabled(true)
    }

    companion object {
        private const val PROVIDER_BASE = 24000
        private const val PLUGIN_BASE = 24100
    }
}
