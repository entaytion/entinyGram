package desu.inugram.helpers.ai

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import desu.inugram.helpers.ai.AiSetupHelper.FetchResult
import desu.inugram.helpers.ai.AiEndpoint
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Multi-step bottom sheet for quickly configuring an AI provider endpoint:
 *
 *  Step 1 — pick provider (Google Gemini / Manual)
 *  Step 2 — enter API key + hit "Verify"
 *  Step 3 — pick a model from the fetched list → auto-save & close
 *
 * "Manual" short-circuits to [onManual] so the caller can open the legacy dialog.
 */
class AiSetupSheet(
    context: Context,
    private val onManual: () -> Unit,
    private val onSaved: () -> Unit,
) : BottomSheet(context, true) {

    // current provider selection
    private var selectedProvider: AiSetupHelper.Provider? = null

    // dynamic content area — swapped between steps
    private val stepContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val scroll = NestedScrollView(context)

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite))

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // ---- Title ----
        root.addView(
            TextView(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
                typeface = AndroidUtilities.bold()
                text = LocaleController.getString(R.string.InuAiSetupTitle)
            },
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 20, 18, 20, 12
            )
        )

        root.addView(stepContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        scroll.addView(root)
        setCustomView(scroll)

        showStep1()
    }

    // ------------------------------------------------------------------ STEP 1

    private fun showStep1() {
        stepContainer.removeAllViews()

        stepContainer.addView(
            label(LocaleController.getString(R.string.InuAiSetupProvider)),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20, 0)
        )

        // Provider buttons
        for (provider in AiSetupHelper.PROVIDERS) {
            stepContainer.addView(
                actionRow(provider.displayName) {
                    selectedProvider = provider
                    showStep2(provider)
                }
            )
        }

        // Divider
        stepContainer.addView(divider(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 8, 0, 8, 0))

        // Manual
        stepContainer.addView(
            actionRow(LocaleController.getString(R.string.InuAiSetupManual)) {
                dismiss()
                onManual()
            }
        )

        spacer()
    }

    // ------------------------------------------------------------------ STEP 2

    private fun showStep2(provider: AiSetupHelper.Provider) {
        stepContainer.removeAllViews()

        stepContainer.addView(
            label(provider.displayName),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14, 0)
        )

        val keyInput = EditText(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            setBackgroundColor(Color.TRANSPARENT)
            hint = LocaleController.getString(R.string.InuAiSetupApiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        }
        stepContainer.addView(
            keyInput,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 4, 14, 4)
        )

        val statusLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            visibility = View.GONE
        }
        stepContainer.addView(
            statusLabel,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14, 4)
        )

        var verifyBtn: TextView? = null
        verifyBtn = roundedButton(LocaleController.getString(R.string.InuAiSetupVerify)) {
            val key = keyInput.text.toString().trim()
            if (key.isBlank()) return@roundedButton
            statusLabel.text = LocaleController.getString(R.string.InuAiSetupVerifying)
            statusLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            statusLabel.visibility = View.VISIBLE
            verifyBtn?.isEnabled = false

            AiSetupHelper.fetchModels(provider, key) { result ->
                verifyBtn?.isEnabled = true
                when (result) {
                    is FetchResult.Success -> {
                        statusLabel.visibility = View.GONE
                        showStep3(provider, key, result.models)
                    }
                    is FetchResult.Error -> {
                        statusLabel.text = LocaleController.formatString(
                            R.string.InuAiSetupError, result.message
                        )
                        statusLabel.setTextColor(Theme.getColor(Theme.key_text_RedBold))
                        statusLabel.visibility = View.VISIBLE
                    }
                }
            }
        }
        stepContainer.addView(
            verifyBtn,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 8, 0, 20, 10)
        )
    }

    // ------------------------------------------------------------------ STEP 3

    private fun showStep3(
        provider: AiSetupHelper.Provider,
        apiKey: String,
        models: List<AiSetupHelper.AiModel>,
    ) {
        stepContainer.removeAllViews()

        stepContainer.addView(
            label(LocaleController.getString(R.string.InuAiSetupPickModel)),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8, 0)
        )

        for (model in models) {
            stepContainer.addView(
                modelRow(model) {
                    // Save endpoint
                    val endpoint = AiEndpoint(
                        id = AiComposeHelper.newEndpointId(),
                        name = provider.displayName,
                        url = provider.endpointUrl,
                        apiKey = apiKey,
                        model = model.id,
                    )
                    AiComposeHelper.upsertEndpoint(endpoint)
                    // Make it active
                    AiComposeHelper.setActiveEndpoint(endpoint.id)
                    dismiss()
                    onSaved()
                }
            )
        }

        spacer()
    }

    // ------------------------------------------------------------------ helpers

    private fun label(text: String) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
        typeface = AndroidUtilities.bold()
        this.text = text.uppercase()
        setPadding(dp(20), 0, dp(20), 0)
    }

    private fun actionRow(text: String, onClick: () -> Unit) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            this.text = text
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector),
                Theme.RIPPLE_MASK_ALL
            )
            setOnClickListener { onClick() }
        }

    private fun modelRow(model: AiSetupHelper.AiModel, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector),
                Theme.RIPPLE_MASK_ALL
            )
            setOnClickListener { onClick() }
        }
        row.addView(TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            text = model.displayName
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        if (model.id != model.displayName) {
            row.addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                text = model.id
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        return row
    }

    private fun roundedButton(label: String, onClick: () -> Unit) =
        TextView(context).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            text = label
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhite), 120)
            )
            setOnClickListener { onClick() }
        }

    private fun divider() = View(context).apply {
        setBackgroundColor(Theme.getColor(Theme.key_divider))
    }

    private fun spacer() {
        stepContainer.addView(
            View(context),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16)
        )
    }

    private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())
}
