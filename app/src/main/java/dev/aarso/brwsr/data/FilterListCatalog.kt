package dev.aarso.brwsr.data

/** The lists "ad-blocking to the max" pulls in. All enabled by default; each can be turned off. */
data class FilterListSpec(val id: String, val title: String, val what: String, val url: String)

object FilterListCatalog {
    val all = listOf(
        FilterListSpec("easylist", "EasyList", "Ads", "https://easylist.to/easylist/easylist.txt"),
        FilterListSpec("easyprivacy", "EasyPrivacy", "Trackers", "https://easylist.to/easylist/easyprivacy.txt"),
        FilterListSpec("ublock", "uBlock filters", "Ads, anti-adblock", "https://ublockorigin.github.io/uAssets/filters/filters.txt"),
        FilterListSpec("ublock-privacy", "uBlock Privacy", "Trackers", "https://ublockorigin.github.io/uAssets/filters/privacy.txt"),
        FilterListSpec("adguard-dns", "AdGuard DNS", "Ad & tracker domains", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
        FilterListSpec("pgl", "Peter Lowe's list", "Ad servers", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"),
        FilterListSpec("stevenblack", "StevenBlack hosts", "Ads & malware", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
        FilterListSpec("fanboy-annoyance", "Fanboy Annoyances", "Cookie banners, pop-ups, social", "https://secure.fanboy.co.nz/fanboy-annoyance.txt"),
        FilterListSpec("urlhaus", "URLhaus malicious", "Malware sites", "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-agh-online.txt"),
    )
}
