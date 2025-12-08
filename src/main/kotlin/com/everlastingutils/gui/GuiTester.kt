package com.everlastingutils.gui

import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

class GuiTester {
    private val logger = LoggerFactory.getLogger("GuiTester")

    fun testBasicButtonCreation(): Boolean {
        try {
            CustomGui.createNormalButton(
                ItemStack(Items.STONE),
                "Stone Button",
                listOf("Click me!")
            )
            CustomGui.createNormalButton(
                ItemStack(Items.DIAMOND),
                "Diamond Button",
                listOf("Shiny!", "Very valuable")
            )
            CustomGui.createNormalButton(
                ItemStack(Items.DIAMOND_SWORD),
                "Weapon Button",
                listOf("Sharp!", "Combat ready")
            )
            return true
        } catch (e: Exception) {
            logger.error("Basic button creation test failed", e)
            return false
        }
    }

    fun testHeadButtonCreation(): Boolean {
        CustomGui.createPlayerHeadButton(
            "this_is_a_very_long_name_that_should_be_truncated",
            Text.literal("Long Name Test"),
            listOf(Text.literal("Should work fine")),
            "test_texture_value"
        )
        try {
            CustomGui.createPlayerHeadButton(
                "basic_head",
                Text.literal("Basic Head"),
                listOf(Text.literal("Simple head button")),
                "test_texture_value"
            )
            CustomGui.createPlayerHeadButton(
                "fancy_head",
                Text.literal("§6Fancy Head"),
                listOf(
                    Text.literal("§aLine 1"),
                    Text.literal("§bLine 2"),
                    Text.literal("§cLine 3")
                ),
                "another_texture_value"
            )
            return true
        } catch (e: Exception) {
            logger.error("Head button creation test failed", e)
            return false
        }
    }

    fun testItemManipulation(): Boolean {
        try {
            val testStack = ItemStack(Items.DIAMOND_SWORD)
            testStack.setCustomName(Text.literal("Super Sword"))
            CustomGui.setItemLore(testStack, listOf(
                "Plain text line",
                Text.literal("Text component line"),
                "§cColored line",
                null,
                123
            ))
            CustomGui.addEnchantmentGlint(testStack)
            return true
        } catch (e: Exception) {
            logger.error("Item manipulation test failed", e)
            return false
        }
    }

    fun testFormattingUtilities(): Boolean {
        try {
            val tests = listOf(
                "§aGreen text",
                "§cRed§r reset §bblue",
                "§k§l§mMultiple§r formats",
                "§0§1§2§3§4§5§6§7§8§9§a§b§c§d§e§f all colors",
                "Normal text with no formatting"
            )
            tests.forEach { CustomGui.stripFormatting(it) }
            return true
        } catch (e: Exception) {
            logger.error("Formatting utilities test failed", e)
            return false
        }
    }

    fun testLayoutCreation(): Boolean {
        try {
            val layout = mutableListOf<ItemStack>()
            layout.add(CustomGui.createNormalButton(
                ItemStack(Items.DIAMOND),
                "Button 1",
                listOf("First button")
            ))
            layout.add(CustomGui.createPlayerHeadButton(
                "test_head",
                Text.literal("Head Button"),
                listOf(Text.literal("Click me")),
                "texture_value"
            ))
            repeat(7) { layout.add(ItemStack.EMPTY) }
            repeat(9) {
                val stack = ItemStack(Items.GOLD_INGOT)
                CustomGui.addEnchantmentGlint(stack)
                layout.add(stack)
            }
            return true
        } catch (e: Exception) {
            logger.error("Layout creation test failed", e)
            return false
        }
    }

    fun testComplexLore(): Boolean {
        try {
            val testStack = ItemStack(Items.PAPER)
            CustomGui.setItemLore(testStack, listOf(
                "§7§m-------------------",
                "§6● §eFirst bullet point",
                "§6● §eSecond bullet point",
                "",
                "§7§oItalic description line",
                "§7§m-------------------"
            ))
            val anotherStack = ItemStack(Items.BOOK)
            CustomGui.setItemLore(anotherStack, listOf(
                "§8<< §7Description §8>>",
                "§7This is a longer description",
                "§7that spans multiple lines",
                "",
                "§eRarity: §6Legendary",
                "§bType: §3Magic"
            ))
            return true
        } catch (e: Exception) {
            logger.error("Complex lore test failed", e)
            return false
        }
    }

    companion object {
        fun runAllTests(): Map<String, Boolean> {
            val tester = GuiTester()
            return mapOf(
                "basicButtons" to tester.testBasicButtonCreation(),
                "headButtons" to tester.testHeadButtonCreation(),
                "itemManipulation" to tester.testItemManipulation(),
                "formatting" to tester.testFormattingUtilities(),
                "layout" to tester.testLayoutCreation(),
                "complexLore" to tester.testComplexLore()
            )
        }
    }
}