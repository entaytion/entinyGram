package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Parent category page in the refactored settings menu (6 categories).
 * Renders its merged sub-sections as chevron rows and forwards to the leaf page
 * that owns that nested logic. Leaf pages, their slugs and SearchRegistry entries
 * stay untouched.
 */
abstract class CategoryIndexActivity : SettingsPageActivity() {

    protected data class Child(
        val iconRes: Int,
        val titleRes: Int,
        val factory: () -> SettingsPageActivity,
    )

    protected abstract val titleRes: Int
    protected abstract val children: List<Child>

    // Stable per-instance base so ids survive repeated fillItems() calls (on resume).
    private val idBase by lazy { InuUtils.generateId() }
    private fun idOf(index: Int): Int = idBase + index

    override fun getTitle(): CharSequence = LocaleController.getString(titleRes)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        children.forEachIndexed { i, child ->
            items.add(mkSubPageButton(idOf(i), child.iconRes, LocaleController.getString(child.titleRes)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val index = item.id - idBase
        if (index in children.indices) {
            presentFragment(children[index].factory())
        }
    }
}
