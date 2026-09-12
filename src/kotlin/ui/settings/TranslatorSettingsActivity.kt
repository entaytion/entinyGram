package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.translate.engine.EntinyTranslate
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.RestrictedLanguagesSelectActivity

class TranslatorSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslator)

    private val translateController get() = MessagesController.getInstance(currentAccount).translateController

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTranslation)))
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_TRANSLATE_BUTTON,
                LocaleController.getString(R.string.ShowTranslateButton),
            ).setChecked(translateController.isContextTranslateEnabled)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON,
                LocaleController.getString(R.string.ShowTranslateChatButton),
            ).setChecked(translateController.isChatTranslateEnabled)
        )
        items.add(check(TOGGLE_AUTO_TRANSLATE_ALL, R.string.InuAutoTranslateAll, InuConfig.AUTO_TRANSLATE_ALL))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAutoTranslateAllInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLanguages)))
        items.add(
            UItem.asButton(
                BUTTON_TARGET_LANG,
                R.drawable.inu_tabler_language,
                LocaleController.getString(R.string.InuTranslationTarget),
                targetLangLabel(),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DO_NOT_TRANSLATE,
                R.drawable.inu_tabler_language_off,
                LocaleController.getString(R.string.DoNotTranslate),
                doNotTranslateLabel(),
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTranslateProviderSection)))
        items.add(
            UItem.asButton(
                BUTTON_PROVIDER,
                R.drawable.inu_tabler_world,
                LocaleController.getString(R.string.InuTranslateProvider),
                EntinyTranslate.currentProviderName()
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateProviderInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAdvanced)))
        // In-place translation owns the next two outright: TranslateHelper gates web previews on
        // `IN_PLACE_TRANSLATION && TRANSLATE_WEB_PREVIEWS`, and "show original" reads a merged body
        // that only the in-place path ever stores. Shown flat, as siblings, both stayed switched on
        // and did nothing the moment in-place went off, which is exactly the dead-toggle shape the
        // Centering group was rebuilt to get rid of. Nest them so the screen cannot claim that.
        items.add(check(TOGGLE_IN_PLACE_TRANSLATION, R.string.InuInPlaceTranslation, InuConfig.IN_PLACE_TRANSLATION))
        if (InuConfig.IN_PLACE_TRANSLATION.value) {
            items.add(check(TOGGLE_TRANSLATE_WEB_PREVIEWS, R.string.InuTranslateWebPreviews, InuConfig.TRANSLATE_WEB_PREVIEWS))
            items.add(check(TOGGLE_KEEP_ORIGINAL, R.string.InuKeepOriginalAfterTranslation, InuConfig.KEEP_ORIGINAL_AFTER_TRANSLATION))
        }
        items.add(UItem.asShadow(null))

        items.add(check(TOGGLE_AUTO_DETECT_LANG, R.string.InuTranslateAutoDetectLang, InuConfig.TRANSLATE_AUTO_DETECT_LANG))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateAutoDetectLangInfo)))
        items.add(check(TOGGLE_TRANSLATE_OUTGOING, R.string.InuTranslateOutgoing, InuConfig.TRANSLATE_OUTGOING))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateOutgoingInfo)))

        // The three "overrule what Telegram decided" toggles, kept adjacent because they are one
        // idea and read as contradictory when scattered between unrelated rows.
        items.add(check(TOGGLE_FORCE_TRANSLATE, R.string.InuForceTranslate, InuConfig.FORCE_TRANSLATE))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuForceTranslateInfo)))
        items.add(check(TOGGLE_INSTANT_TRANSLATE_BANNER, R.string.InuInstantTranslateBanner, InuConfig.INSTANT_TRANSLATE_BANNER))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuInstantTranslateBannerInfo)))
        items.add(check(TOGGLE_IGNORE_TRANSLATIONS_DISABLED, R.string.InuIgnoreTranslationsDisabled, InuConfig.IGNORE_TRANSLATIONS_DISABLED))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuIgnoreTranslationsDisabledInfo)))
    }

    private fun check(id: Int, textRes: Int, item: InuConfig.BoolItem): UItem =
        UItem.asCheck(id, LocaleController.getString(textRes)).setChecked(item.value)

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        // Every plain InuConfig-backed row behaves identically, so route them through one table
        // instead of ten copies of toggle()+isChecked. The list is always rebuilt afterwards:
        // UItem.itemEquals() does not compare `checked`, so the adapter keeps the item it already
        // has and a later recycle-driven rebind would re-render the stale value - and In-place
        // translation additionally has to show or hide its two children in the same frame.
        BOOL_TOGGLES[item.id]?.let { config ->
            (view as? TextCheckCell)?.isChecked = config.toggle()
            if (item.id == TOGGLE_KEEP_ORIGINAL) {
                // Already-drawn bubbles keep the merged body until something asks them to re-read it.
                NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.updateInterfaces, 0)
            }
            listView?.adapter?.update(true)
            return
        }
        when (item.id) {
            BUTTON_PROVIDER -> presentFragment(TranslateProviderSettingsActivity())

            TOGGLE_SHOW_TRANSLATE_BUTTON -> {
                val new = !translateController.isContextTranslateEnabled
                translateController.isContextTranslateEnabled = new
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.updateSearchSettings)
            }

            TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON -> {
                val new = !translateController.isChatTranslateEnabled
                translateController.isChatTranslateEnabled = new
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.updateSearchSettings)
            }

            BUTTON_DO_NOT_TRANSLATE -> presentFragment(RestrictedLanguagesSelectActivity())

            BUTTON_TARGET_LANG -> presentFragment(TranslationTargetActivity())
        }
    }

    private fun targetLangLabel(): String {
        val configured = InuConfig.TRANSLATE_TARGET_LANGUAGE.value
        if (configured.isEmpty() && !MessagesController.getGlobalMainSettings().contains("translate_to_language")) {
            return LocaleController.getString(R.string.InuTranslationTargetFollowApp)
        }
        val code = configured.ifEmpty {
            TranslateAlert2.getToLanguage().orEmpty().also {
                if (it.isNotEmpty()) InuConfig.TRANSLATE_TARGET_LANGUAGE.value = it
            }
        }
        return TranslateAlert2.languageName(code)?.let { TranslateAlert2.capitalFirst(it) } ?: code.uppercase()
    }

    private fun doNotTranslateLabel(): String {
        val langs = RestrictedLanguagesSelectActivity.getRestrictedLanguages()
        if (langs.isEmpty()) return LocaleController.getString(R.string.None)
        if (langs.size > 2) return LocaleController.formatPluralString("Languages", langs.size)
        return langs.joinToString(", ") {
            TranslateAlert2.languageName(it)?.let { name -> TranslateAlert2.capitalFirst(name) } ?: it.uppercase()
        }
    }

    companion object {
        private val BUTTON_PROVIDER = InuUtils.generateId()
        private val TOGGLE_AUTO_TRANSLATE_ALL = InuUtils.generateId()
        private val TOGGLE_FORCE_TRANSLATE = InuUtils.generateId()
        private val TOGGLE_TRANSLATE_OUTGOING = InuUtils.generateId()
        private val TOGGLE_IN_PLACE_TRANSLATION = InuUtils.generateId()
        private val TOGGLE_TRANSLATE_WEB_PREVIEWS = InuUtils.generateId()
        private val TOGGLE_KEEP_ORIGINAL = InuUtils.generateId()
        private val TOGGLE_AUTO_DETECT_LANG = InuUtils.generateId()
        private val TOGGLE_INSTANT_TRANSLATE_BANNER = InuUtils.generateId()
        private val TOGGLE_IGNORE_TRANSLATIONS_DISABLED = InuUtils.generateId()
        private val TOGGLE_SHOW_TRANSLATE_BUTTON = InuUtils.generateId()
        private val TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON = InuUtils.generateId()
        private val BUTTON_DO_NOT_TRANSLATE = InuUtils.generateId()
        private val BUTTON_TARGET_LANG = InuUtils.generateId()

        private val BOOL_TOGGLES: Map<Int, InuConfig.BoolItem> = mapOf(
            TOGGLE_AUTO_TRANSLATE_ALL to InuConfig.AUTO_TRANSLATE_ALL,
            TOGGLE_FORCE_TRANSLATE to InuConfig.FORCE_TRANSLATE,
            TOGGLE_TRANSLATE_OUTGOING to InuConfig.TRANSLATE_OUTGOING,
            TOGGLE_IN_PLACE_TRANSLATION to InuConfig.IN_PLACE_TRANSLATION,
            TOGGLE_TRANSLATE_WEB_PREVIEWS to InuConfig.TRANSLATE_WEB_PREVIEWS,
            TOGGLE_KEEP_ORIGINAL to InuConfig.KEEP_ORIGINAL_AFTER_TRANSLATION,
            TOGGLE_AUTO_DETECT_LANG to InuConfig.TRANSLATE_AUTO_DETECT_LANG,
            TOGGLE_INSTANT_TRANSLATE_BANNER to InuConfig.INSTANT_TRANSLATE_BANNER,
            TOGGLE_IGNORE_TRANSLATIONS_DISABLED to InuConfig.IGNORE_TRANSLATIONS_DISABLED,
        )

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "translator",
            titleRes = R.string.InuTranslator,
            iconRes = R.drawable.msg_translate,
            factory = ::TranslatorSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("translate-provider", R.string.InuTranslateProvider, BUTTON_PROVIDER),
                SearchRegistry.Entry("auto-translate-all", R.string.InuAutoTranslateAll, TOGGLE_AUTO_TRANSLATE_ALL),
                SearchRegistry.Entry("force-translate", R.string.InuForceTranslate, TOGGLE_FORCE_TRANSLATE),
                SearchRegistry.Entry("translate-outgoing", R.string.InuTranslateOutgoing, TOGGLE_TRANSLATE_OUTGOING),
                SearchRegistry.Entry("show-translate-button", R.string.ShowTranslateButton, TOGGLE_SHOW_TRANSLATE_BUTTON),
                SearchRegistry.Entry("show-translate-chat-button", R.string.ShowTranslateChatButton, TOGGLE_SHOW_TRANSLATE_CHAT_BUTTON),
                SearchRegistry.Entry("translation-target", R.string.InuTranslationTarget, BUTTON_TARGET_LANG),
                SearchRegistry.Entry("do-not-translate", R.string.DoNotTranslate, BUTTON_DO_NOT_TRANSLATE),
                SearchRegistry.Entry("in-place-translation", R.string.InuInPlaceTranslation, TOGGLE_IN_PLACE_TRANSLATION),
                SearchRegistry.Entry("translate-web-previews", R.string.InuTranslateWebPreviews, TOGGLE_TRANSLATE_WEB_PREVIEWS),
                SearchRegistry.Entry("keep-original-after-translation", R.string.InuKeepOriginalAfterTranslation, TOGGLE_KEEP_ORIGINAL),
                SearchRegistry.Entry("translate-auto-detect-lang", R.string.InuTranslateAutoDetectLang, TOGGLE_AUTO_DETECT_LANG),
                SearchRegistry.Entry("instant-translate-banner", R.string.InuInstantTranslateBanner, TOGGLE_INSTANT_TRANSLATE_BANNER),
                SearchRegistry.Entry("ignore-translations-disabled", R.string.InuIgnoreTranslationsDisabled, TOGGLE_IGNORE_TRANSLATIONS_DISABLED),
            ),
        )
    }
}
