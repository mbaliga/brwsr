package dev.aarso.brwsr.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aarso.brwsr.core.Corner
import dev.aarso.brwsr.core.SearchEngine
import dev.aarso.brwsr.core.WallStyle
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class Hand { Right, Left }
enum class PuckMode(val label: String) { Joystick("Joystick"), Trackball("Trackball") }

/** App-wide preferences as Compose state, written through to SharedPreferences. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("brwsr", Context.MODE_PRIVATE)

    var orbCorner by enumPref("orbCorner", Corner.BottomRight)
    var wallStyle by enumPref("wallStyle", WallStyle.Curved)
    var searchEngine by enumPref("searchEngine", SearchEngine.DuckDuckGo)
    var immersive by boolPref("immersive", false)

    // Blocking — everything on by default.
    var adblock by boolPref("adblock", true)
    var cosmetic by boolPref("cosmetic", true)
    var blockPopups by boolPref("blockPopups", true)
    var blockThirdPartyCookies by boolPref("block3pCookies", true)
    var sendGpc by boolPref("sendGpc", true)
    var enabledLists by stringSetPref("enabledLists", FilterListCatalog.all.map { it.id }.toSet())
    var listsUpdatedAt by longPref("listsUpdatedAt", 0L)

    // Global look.
    var globalCss by stringPref("globalCss", "")

    // Desktop mode puck.
    var hand by enumPref("hand", Hand.Right)
    var puckMode by enumPref("puckMode", PuckMode.Joystick)
    var puckSizeDp by floatPref("puckSizeDp", 132f)
    var puckSpeed by floatPref("puckSpeed", 1f)
    /** Puck position as fractions of the screen, so it survives rotation. -1 = default spot. */
    var puckX by floatPref("puckX", -1f)
    var puckY by floatPref("puckY", -1f)

    var restoreUrls by stringPref("restoreUrls", "")

    private fun boolPref(key: String, def: Boolean) = pref(key, def, { getBoolean(key, def) }, { putBoolean(key, it) })
    private fun longPref(key: String, def: Long) = pref(key, def, { getLong(key, def) }, { putLong(key, it) })
    private fun floatPref(key: String, def: Float) = pref(key, def, { getFloat(key, def) }, { putFloat(key, it) })
    private fun stringPref(key: String, def: String) = pref(key, def, { getString(key, def) ?: def }, { putString(key, it) })
    private fun stringSetPref(key: String, def: Set<String>) =
        pref(key, def, { getStringSet(key, def)?.toSet() ?: def }, { putStringSet(key, it) })

    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E) = pref(
        key, def,
        { getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def },
        { putString(key, it.name) },
    )

    private fun <T> pref(
        key: String,
        def: T,
        read: SharedPreferences.() -> T,
        write: SharedPreferences.Editor.(T) -> Unit,
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(runCatching { prefs.read() }.getOrDefault(def))
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply { write(value) }.apply()
        }
    }
}
