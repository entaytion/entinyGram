package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiEndpoint
import desu.inugram.helpers.ai.AiSetupSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiCompose)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiCompose)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuAiCompose,
                R.string.InuAiComposeInfo,
                InuConfig.AI_COMPOSE_ENABLED.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiEndpoints)))
        val endpoints = AiComposeHelper.endpoints()
        val activeId = InuConfig.AI_COMPOSE_ACTIVE_ENDPOINT.value
        for ((i, ep) in endpoints.withIndex()) {
            val active = ep.id == activeId || (activeId.isEmpty() && i == 0)
            val host = AiComposeHelper.host(ep.url)
            val name = ep.name.ifBlank { host.ifBlank { ep.url } }
            val subtitle = buildString {
                append(host.ifBlank { ep.url })
                if (ep.model.isNotBlank()) {
                    append(" · ")
                    append(ep.model)
                }
                if (active) {
                    append(" · ")
                    append(LocaleController.getString(R.string.InuAiEndpointActive))
                }
            }
            items.add(
                UItem.asButtonCheck(ITEM_BASE + i, name, subtitle).also { it.checked = active }
            )
        }
        items.add(
            UItem.asButton(
                BUTTON_SETUP,
                R.drawable.input_ai_star,
                LocaleController.getString(R.string.InuAiSetupTitle)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ADD,
                R.drawable.msg_add,
                LocaleController.getString(R.string.InuAiEndpointAdd)
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val new = InuConfig.AI_COMPOSE_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_SETUP -> {
                val ctx = context ?: return
                AiSetupSheet(
                    context = ctx,
                    onManual = { showEndpointDialog(null) },
                    onSaved = { listView.adapter.update(true) },
                ).show()
            }

            BUTTON_ADD -> showEndpointDialog(null)

            else -> {
                val idx = item.id - ITEM_BASE
                val endpoints = AiComposeHelper.endpoints()
                if (idx in endpoints.indices) {
                    showEndpointDialog(endpoints[idx])
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val idx = item.id - ITEM_BASE
        val endpoints = AiComposeHelper.endpoints()
        if (idx !in endpoints.indices) return super.onLongClick(item, view, position, x, y)
        val ep = endpoints[idx]
        val options = ItemOptions.makeOptions(this, view)
            .setScrimViewBackground(listView.getClipBackground(view))
        if (AiComposeHelper.activeEndpoint()?.id != ep.id) {
            options.add(R.drawable.msg_select, LocaleController.getString(R.string.InuAiEndpointSetActive)) {
                AiComposeHelper.setActiveEndpoint(ep.id)
                listView.adapter.update(true)
            }
        }
        options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete)) {
            AiComposeHelper.deleteEndpoint(ep.id)
            listView.adapter.update(true)
        }.show()
        return true
    }

    private fun showEndpointDialog(existing: AiEndpoint?) {
        val ctx = context ?: return

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val nameInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointName),
            existing?.name ?: "",
            InputType.TYPE_CLASS_TEXT
        )
        container.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val urlInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointUrl),
            existing?.url ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        container.addView(urlInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val keyInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointApiKey),
            existing?.apiKey ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        container.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val modelInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointModel),
            existing?.model ?: "",
            InputType.TYPE_CLASS_TEXT
        )
        container.addView(modelInput, LinearLayout.LayoutParams(-1, -2))

        val title = if (existing != null)
            LocaleController.getString(R.string.InuAiEndpointEdit)
        else
            LocaleController.getString(R.string.InuAiEndpointAdd)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                AiComposeHelper.upsertEndpoint(
                    AiEndpoint(
                        id = existing?.id ?: AiComposeHelper.newEndpointId(),
                        name = nameInput.text.toString().trim(),
                        url = url,
                        apiKey = keyInput.text.toString().trim(),
                        model = modelInput.text.toString().trim(),
                    )
                )
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                AiComposeHelper.deleteEndpoint(existing.id)
                listView.adapter.update(true)
            }
        }

        showDialog(builder.create())
    }

    private fun fieldInput(hint: String, text: String, inputType: Int): EditText =
        EditText(context!!).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            setHint(hint)
            setText(text)
            this.inputType = inputType
            isSingleLine = true
            setSelection(text.length)
            textSize = 16f
        }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_SETUP = InuUtils.generateId()
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ITEM_BASE = 20000

        private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-compose",
            titleRes = R.string.InuAiCompose,
            iconRes = R.drawable.input_ai_star,
            factory = ::AiSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-compose-enabled", R.string.InuAiCompose, TOGGLE_ENABLED),
                SearchRegistry.Entry("ai-endpoints", R.string.InuAiEndpoints, BUTTON_ADD),
            ),
        )
    }
}
