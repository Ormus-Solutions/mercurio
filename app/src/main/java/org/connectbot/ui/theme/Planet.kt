/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.theme

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import org.connectbot.R
import org.connectbot.util.PreferenceConstants

/**
 * The app's themes, one per classical planet (ormus-brand tokens.json `planets`). Every one
 * is dark and keeps the same roles; only the palette changes, so a screen that reads
 * MaterialTheme and [Ormus.extras] follows the planet with no change of its own.
 */
enum class Planet(
    /** What the settings panel saves. */
    val key: String,
    /** The planet's sign, in text presentation (U+FE0E) so no phone draws it as an emoji. */
    val sign: String,
    @param:StringRes val label: Int,
    val palette: OrmusPalette,
) {
    SUN("sun", "☉︎", R.string.planet_sun, OrmusTokens.Planets.Sun),
    MOON("moon", "☽︎", R.string.planet_moon, OrmusTokens.Planets.Moon),
    MERCURY("mercury", "☿︎", R.string.planet_mercury, OrmusTokens.Planets.Mercury),
    VENUS("venus", "♀︎", R.string.planet_venus, OrmusTokens.Planets.Venus),
    MARS("mars", "♂︎", R.string.planet_mars, OrmusTokens.Planets.Mars),
    JUPITER("jupiter", "♃︎", R.string.planet_jupiter, OrmusTokens.Planets.Jupiter),
    SATURN("saturn", "♄︎", R.string.planet_saturn, OrmusTokens.Planets.Saturn),
    ;

    val colorScheme: ColorScheme by lazy { ormusColorScheme(palette) }
    val extras: OrmusExtras by lazy { ormusExtras(palette) }

    companion object {
        /** The planet saved under [key], or Sun for none or a key this version does not know. */
        fun fromKey(key: String?): Planet = entries.firstOrNull { it.key == key } ?: SUN
    }
}

/** The planet saved in the settings panel, followed as it changes, so a pick repaints the app at once. */
@Composable
fun rememberPlanet(): Planet {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var planet by remember(prefs) { mutableStateOf(Planet.fromKey(prefs.getString(PreferenceConstants.PLANET, null))) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == PreferenceConstants.PLANET) planet = Planet.fromKey(p.getString(PreferenceConstants.PLANET, null))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return planet
}
