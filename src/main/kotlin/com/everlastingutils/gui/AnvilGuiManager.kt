package com.everlastingutils.gui

import com.everlastingutils.colors.KyoriHelper
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.inventory.SimpleInventory
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

/**
 * A fully modular anvil GUI manager
 */
object AnvilGuiManager {
    private val logger = LoggerFactory.getLogger(AnvilGuiManager::class.java)

    /**
     * Original method for backwards compatibility - uses Text.literal
     */
    fun openAnvilGui(
        player: ServerPlayerEntity,
        id: String,
        title: String,
        initialText: String = "",
        leftItem: ItemStack? = null,
        rightItem: ItemStack? = null,
        resultItem: ItemStack? = null,
        onLeftClick: ((AnvilInteractionContext) -> Unit)? = null,
        onRightClick: ((AnvilInteractionContext) -> Unit)? = null,
        onResultClick: ((AnvilInteractionContext) -> Unit)? = null,
        onTextChange: ((String) -> Unit)? = null,
        onClose: ((Inventory) -> Unit)? = null
    ) {
        val factory = SimpleNamedScreenHandlerFactory(
            { syncId, inv, _ ->
                FullyModularAnvilScreenHandler(
                    syncId, inv, id,
                    initialText,
                    leftItem, rightItem, resultItem,
                    onLeftClick, onRightClick, onResultClick,
                    onTextChange, onClose
                )
            },
            Text.literal(title)
        )
        player.openHandledScreen(factory)
    }

    /**
     * New method that supports MiniMessage formatted titles
     */
    fun openAnvilGuiFormatted(
        player: ServerPlayerEntity,
        id: String,
        title: String,
        initialText: String = "",
        leftItem: ItemStack? = null,
        rightItem: ItemStack? = null,
        resultItem: ItemStack? = null,
        onLeftClick: ((AnvilInteractionContext) -> Unit)? = null,
        onRightClick: ((AnvilInteractionContext) -> Unit)? = null,
        onResultClick: ((AnvilInteractionContext) -> Unit)? = null,
        onTextChange: ((String) -> Unit)? = null,
        onClose: ((Inventory) -> Unit)? = null
    ) {
        val formattedTitle = try {
            KyoriHelper.parseToMinecraft(title, player.server.registryManager)
        } catch (e: Exception) {
            logger.warn("Failed to parse MiniMessage title, falling back to literal: ${e.message}")
            Text.literal(title)
        }

        val factory = SimpleNamedScreenHandlerFactory(
            { syncId, inv, _ ->
                FullyModularAnvilScreenHandler(
                    syncId, inv, id,
                    initialText,
                    leftItem, rightItem, resultItem,
                    onLeftClick, onRightClick, onResultClick,
                    onTextChange, onClose
                )
            },
            formattedTitle
        )
        player.openHandledScreen(factory)
    }

    /**
     * Alternative method that accepts Text directly
     */
    fun openAnvilGuiWithText(
        player: ServerPlayerEntity,
        id: String,
        title: Text,
        initialText: String = "",
        leftItem: ItemStack? = null,
        rightItem: ItemStack? = null,
        resultItem: ItemStack? = null,
        onLeftClick: ((AnvilInteractionContext) -> Unit)? = null,
        onRightClick: ((AnvilInteractionContext) -> Unit)? = null,
        onResultClick: ((AnvilInteractionContext) -> Unit)? = null,
        onTextChange: ((String) -> Unit)? = null,
        onClose: ((Inventory) -> Unit)? = null
    ) {
        val factory = SimpleNamedScreenHandlerFactory(
            { syncId, inv, _ ->
                FullyModularAnvilScreenHandler(
                    syncId, inv, id,
                    initialText,
                    leftItem, rightItem, resultItem,
                    onLeftClick, onRightClick, onResultClick,
                    onTextChange, onClose
                )
            },
            title
        )
        player.openHandledScreen(factory)
    }

    /**
     * Original simplified method for backwards compatibility
     */
    fun openAnvilGui(
        player: ServerPlayerEntity,
        id: String,
        title: String,
        initialText: String = "",
        layout: Map<Int, ItemStack> = emptyMap(),
        onInteract: ((AnvilInteractionContext) -> Unit)? = null,
        onTextChange: ((String) -> Unit)? = null,
        onClose: ((Inventory) -> Unit)? = null
    ) {
        openAnvilGui(
            player = player,
            id = id,
            title = title,
            initialText = initialText,
            leftItem = layout[0],
            rightItem = layout[1],
            resultItem = layout[2],
            onLeftClick = onInteract?.let { handler -> { context -> handler(context) } },
            onRightClick = onInteract?.let { handler -> { context -> handler(context) } },
            onResultClick = onInteract?.let { handler -> { context -> handler(context) } },
            onTextChange = onTextChange,
            onClose = onClose
        )
    }

    /**
     * New simplified method with MiniMessage support
     */
    fun openAnvilGuiFormatted(
        player: ServerPlayerEntity,
        id: String,
        title: String,
        initialText: String = "",
        layout: Map<Int, ItemStack> = emptyMap(),
        onInteract: ((AnvilInteractionContext) -> Unit)? = null,
        onTextChange: ((String) -> Unit)? = null,
        onClose: ((Inventory) -> Unit)? = null
    ) {
        openAnvilGuiFormatted(
            player = player,
            id = id,
            title = title,
            initialText = initialText,
            leftItem = layout[0],
            rightItem = layout[1],
            resultItem = layout[2],
            onLeftClick = onInteract?.let { handler -> { context -> handler(context) } },
            onRightClick = onInteract?.let { handler -> { context -> handler(context) } },
            onResultClick = onInteract?.let { handler -> { context -> handler(context) } },
            onTextChange = onTextChange,
            onClose = onClose
        )
    }

    /**
     * Extension function for convenience
     */
    fun ServerPlayerEntity.openAnvilGui(
        id: String,
        title: String,
        initialText: String = "",
        formatted: Boolean = false,
        layout: Map<Int, ItemStack> = emptyMap(),
        onInteract: ((AnvilInteractionContext) -> Unit)? = null,
        onTextChange: ((String) -> Unit)? = null,
        onClose: ((Inventory) -> Unit)? = null
    ) {
        if (formatted) {
            openAnvilGuiFormatted(this, id, title, initialText, layout, onInteract, onTextChange, onClose)
        } else {
            openAnvilGui(this, id, title, initialText, layout, onInteract, onTextChange, onClose)
        }
    }
}

data class AnvilInteractionContext(
    val slotIndex: Int,
    val clickType: ClickType,
    val button: Int,
    val clickedStack: ItemStack,
    val player: ServerPlayerEntity,
    val handler: FullyModularAnvilScreenHandler
)

class InteractiveSlotAnvil(
    inventory: Inventory,
    index: Int,
    x: Int,
    y: Int,
    private val isInteractive: Boolean
) : Slot(inventory, index, x, y) {
    override fun canInsert(stack: ItemStack) = isInteractive
    override fun canTakeItems(player: PlayerEntity) = isInteractive
}

// FullyModularAnvilScreenHandler remains unchanged
class FullyModularAnvilScreenHandler(
    syncId: Int,
    playerInventory: PlayerInventory,
    private val id: String,
    initialText: String,
    leftItem: ItemStack?,
    rightItem: ItemStack?,
    resultItem: ItemStack?,
    private val onLeftClick: ((AnvilInteractionContext) -> Unit)?,
    private val onRightClick: ((AnvilInteractionContext) -> Unit)?,
    private val onResultClick: ((AnvilInteractionContext) -> Unit)?,
    private val onTextChange: ((String) -> Unit)?,
    private val onClose: ((Inventory) -> Unit)?
) : AnvilScreenHandler(syncId, playerInventory, ScreenHandlerContext.EMPTY) {

    private val virtualItems = mutableListOf<ItemStack>()
    private val dummyInventory = object : Inventory {
        override fun clear() {}
        override fun size() = 3
        override fun isEmpty() = virtualItems.all { it.isEmpty }
        override fun getStack(slot: Int) = if (slot in 0..2) virtualItems[slot] else ItemStack.EMPTY
        override fun removeStack(slot: Int, amount: Int) = ItemStack.EMPTY
        override fun removeStack(slot: Int) = ItemStack.EMPTY
        override fun setStack(slot: Int, stack: ItemStack) {}
        override fun markDirty() {}
        override fun canPlayerUse(player: PlayerEntity) = true
    }
    private val guiInventory = SimpleInventory(3) // Unused but kept for compatibility
    var currentText: String = initialText
        private set
    private var isInitializing = true
    private var player: PlayerEntity? = playerInventory.player

    init {
        virtualItems.add(leftItem?.let { removeTitle(it) } ?: ItemStack.EMPTY)
        virtualItems.add(rightItem ?: ItemStack.EMPTY)
        virtualItems.add(resultItem ?: ItemStack.EMPTY)

        this.slots[0] = InteractiveSlotAnvil(dummyInventory, 0, this.slots[0].x, this.slots[0].y, false)
        this.slots[1] = InteractiveSlotAnvil(dummyInventory, 1, this.slots[1].x, this.slots[1].y, false)
        this.slots[2] = InteractiveSlotAnvil(dummyInventory, 2, this.slots[2].x, this.slots[2].y, false)

        setNewItemName("")
        if (initialText.isNotEmpty()) {
            setNewItemName(initialText)
        }

        isInitializing = false
    }

    private fun removeTitle(item: ItemStack): ItemStack {
        val copy = item.copy()
        copy.setCustomName(Text.literal(""))
        return copy
    }

    override fun setNewItemName(newName: String): Boolean {
        val result = super.setNewItemName(newName)
        if (!isInitializing || newName != currentText) {
            currentText = newName
            // Note: Ensure that onTextChange handles the input safely, e.g., validate and sanitize the text.
            onTextChange?.invoke(newName)
        }
        return result
    }

    override fun updateResult() {}

    override fun getLevelCost(): Int = 0

    override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
        if (slotIndex < 0 || slotIndex >= slots.size) {
            return
        }
        if (slotIndex in 0..2 && actionType == SlotActionType.PICKUP && player is ServerPlayerEntity) {
            val clickType = if (button == 0) ClickType.LEFT else ClickType.RIGHT
            val stack = virtualItems[slotIndex]
            val context = AnvilInteractionContext(slotIndex, clickType, button, stack, player, this)
            when (slotIndex) {
                0 -> onLeftClick?.invoke(context)
                1 -> onRightClick?.invoke(context)
                2 -> onResultClick?.invoke(context)
            }
            player.networkHandler.sendPacket(
                ScreenHandlerSlotUpdateS2CPacket(syncId, nextRevision(), slotIndex, stack)
            )
            return
        }
        super.onSlotClick(slotIndex, button, actionType, player)
    }

    override fun quickMove(player: PlayerEntity, index: Int): ItemStack {
        return ItemStack.EMPTY // Explicitly disable shift-click transfers
    }

    override fun canUse(player: PlayerEntity): Boolean = true

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        onClose?.invoke(dummyInventory)
    }

    fun updateSlot(slot: Int, item: ItemStack?) {
        if (slot in 0..2) {
            val newStack = item?.let { removeTitle(it) } ?: ItemStack.EMPTY
            virtualItems[slot] = newStack
            (player as? ServerPlayerEntity)?.networkHandler?.sendPacket(
                ScreenHandlerSlotUpdateS2CPacket(syncId, nextRevision(), slot, newStack)
            )
            if (slot == 0) {
                val tempText = currentText
                setNewItemName("")
                setNewItemName(tempText)
            }
        }
    }

    fun clearTextField() {
        setNewItemName("")
    }
}