package desu.inugram.helpers.translate

import android.text.SpannableStringBuilder
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_iv
import org.telegram.ui.iv.RichTextStyle

class RichMessageTranslator private constructor(
    val messages: ArrayList<TL_iv.RichMessage>,
    val texts: ArrayList<TLRPC.TL_textWithEntities>,
    val sourceLanguages: ArrayList<String?>,
    private val applyTranslations: List<(TLRPC.TL_textWithEntities) -> Unit>,
) {
    fun apply(translations: List<TLRPC.TL_textWithEntities>): ArrayList<TL_iv.RichMessage>? {
        if (translations.size != applyTranslations.size) return null
        translations.forEachIndexed { index, text -> applyTranslations[index](text) }
        return messages
    }

    companion object {
        fun create(
            account: Int,
            sources: List<TL_iv.RichMessage>,
            languages: List<String?>,
        ): RichMessageTranslator? {
            val messages = ArrayList<TL_iv.RichMessage>(sources.size)
            val texts = ArrayList<TLRPC.TL_textWithEntities>()
            val sourceLanguages = ArrayList<String?>()
            val applyTranslations = ArrayList<(TLRPC.TL_textWithEntities) -> Unit>()

            fun addRichText(
                text: TL_iv.RichText?,
                language: String?,
                apply: (TL_iv.RichText) -> Unit,
            ) {
                if (text == null || text is TL_iv.textEmpty) return
                val value = arrayOf<CharSequence>(RichTextStyle.toSpannable(text))
                if (value[0].isEmpty()) return
                texts += TLRPC.TL_textWithEntities().also {
                    it.text = value[0].toString()
                    it.entities = MediaDataController.getInstance(account).getEntities(value, true, false)
                }
                sourceLanguages += language
                applyTranslations += { translated ->
                    val styled = SpannableStringBuilder(translated.text.orEmpty())
                    MessageObject.addEntitiesToText(styled, translated.entities, false, false, false, true)
                    apply(RichTextStyle.fromSpannable(styled))
                }
            }

            fun addString(value: String?, language: String?, apply: (String) -> Unit) {
                if (value.isNullOrEmpty()) return
                texts += TLRPC.TL_textWithEntities().also { it.text = value }
                sourceLanguages += language
                applyTranslations += { apply(it.text.orEmpty()) }
            }

            fun visitCaption(caption: TL_iv.PageCaption?, language: String?) {
                if (caption == null) return
                addRichText(caption.text, language) { caption.text = it }
                addRichText(caption.credit, language) { caption.credit = it }
            }

            lateinit var visitBlock: (TL_iv.PageBlock, String?) -> Unit
            val visitBlocks = { blocks: List<TL_iv.PageBlock>, language: String? ->
                blocks.forEach { visitBlock(it, language) }
            }
            visitBlock = { block, language ->
                addRichText(block.text, language) { block.text = it }
                visitCaption(block.caption, language)
                when (block) {
                    is TL_iv.pageBlockAuthorDate -> addRichText(block.author, language) { block.author = it }
                    is TL_iv.pageBlockBlockquote -> addRichText(block.caption, language) { block.caption = it }
                    is TL_iv.pageBlockBlockquoteBlocks -> {
                        addRichText(block.caption, language) { block.caption = it }
                        visitBlocks(block.blocks, language)
                    }
                    is TL_iv.pageBlockPullquote -> addRichText(block.caption, language) { block.caption = it }
                    is TL_iv.pageBlockCover -> visitBlock(block.cover, language)
                    is TL_iv.pageBlockEmbedPost -> {
                        addString(block.author, language) { block.author = it }
                        visitBlocks(block.blocks, language)
                    }
                    is TL_iv.pageBlockCollage -> visitBlocks(block.items, language)
                    is TL_iv.pageBlockSlideshow -> visitBlocks(block.items, language)
                    is TL_iv.pageBlockTable -> {
                        addRichText(block.title, language) { block.title = it }
                        block.rows.forEach { row ->
                            row.cells.forEach { cell ->
                                addRichText(cell.text, language) { cell.text = it }
                            }
                        }
                    }
                    is TL_iv.pageBlockList -> block.items.forEach { item ->
                        when (item) {
                            is TL_iv.TL_pageListItemText -> addRichText(item.text, language) { item.text = it }
                            is TL_iv.TL_pageListItemBlocks -> visitBlocks(item.blocks, language)
                        }
                    }
                    is TL_iv.pageBlockOrderedList -> block.items.forEach { item ->
                        when (item) {
                            is TL_iv.TL_pageListOrderedItemText -> addRichText(item.text, language) { item.text = it }
                            is TL_iv.TL_pageListOrderedItemBlocks -> visitBlocks(item.blocks, language)
                        }
                    }
                    is TL_iv.pageBlockDetails -> {
                        addRichText(block.title, language) { block.title = it }
                        visitBlocks(block.blocks, language)
                    }
                    is TL_iv.pageBlockRelatedArticles -> {
                        addRichText(block.title, language) { block.title = it }
                        block.articles.forEach { article ->
                            addString(article.title, language) { article.title = it }
                            addString(article.description, language) { article.description = it }
                            addString(article.author, language) { article.author = it }
                        }
                    }
                    is TL_iv.pageBlockButtonRow -> block.buttons.forEach { button ->
                        addRichText(button.text, language) { button.text = it }
                    }
                    else -> {}
                }
            }

            for ((index, source) in sources.withIndex()) {
                val message = InuUtils.cloneTLObject(source, TL_iv.RichMessage::TLdeserialize) ?: return null
                messages += message
                visitBlocks(message.blocks, languages.getOrNull(index))
            }
            return RichMessageTranslator(messages, texts, sourceLanguages, applyTranslations)
        }
    }
}
