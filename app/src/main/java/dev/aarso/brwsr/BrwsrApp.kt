package dev.aarso.brwsr

import android.app.Application
import dev.aarso.brwsr.adblock.FilterLists
import dev.aarso.brwsr.data.Settings
import dev.aarso.brwsr.data.SiteRules
import dev.aarso.brwsr.data.TopSites
import dev.aarso.crashrecovery.CrashRecovery
import kotlinx.coroutines.MainScope

/** App-lifetime state: preferences, site rules, top sites and the filter engine. */
class BrwsrApp : Application() {
    val scope = MainScope()
    lateinit var settings: Settings private set
    lateinit var rules: SiteRules private set
    lateinit var topSites: TopSites private set
    lateinit var filters: FilterLists private set

    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "brwsr")
        settings = Settings(this)
        rules = SiteRules(this, scope)
        topSites = TopSites(this, scope)
        filters = FilterLists(this, settings, scope).also { it.start() }
    }
}
