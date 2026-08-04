package com.raiiiden.ragdollified.menu;

import com.raiiiden.ragdollified.compat.CuriosCompat;
import com.raiiiden.ragdollified.entity.CorpseEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Loot menu for a corpse. Its container mirrors a player inventory by slot index — 0-8 hotbar,
// 9-35 main, 36-39 armor, 40 offhand — with captured curios appended from 41.
//
// CorpseScreen draws the whole GUI with no chest texture, grouping slots into an equipment band
// (armor and offhand on one row, curios on their own), the corpse loot grid, and the looting
// player's inventory, split by dividers. This class owns every slot coordinate and exposes the
// divider and label positions so the screen stays in sync.
public class CorpseMenu extends AbstractContainerMenu {

    public static final int WIDTH = 176;
    private static final int SLOT = 18;
    private static final int LEFT = 8; // x of the first column's slot content

    // Button ids sent from com.raiiiden.ragdollified.client.screen.CorpseScreen.
    public static final int BTN_TAKE_ALL = 0;
    public static final int BTN_SWAP = 1;

    private final Container corpse;
    private final int corpseSlots; // armor/offhand/curios + loot grid (all corpse-backed slots)
    private final List<String> curioIds;

    // Layout the screen reads back so its background matches the slot positions exactly.
    private final int imageHeight;
    private final int equipDividerY;       // line below the equipment band
    private final int curioDividerY;       // line between armor row and curio rows (-1 if no curios)
    private final int playerDividerY;      // line above the looting player's inventory
    private final int inventoryLabelY;     // y of the "Inventory" label

    // Client constructor (IForgeMenuType). Reads the curio layout; contents arrive via slot sync.
    public CorpseMenu(int id, Inventory playerInv, FriendlyByteBuf buf) {
        this(id, playerInv, readCurioIds(buf));
    }

    private CorpseMenu(int id, Inventory playerInv, List<String> curioIds) {
        this(id, playerInv, new SimpleContainer(CorpseEntity.VANILLA_SLOTS + curioIds.size()), curioIds);
    }

    private static List<String> readCurioIds(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readUtf());
        return ids;
    }

    // Server constructor.
    public CorpseMenu(int id, Inventory playerInv, Container corpse, List<String> curioIds) {
        super(ModMenus.CORPSE.get(), id);
        this.corpse = corpse;
        this.curioIds = curioIds;
        corpse.startOpen(playerInv.player);
        Player owner = playerInv.player;

        int n = curioIds.size();
        int curioRows = (n + 8) / 9; // ceil
        this.corpseSlots = CorpseEntity.VANILLA_SLOTS + n;

        // --- Vertical layout ---------------------------------------------------------------
        int armorRowY = 19;                                   // armor + offhand row
        int curiosStartY;
        int equipBottomY;
        if (n > 0) {
            this.curioDividerY = armorRowY + SLOT + 2;        // divider between armor and curios
            curiosStartY = curioDividerY + 5;
            equipBottomY = curiosStartY + curioRows * SLOT;
        } else {
            this.curioDividerY = -1;
            curiosStartY = armorRowY;                         // unused
            equipBottomY = armorRowY + SLOT;
        }
        this.equipDividerY = equipBottomY + 3;
        int lootGridY = equipDividerY + 6;                    // 4 rows (27 main + 9 hotbar)
        int lootBottomY = lootGridY + 4 * SLOT;
        this.playerDividerY = lootBottomY + 3;
        int playerInvY = playerDividerY + 13;                 // room for the "Inventory" label
        this.inventoryLabelY = playerInvY - 11;
        // player inv: 3 rows + hotbar (with the usual 4px gap)
        int playerInvBottomY = playerInvY + 3 * SLOT + 4 + SLOT;
        this.imageHeight = playerInvBottomY + 7;

        // --- Equipment band ----------------------------------------------------------------
        // Armor (helmet..boots) at columns 0-3; offhand at column 5 (a gap separates them).
        addSlot(armorSlot(corpse, 39, colX(0), armorRowY, EquipmentSlot.HEAD,  owner, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET));
        addSlot(armorSlot(corpse, 38, colX(1), armorRowY, EquipmentSlot.CHEST, owner, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE));
        addSlot(armorSlot(corpse, 37, colX(2), armorRowY, EquipmentSlot.LEGS,  owner, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS));
        addSlot(armorSlot(corpse, 36, colX(3), armorRowY, EquipmentSlot.FEET,  owner, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS));
        addSlot(new Slot(corpse, 40, colX(5), armorRowY).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD));
        // Curios on their own rows below the divider, 9 per row.
        for (int k = 0; k < n; k++) {
            addSlot(curioSlot(corpse, CorpseEntity.VANILLA_SLOTS + k,
                    colX(k % 9), curiosStartY + (k / 9) * SLOT, curioIds.get(k), owner));
        }

        // --- Corpse loot grid (slots 9-35 then hotbar 0-8) ---------------------------------
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(corpse, 9 + row * 9 + col, colX(col), lootGridY + row * SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(corpse, col, colX(col), lootGridY + 3 * SLOT));
        }

        // --- Looting player's inventory ----------------------------------------------------
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, colX(col), playerInvY + row * SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, colX(col), playerInvY + 3 * SLOT + 4));
        }
    }

    private static int colX(int col) { return LEFT + col * SLOT; }

    public int getImageWidth() { return WIDTH; }
    public int getImageHeight() { return imageHeight; }
    public int getEquipDividerY() { return equipDividerY; }
    public int getCurioDividerY() { return curioDividerY; }
    public int getPlayerDividerY() { return playerDividerY; }
    public int getCorpseInventoryLabelY() { return inventoryLabelY; }

    // An armor slot that, like vanilla, only accepts items equippable in eq.
    private static Slot armorSlot(Container c, int idx, int x, int y, EquipmentSlot eq,
                                  Player owner, ResourceLocation icon) {
        Slot slot = new Slot(c, idx, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !stack.isEmpty() && stack.canEquip(eq, owner);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
        slot.setBackground(InventoryMenu.BLOCK_ATLAS, icon);
        return slot;
    }

    // A curio slot that only accepts items valid for that curio slot type.
    private static Slot curioSlot(Container c, int idx, int x, int y, String id, Player owner) {
        Slot slot = new Slot(c, idx, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return CuriosCompat.isValid(id, owner, stack);
            }
        };
        if (CuriosCompat.isLoaded()) {
            ResourceLocation icon = CuriosCompat.getSlotIcon(id);
            if (icon != null) slot.setBackground(InventoryMenu.BLOCK_ATLAS, icon);
        }
        return slot;
    }

    @Override
    public boolean stillValid(Player player) {
        return corpse.stillValid(player);
    }

    // Server side of the screen's Take All / Swap buttons, driven by the vanilla menu-button
    // packet. Player main-inventory slots belong to this menu, so they sync on the auto-broadcast
    // once this returns true. Armor and offhand are not menu slots, and while a custom container
    // is open the client ignores container-0 inventory packets — inventoryMenu.broadcastChanges()
    // silently drops them, which was the ghost-armor bug — so they go out as container-(-2) slot
    // packets the client applies regardless of the open screen. Curios sync on their own tick.
    @Override
    public boolean clickMenuButton(Player player, int id) {
        switch (id) {
            case BTN_TAKE_ALL -> takeAll(player);
            case BTN_SWAP -> swap(player);
            default -> { return false; }
        }
        corpse.setChanged();
        if (player instanceof ServerPlayer sp) syncEquipmentSlots(sp);
        return true;
    }

    // Resync the player's armor (36-39) and offhand (40) slots directly to the client.
    private static void syncEquipmentSlots(ServerPlayer sp) {
        for (int i = 36; i <= 40; i++) {
            sp.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, sp.getInventory().getItem(i)));
        }
    }

    // Strip the corpse: fill empty player armor, offhand, and curio slots from the matching
    // corpse slot first, then take the rest as a normal pickup. Whatever does not fit stays.
    private void takeAll(Player player) {
        // Equip armor into empty slots (corpse 39..36 = head..feet).
        final int[] armorIdx = {39, 38, 37, 36};
        final EquipmentSlot[] eqs = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int j = 0; j < armorIdx.length; j++) {
            ItemStack a = corpse.getItem(armorIdx[j]);
            if (!a.isEmpty() && player.getItemBySlot(eqs[j]).isEmpty() && a.canEquip(eqs[j], player)) {
                player.setItemSlot(eqs[j], a);
                corpse.setItem(armorIdx[j], ItemStack.EMPTY);
            }
        }
        // Offhand.
        ItemStack off = corpse.getItem(40);
        if (!off.isEmpty() && player.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
            player.setItemSlot(EquipmentSlot.OFFHAND, off);
            corpse.setItem(40, ItemStack.EMPTY);
        }
        // Curios into empty matching slots.
        if (CuriosCompat.isLoaded()) {
            for (int k = 0; k < curioIds.size(); k++) {
                int slot = CorpseEntity.VANILLA_SLOTS + k;
                ItemStack s = corpse.getItem(slot);
                if (!s.isEmpty() && CuriosCompat.equipInEmpty(player, curioIds.get(k), s)) {
                    corpse.setItem(slot, ItemStack.EMPTY);
                }
            }
        }
        // Everything else: normal pickup into the player's inventory; leftovers stay on the corpse.
        for (int i = 0; i < corpseSlots; i++) {
            ItemStack s = corpse.getItem(i);
            if (s.isEmpty()) continue;
            player.getInventory().add(s);
            corpse.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
        }
    }

    // Exchange the player's whole inventory (main, armor, offhand, curios) with the corpse's.
    private void swap(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < CorpseEntity.VANILLA_SLOTS; i++) {
            ItemStack pStack = inv.getItem(i);
            ItemStack cStack = corpse.getItem(i);
            inv.setItem(i, cStack);
            corpse.setItem(i, pStack);
        }
        if (CuriosCompat.isLoaded()) {
            CuriosCompat.swapWorn(player, corpse, CorpseEntity.VANILLA_SLOTS, curioIds);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < corpseSlots) {
                // corpse -> player inventory
                if (!this.moveItemStackTo(stack, corpseSlots, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // player inventory -> corpse
                if (!this.moveItemStackTo(stack, 0, corpseSlots, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        corpse.stopOpen(player);
    }
}
