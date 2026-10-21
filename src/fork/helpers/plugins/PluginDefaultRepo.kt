package desu.inugram.helpers.plugins

object PluginDefaultRepo {
    private var seeding = false

    // entiny: the official repo is part of the app, so it comes back whenever it is missing; a network failure retries on the next open
    @JvmStatic
    fun seed(account: Int, onDone: () -> Unit = {}) {
        if (seeding || PluginRepos.getRepos().any { PluginRepos.isOfficial(it) }) return
        seeding = true
        PluginRepos.addRepo(account, PluginRepos.OFFICIAL_USERNAME) {
            seeding = false
            onDone()
        }
    }
}
