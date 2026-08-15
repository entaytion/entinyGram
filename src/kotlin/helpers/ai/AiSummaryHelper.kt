package desu.inugram.helpers.ai

import android.content.Context
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog

object AiSummaryHelper {
    @JvmStatic
    fun summarize(context: Context, message: MessageObject) {
        val endpoint = AiComposeHelper.activeEndpoint()
        if (endpoint == null || endpoint.url.isBlank() || endpoint.apiKey.isBlank()) {
            AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuAiSummary))
                .setMessage(LocaleController.getString(R.string.InuAiOpenSettings))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show()
            return
        }
        val text = message.messageOwner?.message?.trim().orEmpty()
        if (text.isBlank()) return
        AiComposeHelper.request(
            endpoint,
            "Summarize the following Telegram channel post clearly and concisely. Preserve important names, dates, numbers and links. Return only the summary.",
            text,
        ) { result, error ->
            val body = result ?: "${LocaleController.getString(R.string.InuAiSummary)}: ${error.orEmpty()}"
            AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuAiSummary))
                .setMessage(body)
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show()
        }
    }
}
