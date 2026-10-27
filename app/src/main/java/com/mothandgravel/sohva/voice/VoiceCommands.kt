package com.mothandgravel.sohva.voice

import com.mothandgravel.sohva.protocol.ExternalInput
import com.mothandgravel.sohva.protocol.TvApp
import com.mothandgravel.sohva.protocol.TvChannel
import java.text.Normalizer

/** What a spoken command asks the TV to do. */
sealed interface VoiceAction {
    data class SetVolume(val level: Int) : VoiceAction
    data class StepVolume(val up: Boolean) : VoiceAction
    data class Mute(val muted: Boolean) : VoiceAction
    data object PowerOff : VoiceAction
    data class Channel(val channel: TvChannel) : VoiceAction
    data class App(val app: TvApp) : VoiceAction
    data class Input(val input: ExternalInput) : VoiceAction
}

/**
 * Turns what the phone heard into a [VoiceAction], in Finnish or English. The names come from the
 * TV's own lists (channels, apps, inputs), so "Yle TV2", "Netflix" or "PlayStation" work without a
 * vocabulary of our own. Finnish endings are tolerated by matching the start of a word
 * ("Netflixiin", "MTV3:lle"). Pure Kotlin, so it is unit-tested.
 */
object VoiceCommands {

    fun parse(heard: String, channels: List<TvChannel>, apps: List<TvApp>, inputs: List<ExternalInput>): VoiceAction? {
        val text = clean(heard)
        if (text.isBlank()) return null
        val words = text.split(' ')

        fixed(text, words)?.let { return it }

        VOLUME_LEVEL.find(text)?.let { match ->
            val level = match.groupValues.last().toInt()
            return VoiceAction.SetVolume(level.coerceIn(0, 100))
        }

        // "kanava 3", "channel 12", or only a number: the channel shown as that number.
        val number = CHANNEL_NUMBER.find(text)?.groupValues?.last() ?: text.takeIf { it.all(Char::isDigit) }
        number?.toIntOrNull()?.let { n ->
            channels.firstOrNull { it.number.toIntOrNull() == n }?.let { return VoiceAction.Channel(it) }
        }

        val target = words.filterNot { it in FILLER }.joinToString("")
        if (target.isEmpty()) return null
        val candidates = channels.map { VoiceAction.Channel(it) to it.title } +
            inputs.map { VoiceAction.Input(it) to it.displayName } +
            apps.map { VoiceAction.App(it) to it.title }
        return candidates
            .mapNotNull { (action, name) -> bestScore(target, name)?.let { action to it } }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun fixed(text: String, words: List<String>): VoiceAction? = when {
        UNMUTE.any { it in text } -> VoiceAction.Mute(false)
        MUTE.any { it in text } -> VoiceAction.Mute(true)
        POWER_OFF.any { it in text } -> VoiceAction.PowerOff
        words.any { it in LOUDER } || LOUDER_PHRASES.any { it in text } -> VoiceAction.StepVolume(up = true)
        words.any { it in QUIETER } || QUIETER_PHRASES.any { it in text } -> VoiceAction.StepVolume(up = false)
        else -> null
    }

    /**
     * The name as the TV lists it, or without its first word ("Areena" for "Yle Areena", "TV2"
     * for "Yle TV2 HD"), which people often leave out; the full name wins a tie.
     */
    private fun bestScore(target: String, name: String): Int? {
        val words = clean(name).split(' ').filterNot { it == "hd" }
        val full = score(target, words.joinToString(""))
        val short = if (words.size > 1) words.drop(1).joinToString("").takeIf { it.length >= 3 }?.let { score(target, it) } else null
        return listOfNotNull(full, short?.minus(500)).maxOrNull()
    }

    /**
     * How well [target] (the spoken name, filler removed) names [name]; null for no match. Exact
     * beats a Finnish ending ("netflixiin"), which beats a shared start of over half the name
     * ("kutoselle" for "kutonen").
     */
    private fun score(target: String, name: String): Int? {
        if (name.isEmpty()) return null
        return when {
            target == name -> 3000 + name.length
            target.startsWith(name) && name.length >= 3 -> 2000 + name.length
            else -> {
                val shared = target.commonPrefixWith(name).length
                if (shared >= 4 && shared * 20 >= name.length * 11) 1000 + shared else null
            }
        }
    }

    /** Lower case, accents kept (ä, ö), punctuation turned into spaces. */
    private fun clean(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private val VOLUME_LEVEL = Regex("(?:volume|ääni|äänet|äänenvoimakkuus|voimakkuus)(?: \\p{L}+)? (\\d{1,3})\\b")
    private val CHANNEL_NUMBER = Regex("(?:kanava\\p{L}*|channel) (\\d{1,4})\\b")

    private val MUTE = listOf("mute", "mykistä", "mykistys", "hiljaa", "äänet pois", "ääni pois")
    private val UNMUTE = listOf("unmute", "äänet päälle", "ääni päälle", "palauta äänet", "poista mykistys")
    private val POWER_OFF = listOf("turn off", "power off", "switch off", "sammuta", "sulje tv", "tv pois")
    private val LOUDER = setOf("louder", "kovempaa", "kovemmalle", "lujempaa", "lujemmalle")
    private val LOUDER_PHRASES = listOf("volume up", "lisää ääntä", "ääni ylös", "isommalle")
    private val QUIETER = setOf("quieter", "hiljempaa", "hiljemmalle", "pienemmälle")
    private val QUIETER_PHRASES = listOf("volume down", "vähemmän ääntä", "ääni alas")

    /** Words around a name that do not belong to it. */
    private val FILLER = setOf(
        "laita", "avaa", "vaihda", "katso", "näytä", "mene", "käynnistä", "kanava", "kanavalle", "kanavaan",
        "sovellus", "sovellukseen", "tulo", "tuloon", "päälle",
        "open", "switch", "put", "go", "to", "on", "the", "watch", "play", "start", "launch", "channel", "app", "input",
    )
}
