package com.everlastingutils.gui

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
     * Opens an anvil GUI for the player.
     *
     * @param player The player to open the GUI for.
     * @param id A unique identifier for the GUI.
     * @param title The title of the GUI.
     * @param initialText The initial text in the anvil's text field.
     * @param leftItem The item to display in the left slot.
     * @param rightItem The item to display in the right slot.
     * @param resultItem The item to display in the result slot.
     * @param onLeftClick Callback for when the left slot is clicked. Ensure to validate inputs and check permissions.
     * @param onRightClick Callback for when the right slot is clicked. Ensure to validate inputs and check permissions.
     * @param onResultClick Callback for when the result slot is clicked. Ensure to validate inputs and check permissions.
     * @param onTextChange Callback for when the text field changes. Ensure to validate and sanitize the input.
     * @param onClose Callback for when the GUI is closed.
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
     * Opens an anvil GUI with a simplified interaction callback.
     *
     * @param player The player to open the GUI for.
     * @param id A unique identifier for the GUI.
     * @param title The title of the GUI.
     * @param initialText The initial text in the anvil's text field.
     * @param layout Map of slot indices to ItemStacks.
     * @param onInteract Callback for slot interactions. Ensure to validate inputs and check permissions.
     * @param onTextChange Callback for text changes. Ensure to validate and sanitize the input.
     * @param onClose Callback for when the GUI is closed.
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

    /**
     * Blocks quick moving (shift-clicking) by always returning an empty stack.
     */
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