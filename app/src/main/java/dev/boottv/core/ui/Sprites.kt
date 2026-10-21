package dev.boottv.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.LocalDate

/**
 * Pokémon sprites for the Pocket Monsters theme. They are © Nintendo / Creatures / GAME FREAK: BootTV doesn't ship
 * them, the TV downloads them for personal use from the PokeAPI sprite collection (and keeps them in its image cache),
 * and they can be turned off in Settings › Home screen.
 */
object Sprites {
    private const val BASE = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon"

    internal var enabled by mutableStateOf(true)
    /** Pokédex number, 0 = a different one each day, -1 = whatever the theme suggests. */
    internal var partnerChoice by mutableIntStateOf(-1)

    /** Sprites show in the Pocket Monsters theme when turned on. */
    val on: Boolean get() = enabled && BootColors.theme.playful

    /** The little pixel sprite (96 px, from the games). */
    fun still(id: Int) = "$BASE/$id.png"

    /** Large official-style artwork, for the backdrop. */
    fun artwork(id: Int) = "$BASE/other/official-artwork/$id.png"

    /** Animated battle sprite (Black & White style, Pokédex 1–649); others fall back to the still one. */
    fun animated(id: Int) = if (id in 1..649) "$BASE/versions/generation-v/black-white/animated/$id.gif" else still(id)

    /** Partner choices for Settings; 0 picks a different one each day. */
    val partners = listOf(
        -1 to "Theme's choice", 25 to "Pikachu", 94 to "Gengar", 133 to "Eevee", 1 to "Bulbasaur", 4 to "Charmander", 7 to "Squirtle", 39 to "Jigglypuff",
        52 to "Meowth", 54 to "Psyduck", 94 to "Gengar", 143 to "Snorlax", 151 to "Mew", 0 to "A different one each day",
    )

    fun partnerName(id: Int) = partners.firstOrNull { it.first == id }?.second ?: "#$id"

    /** Friendly, recognisable Pokémon for placeholders and the daily partner. */
    private val defaultPool = intArrayOf(
        1, 4, 7, 25, 35, 39, 52, 54, 58, 63, 77, 79, 92, 104, 113, 129, 131, 133, 137, 143, 147, 151, 152, 155, 158,
        172, 175, 183, 194, 196, 197, 216, 246, 252, 255, 258, 280, 300, 311, 328, 333, 349, 363, 387, 390, 393, 403,
        417, 427, 438, 446, 447, 470, 471, 479, 495, 498, 501, 509, 517, 532, 543, 546, 551, 570, 572, 587, 610,
    )

    private val pool: IntArray get() = BootColors.theme.pool ?: defaultPool

    fun partner(): Int = when (partnerChoice) {
        0 -> pool[(LocalDate.now().toEpochDay() % pool.size).toInt()]
        -1 -> BootColors.theme.partner
        else -> partnerChoice
    }

    /** The same Pokémon every time for the same [key] (a title, a channel), so placeholders don't shuffle. */
    fun forKey(key: String): Int = pool[(key.hashCode() and 0x7fffffff) % pool.size]

    /** A Pokémon that suits a row's title. */
    fun forTitle(title: String): Int {
        val t = title.lowercase()
        return when {
            BootColors.theme.pool != null -> forKey(title) // a ghost theme keeps to its ghosts
            "pokémon" in t || "pokemon" in t -> 25
            "continue" in t || "resume" in t -> 143 // Snorlax: picks up where you dozed off
            "on now" in t || "live" in t || "channel" in t -> 479 // Rotom lives in TVs
            "anime" in t || "cartoon" in t -> 39
            "favorite" in t -> 35
            t == "apps" || "app" in t -> 137 // Porygon
            "web" in t -> 81
            "free" in t -> 52 // Meowth
            "download" in t || "offline" in t -> 79
            "send" in t || "upload" in t -> 16
            "video" in t || "added" in t || "media" in t -> 235 // Smeargle: your own creations
            else -> forKey(title)
        }
    }

    fun colorFor(key: String): Color = PokeType.of(key).color
}

/** The games' elemental types, used as BootTV's colour system: every folder, genre and channel gets one. */
enum class PokeType(val label: String, val color: Color, val sprite: Int) {
    NORMAL("Normal", Color(0xFFA8A77A), 133), FIRE("Fire", Color(0xFFEE8130), 4), WATER("Water", Color(0xFF6390F0), 7),
    ELECTRIC("Electric", Color(0xFFF7D02C), 25), GRASS("Grass", Color(0xFF7AC74C), 1), ICE("Ice", Color(0xFF96D9D6), 363),
    FIGHTING("Fighting", Color(0xFFC22E28), 66), POISON("Poison", Color(0xFFA33EA1), 88), GROUND("Ground", Color(0xFFE2BF65), 50),
    FLYING("Flying", Color(0xFFA98FF3), 16), PSYCHIC("Psychic", Color(0xFFF95587), 63), BUG("Bug", Color(0xFFA6B91A), 10),
    ROCK("Rock", Color(0xFFB6A136), 74), GHOST("Ghost", Color(0xFF735797), 92), DRAGON("Dragon", Color(0xFF6F35FC), 147),
    DARK("Dark", Color(0xFF705746), 197), STEEL("Steel", Color(0xFFB7B7CE), 81), FAIRY("Fairy", Color(0xFFD685AD), 39);

    companion object {
        /** A fitting type for a folder, genre or channel name; otherwise the same hashed type every time. */
        fun of(name: String): PokeType {
            val t = name.lowercase()
            return when {
                "pok" in t || "electr" in t || "music" in t -> ELECTRIC
                "horror" in t || "scary" in t || "halloween" in t || "ghost" in t -> GHOST
                "anime" in t || "dragon" in t -> DRAGON
                "cartoon" in t || "kids" in t || "toon" in t || "family" in t -> FAIRY
                "comedy" in t || "funny" in t -> NORMAL
                "action" in t || "sport" in t || "fight" in t || "wrestl" in t -> FIGHTING
                "nature" in t || "garden" in t || "outdoor" in t || "travel" in t -> GRASS
                "ocean" in t || "sea" in t || "swim" in t || "fish" in t || "beach" in t -> WATER
                "news" in t || "science" in t || "document" in t || "learn" in t -> PSYCHIC
                "movie" in t || "film" in t || "cinema" in t -> FIRE
                "home" in t || "camera" in t || "vacation" in t -> NORMAL
                "winter" in t || "christmas" in t || "snow" in t -> ICE
                "night" in t || "crime" in t || "noir" in t -> DARK
                "sky" in t || "bird" in t || "plane" in t -> FLYING
                "tech" in t || "robot" in t || "game" in t -> STEEL
                "rock" in t || "metal" in t -> ROCK
                else -> entries[(t.hashCode() and 0x7fffffff) % entries.size]
            }
        }
    }
}

/** One Pokémon sprite, kept pixel-sharp when scaled up. */
@Composable
fun PokeSprite(id: Int, size: Dp, modifier: Modifier = Modifier, animated: Boolean = false) {
    val context = LocalContext.current
    val url = if (animated) Sprites.animated(id) else Sprites.still(id)
    AsyncImage(
        model = remember(url) { ImageRequest.Builder(context).data(url).build() },
        contentDescription = null,
        filterQuality = FilterQuality.None,
        contentScale = ContentScale.Fit,
        // requiredSize: a sprite may be drawn bigger than its slot (its canvas is mostly empty space).
        modifier = modifier.requiredSize(size),
    )
}

/** Artwork for a tile that has no picture of its own: a sprite on a type-coloured card. */
@Composable
fun SpriteArt(key: String, modifier: Modifier = Modifier) {
    val color = Sprites.colorFor(key)
    BoxWithConstraints(
        modifier.background(Brush.linearGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.35f), BootColors.Surface))),
        contentAlignment = Alignment.Center,
    ) {
        PokeSprite(Sprites.forKey(key), min(maxWidth, maxHeight) * 0.95f)
    }
}

/** A row heading; in the Pocket Monsters theme a fitting Pokémon stands next to it. */
@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (Sprites.on) {
            // Sprites have empty space around them: let it overlap the row instead of pushing the text.
            // One fixed slot per title, so every row lines up the same way.
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { PokeSprite(Sprites.forTitle(text), 48.dp) }
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = BootColors.TextPrimary.copy(alpha = 0.9f))
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = BootColors.TextSecondary.copy(alpha = 0.7f))
        }
    }
}

/** "Loading…": the partner Pokémon running in place in the Pocket Monsters theme, plain text otherwise. */
@Composable
fun LoadingIndicator(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color(0x99000000).takeIf { !Sprites.on } ?: Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (Sprites.on) PokeSprite(Sprites.partner(), 120.dp, animated = true)
        Text(text, color = BootColors.TextSecondary, style = MaterialTheme.typography.titleMedium)
    }
}

/** The partner Pokémon, animated, for the top bar. */
@Composable
fun PartnerSprite(size: Dp = 64.dp, modifier: Modifier = Modifier) {
    if (Sprites.on) PokeSprite(Sprites.partner(), size, modifier, animated = true)
}
