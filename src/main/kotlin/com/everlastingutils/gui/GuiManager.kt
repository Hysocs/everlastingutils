package com.everlastingutils.gui

import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.component.type.ProfileComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket
import net.minecraft.screen.*
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import org.slf4j.LoggerFactory
import java.util.UUID

data class InteractionContext(
    val slotIndex: Int,
    val clickType: ClickType,
    val button: Int,
    val clickedStack: ItemStack,
    val player: ServerPlayerEntity
)

object CustomGui {
    private val logger = LoggerFactory.getLogger(CustomGui::class.java)

    /**
     * Opens a custom GUI for the player using a double chest (6 rows).
     *
     * @param player The player to open the GUI for.
     * @param title The title of the GUI.
     * @param layout The list of ItemStacks to display in the GUI.
     * @param onInteract Callback for when a player interacts with a slot.
     * @param onClose Callback for when the GUI is closed.
     */
    fun openGui(
        player: ServerPlayerEntity,
        title: String,
        layout: List<ItemStack>,
        onInteract: (InteractionContext) -> Unit,
        onClose: (Inventory) -> Unit
    ) {
        // Delegate to the overloaded method with 6 rows (double chest) as default
        openGui(player, title, layout, 6, onInteract, onClose)
    }

    /**
     * Opens a custom GUI for the player with a specified number of container rows.
     * For example, use rows = 3 for a single chest GUI.
     *
     * @param player The player to open the GUI for.
     * @param title The title of the GUI.
     * @param layout The list of ItemStacks to display in the GUI.
     * @param rows Number of rows for the container (typically 3 for a single chest, 6 for a double chest).
     * @param onInteract Callback for when a player interacts with a slot.
     * @param onClose Callback for when the GUI is closed.
     */
    fun openGui(
        player: ServerPlayerEntity,
        title: String,
        layout: List<ItemStack>,
        rows: Int,
        onInteract: (InteractionContext) -> Unit,
        onClose: (Inventory) -> Unit
    ) {
        val factory = SimpleNamedScreenHandlerFactory(
            { syncId, inv, _ ->
                CustomScreenHandler(syncId, inv, layout, rows, onInteract, onClose)
            },
            Text.literal(title)
        )
        player.openHandledScreen(factory)
    }

    fun createPlayerHeadButton(
        textureName: String,
        title: Text,
        lore: List<Text>,
        textureValue: String
    ): ItemStack {
        val itemStack = ItemStack(Items.PLAYER_HEAD)
        itemStack.set(DataComponentTypes.ITEM_NAME, title)
        itemStack.set(DataComponentTypes.LORE, LoreComponent(lore))

        val safeName = textureName.take(16)
        val profile = GameProfile(UUID.randomUUID(), safeName)
        profile.properties.put("textures", Property("textures", textureValue))
        itemStack.set(DataComponentTypes.PROFILE, ProfileComponent(profile))

        return itemStack
    }

    fun createNormalButton(
        item: ItemStack,
        displayName: String,
        lore: List<String>
    ): ItemStack {
        val newStack = item.copy()
        newStack.set(DataComponentTypes.ITEM_NAME, Text.literal(displayName))
        val loreText = lore.map { Text.literal(it) }
        newStack.set(DataComponentTypes.LORE, LoreComponent(loreText))
        return newStack
    }

    fun setItemLore(itemStack: ItemStack, loreLines: List<Any?>) {
        val textLines = loreLines.mapNotNull { line ->
            when (line) {
                is Text -> line
                is String -> Text.literal(line)
                null -> null
                else -> Text.literal(line.toString())
            }
        }

        if (textLines.size != loreLines.size) {
            itemStack.set(
                DataComponentTypes.LORE,
                LoreComponent(listOf(Text.literal("§cError setting lore"), Text.literal("§7One of the lines was null")))
            )
            return
        }

        itemStack.set(DataComponentTypes.LORE, LoreComponent(textLines))
    }

    fun stripFormatting(text: String): String {
        return text.replace(Regex("§[0-9a-fk-or]"), "")
    }

    fun addEnchantmentGlint(itemStack: ItemStack) {
        itemStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
    }

    fun refreshGui(player: ServerPlayerEntity, newLayout: List<ItemStack>) {
        val screenHandler = player.currentScreenHandler as? CustomScreenHandler
        screenHandler?.updateInventory(newLayout) ?: run {
            logger.warn("Player ${player.name.string} does not have the expected screen handler open.")
        }
    }

    fun closeGui(player: ServerPlayerEntity) {
        player.closeHandledScreen()
    }
}

class CustomScreenHandler(
    syncId: Int,
    playerInventory: PlayerInventory,
    layout: List<ItemStack>,
    val rows: Int,
    private var onInteract: ((InteractionContext) -> Unit)?,
    private var onClose: ((Inventory) -> Unit)?
) : ScreenHandler(
    when (rows) {
        3 -> ScreenHandlerType.GENERIC_9X3
        6 -> ScreenHandlerType.GENERIC_9X6
        else -> ScreenHandlerType.GENERIC_9X6
    }, syncId) {

    private val containerSize = rows * 9
    private val virtualLayout = mutableListOf<ItemStack>()
    private lateinit var player: ServerPlayerEntity

    init {
        virtualLayout.addAll(layout.take(containerSize).map { it.copy() })
        while (virtualLayout.size < containerSize) virtualLayout.add(ItemStack.EMPTY)

        val dummyInventory = object : Inventory {
            override fun clear() {}
            override fun size() = containerSize
            override fun isEmpty() = virtualLayout.all { it.isEmpty }
            override fun getStack(slot: Int) = if (slot in 0 until containerSize) virtualLayout[slot] else ItemStack.EMPTY
            override fun removeStack(slot: Int, amount: Int) = ItemStack.EMPTY
            override fun removeStack(slot: Int) = ItemStack.EMPTY
            override fun setStack(slot: Int, stack: ItemStack) {
                if (slot in 0 until containerSize) {
                    virtualLayout[slot] = stack.copy()
                }
            }
            override fun markDirty() {}
            override fun canPlayerUse(player: PlayerEntity) = true
        }
        // Add container slots (variable based on rows)
        for (i in 0 until containerSize) {
            addSlot(InteractiveSlot(dummyInventory, i, false))
        }

        // Adjust player's inventory slot positions based on container size.
        // For double chest (rows == 6) the positions remain the same,
        // for a single chest (rows == 3) they are shifted upward.
        val offset = (6 - rows) * 6  // 0 for 6 rows, 18 for 3 rows
        for (i in 0..2) {
            for (j in 0..8) {
                val index = j + i * 9 + 9
                addSlot(Slot(playerInventory, index, 8 + j * 18, (84 - offset) + i * 18))
            }
        }
        for (k in 0..8) {
            addSlot(Slot(playerInventory, k, 8 + k * 18, 142 - offset))
        }

        if (playerInventory.player is ServerPlayerEntity) {
            player = playerInventory.player as ServerPlayerEntity
        }
    }

    override fun canUse(player: PlayerEntity) = true

    override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
        if (slotIndex < 0 || slotIndex >= slots.size) {
            return
        }
        val isGuiSlot = slotIndex in 0 until containerSize
        if (isGuiSlot && player is ServerPlayerEntity) {
            val stack = virtualLayout[slotIndex]
            val clickType = if (button == 0) ClickType.LEFT else ClickType.RIGHT
            val context = InteractionContext(slotIndex, clickType, button, stack, player)
            onInteract?.invoke(context)
            player.networkHandler.sendPacket(
                ScreenHandlerSlotUpdateS2CPacket(syncId, nextRevision(), slotIndex, stack)
            )
            return
        }
        if (!isGuiSlot) {
            super.onSlotClick(slotIndex, button, actionType, player)
        }
    }

    /**
     * Blocks quick moving (shift-clicking) by always returning an empty stack.
     */
    override fun quickMove(player: PlayerEntity, index: Int): ItemStack {
        return ItemStack.EMPTY // Explicitly disable shift-click transfers
    }

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        onClose?.invoke(object : Inventory {
            override fun clear() {}
            override fun size() = containerSize
            override fun isEmpty() = virtualLayout.all { it.isEmpty }
            override fun getStack(slot: Int) = virtualLayout[slot]
            override fun removeStack(slot: Int, amount: Int) = ItemStack.EMPTY
            override fun removeStack(slot: Int) = ItemStack.EMPTY
            override fun setStack(slot: Int, stack: ItemStack) {}
            override fun markDirty() {}
            override fun canPlayerUse(player: PlayerEntity) = true
        })
        onInteract = null
        onClose = null
    }

    fun updateInventory(newLayout: List<ItemStack>) {
        virtualLayout.clear()
        virtualLayout.addAll(newLayout.take(containerSize).map { it.copy() })
        while (virtualLayout.size < containerSize) virtualLayout.add(ItemStack.EMPTY)
        sendContentUpdates()
    }
}

class InteractiveSlot(
    inventory: Inventory,
    index: Int,
    private val isInteractive: Boolean
) : Slot(
    inventory,
    index,
    8 + (index % 9) * 18,
    18 + (index / 9) * 18
) {
    override fun canInsert(stack: ItemStack) = isInteractive
    override fun canTakeItems(player: PlayerEntity) = isInteractive
}

fun ItemStack.setCustomName(name: Text) {
    this.set(DataComponentTypes.ITEM_NAME, name)
}
