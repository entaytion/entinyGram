package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.ai.AiProviderStore
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem

abstract class AiTaskSettingsActivity(
    private val voice: Boolean,
    private val providerId: InuConfig.StringItem,
    private val model: InuConfig.StringItem,
    private val providerButton: Int,
    private val modelButton: Int,
) : SettingsPageActivity() {

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    protected fun taskProvider(): AiProviderStore.Provider? = AiFeatureSupport.effectiveProvider(providerId.value, voice)

    protected fun addTaskItems(
        items: ArrayList<UItem>,
        enabled: UItem,
        provider: AiProviderStore.Provider?,
        extra: (ArrayList<UItem>) -> Unit = {},
    ) {
        items.add(enabled)
        items.add(UItem.asShadow(if (provider == null) LocaleController.getString(R.string.InuAiAddProviderFirst) else null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionProvider)))
        items.add(
            UItem.asButton(
                providerButton,
                LocaleController.getString(R.string.InuAiFeatureProvider),
                AiFeatureSupport.providerLabel(providerId.value, voice),
            )
        )
        items.add(
            UItem.asButton(
                modelButton,
                LocaleController.getString(R.string.InuAiTranscribeModel),
                AiFeatureSupport.modelLabel(model.value, provider, voice),
            ).setEnabled(provider != null)
        )
        extra(items)
        items.add(UItem.asShadow(null))
    }

    protected fun onTaskClick(id: Int): Boolean {
        when (id) {
            providerButton -> AiFeatureSupport.pickProvider(this, voice, providerId.value) {
                providerId.value = it
                model.value = ""
                listView?.adapter?.update(true)
            }
            modelButton -> AiFeatureSupport.pickModel(this, taskProvider(), voice, model.value) {
                model.value = it
            }
            else -> return false
        }
        return true
    }
}
