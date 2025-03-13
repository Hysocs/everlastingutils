package com.everlastingutils.colors

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.format.TextDecoration.State
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.minecraft.registry.RegistryWrapper
import net.minecraft.text.Text

object KyoriHelper {
    private val miniMessage = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.builder()
        .character('§')
        .hexColors()
        .build()

    fun parse(message: String): Component {
        return miniMessage.deserialize(message)
    }

    fun text(text: String): Component {
        return Component.text(text)
    }

    fun combine(components: List<Component>): Component {
        return components.reduce { acc, component ->
            acc.append(component)
        }
    }

    fun combine(vararg components: Component): Component {
        return combine(components.toList())
    }

    fun colored(text: String, hexColor: String): Component {
        return Component.text(text)
            .style(Style.style(TextColor.fromHexString(hexColor)))
    }

    fun colored(text: String, color: NamedTextColor): Component {
        return Component.text(text)
            .color(color)
    }

    fun styled(text: String, vararg decorations: TextDecoration): Component {
        return Component.text(text)
            .decorations(decorations.associateWith { State.TRUE })
    }

    fun stripFormatting(input: String): String {
        return miniMessage.stripTags(input)
    }


    fun parseToMinecraft(message: String, registryWrapper: RegistryWrapper.WrapperLookup): Text {
        // Parse the message into a Kyori Adventure Component
        val component = parse(message) // Assume 'parse' is a function converting String to Component
        // Serialize to JSON
        val json = GsonComponentSerializer.gson().serialize(component)
        // Deserialize to Minecraft Text with the required registry wrapper
        val text = Text.Serialization.fromJson(json, registryWrapper)
        // Handle null case
        return text ?: throw IllegalArgumentException("Failed to deserialize message: $message")
    }
}