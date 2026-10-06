package desu.inugram.helpers.search

import android.os.Bundle
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import desu.inugram.helpers.security.ParanoiaHelper
import java.util.function.Consumer


sealed class SearchTopAction {
    abstract val label: CharSequence
    open val iconRes: Int = R.drawable.msg_link2
    abstract fun execute(fragment: BaseFragment)

    class ExitParanoia : SearchTopAction() {
        override val label: CharSequence
            get() = LocaleController.getString(R.string.InuParanoiaExit)
        override val iconRes: Int = R.drawable.msg_permissions

        override fun execute(fragment: BaseFragment) {
            ParanoiaHelper.disableParanoia(fragment)
        }
    }

    class Username(val name: String) : SearchTopAction() {
        override val label: CharSequence
            get() = LocaleController.formatString(R.string.InuSearchOpenUsername, "@$name")

        override fun execute(fragment: BaseFragment) {
            MessagesController.getInstance(fragment.currentAccount)
                .openByUserName(name, fragment, 1)
        }
    }

    class Peer(private val peer: TLObject) : SearchTopAction() {
        override val label: CharSequence
            get() = LocaleController.formatString(
                R.string.InuSearchOpenUsername,
                if (peer is TLRPC.User) UserObject.getUserName(peer) else (peer as TLRPC.Chat).title,
            )
        override val iconRes: Int = R.drawable.msg_openprofile

        override fun execute(fragment: BaseFragment) {
            val args = Bundle()
            if (peer is TLRPC.User) {
                args.putLong("user_id", peer.id)
            } else {
                args.putLong("chat_id", (peer as TLRPC.Chat).id)
            }
            fragment.presentFragment(ChatActivity(args))
        }
    }

    class Link(private val display: String, private val url: String) : SearchTopAction() {
        override val label: CharSequence
            get() = LocaleController.formatString(R.string.InuSearchOpenLink, display)

        override fun execute(fragment: BaseFragment) {
            val activity = fragment.parentActivity ?: return
            Browser.openUrl(activity, url)
        }
    }

    companion object {
        const val VIEW_TYPE = 100;
        private val USERNAME = Regex("^@?([A-Za-z][A-Za-z0-9_]{4,31})$")
        private val PEER_ID = Regex("^-?\\d{1,19}$")
        private val TG_LINK = Regex("^tg://\\S+$")
        private val TME_LINK =
            Regex("^(https?://)?(t\\.me|telegram\\.me|telegram\\.dog)/\\S+$", RegexOption.IGNORE_CASE)

        @JvmStatic
        fun parse(query: String?, account: Int, onPeerLoaded: Consumer<SearchTopAction>): SearchTopAction? {
            if (query.isNullOrEmpty()) return null
            if (ParanoiaHelper.matchesExitCode(query)) return ExitParanoia()
            if (PEER_ID.matches(query)) return findPeerById(query, account, onPeerLoaded)
            USERNAME.matchEntire(query)?.let { return Username(it.groupValues[1]) }
            if (TG_LINK.matches(query)) return Link(query, query)
            TME_LINK.matchEntire(query)?.let {
                val scheme = it.groupValues[1]
                val url = if (scheme.isEmpty()) "https://$query" else query
                return Link(query, url)
            }
            return null
        }

        private fun findPeerById(query: String, account: Int, onPeerLoaded: Consumer<SearchTopAction>): Peer? {
            val id = query.toLongOrNull() ?: return null
            val dialogIds = when {
                id > 0 -> listOf(id, -id)
                query.startsWith("-100") -> listOfNotNull(id, query.substring(4).toLongOrNull()?.let { -it })
                else -> listOf(id)
            }
            val controller = MessagesController.getInstance(account)
            dialogIds.firstNotNullOfOrNull { controller.getUserOrChat(it) }?.let { return Peer(it) }

            val storage = MessagesStorage.getInstance(account)
            storage.storageQueue.postRunnable {
                val peer = dialogIds.firstNotNullOfOrNull {
                    if (it > 0) storage.getUser(it) else storage.getChat(-it)
                } ?: return@postRunnable
                AndroidUtilities.runOnUIThread {
                    if (peer is TLRPC.User) controller.putUser(peer, true) else controller.putChat(peer as TLRPC.Chat, true)
                    onPeerLoaded.accept(Peer(peer))
                }
            }
            return null
        }
    }
}
