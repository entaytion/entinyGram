package desu.inugram.helpers.plugins

import desu.inugram.InuConfig

object PluginDefaultRepo {
    private const val USERNAME = "entinyGramPlugins"
    private var seeding = false

    @JvmStatic
    fun seed(account: Int, onDone: () -> Unit = {}) {
        if (InuConfig.PLUGIN_REPOS_SEEDED.value || seeding) return
        seeding = true
        PluginRepos.addRepo(account, USERNAME) { error ->
            seeding = false
            // entiny: an existing repo counts as seeded, a network failure retries next time
            if (error == null || PluginRepos.getRepos().any { it.username.equals(USERNAME, true) }) {
                InuConfig.PLUGIN_REPOS_SEEDED.value = true
            }
            onDone()
        }
    }
}
