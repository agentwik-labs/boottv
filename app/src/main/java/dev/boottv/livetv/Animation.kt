package dev.boottv.livetv

private val animationGroups = setOf("anime", "anime & gaming", "animated", "animation", "cartoons", "kids", "infantil")
private val animationName = Regex("""\b(anime|animation|animated|cartoons?|toons?)\b|toon""", RegexOption.IGNORE_CASE)

internal fun Channel.isAnime() = groups.any { it.lowercase().startsWith("anime") } || name.contains("anime", ignoreCase = true)

/** Clearly animation by name or group (not just a kids' channel). */
internal fun Channel.isCartoon() = animationName.containsMatchIn(name) || groups.any { it.lowercase().startsWith("anima") }

/** Anime and cartoon channels from the free live feeds, by channel group or name: anime, then clear animation, then other kids' channels. */
internal fun LiveState.animationChannels(): List<Channel> =
    channels.filter { ch -> ch.groups.any { it.lowercase() in animationGroups } || animationName.containsMatchIn(ch.name) }
        .sortedBy { if (it.isAnime()) 0 else if (it.isCartoon()) 1 else 2 }
        .distinctBy { it.name.lowercase() }

private val pokemonName = Regex("""^pok[eé]mon\b""", RegexOption.IGNORE_CASE)

/** The official Pokémon channels in the free line-ups (Pluto TV, Samsung TV Plus), not poker channels. */
internal fun Channel.isPokemon() = pokemonName.containsMatchIn(name.trim())

internal fun LiveState.pokemonChannels(): List<Channel> = channels.filter { it.isPokemon() }.distinctBy { it.name.lowercase() }
