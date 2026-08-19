package com.raiiiden.ragdollified.client.screen;

import com.raiiiden.ragdollified.menu.CorpseMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

// Corpse loot screen drawn from the vanilla GUI palette rather than the chest texture, so it can
// absorb any number of curio slots. Frames are drawn under every menu slot CorpseMenu placed.
public class CorpseScreen extends AbstractContainerScreen<CorpseMenu> {

    // Vanilla inventory palette.
    private static final int PANEL_BG    = 0xFFC6C6C6;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_DARK  = 0xFF555555;
    private static final int SLOT_BG     = 0xFF8B8B8B;
    private static final int BEVEL_DARK  = 0xFF373737;
    private static final int BEVEL_LIGHT = 0xFFFFFFFF;

    public CorpseScreen(CorpseMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = menu.getImageWidth();
        this.imageHeight = menu.getImageHeight();
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = menu.getCorpseInventoryLabelY();
    }

    @Override
    protected void init() {
        super.init();
        // "Take All" / "Swap" buttons, right-aligned on their own row under the title.
        int h = this.menu.getButtonHeight();
        int y = this.topPos + this.menu.getButtonRowY();
        Button swap = Button.builder(Component.literal("Swap"), b -> press(CorpseMenu.BTN_SWAP))
                .bounds(0, y, this.font.width("Swap") + 10, h).build();
        Button takeAll = Button.builder(Component.literal("Take All"), b -> press(CorpseMenu.BTN_TAKE_ALL))
                .bounds(0, y, this.font.width("Take All") + 10, h).build();
        swap.setX(this.leftPos + this.imageWidth - 6 - swap.getWidth());
        takeAll.setX(swap.getX() - 3 - takeAll.getWidth());
        addRenderableWidget(takeAll);
        addRenderableWidget(swap);
    }

    // Send a vanilla menu-button click so the server runs clickMenuButton.
    private void press(int buttonId) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        drawPanel(g, x, y, this.imageWidth, this.imageHeight);

        // Section dividers (top..bottom): armor|curios, equipment|loot, loot|player inventory.
        if (this.menu.getCurioDividerY() >= 0) {
            drawDivider(g, x, y + this.menu.getCurioDividerY(), this.imageWidth);
        }
        drawDivider(g, x, y + this.menu.getEquipDividerY(), this.imageWidth);
        drawDivider(g, x, y + this.menu.getPlayerDividerY(), this.imageWidth);

        // A recessed frame under every slot (corpse + player). The parent draws the empty-slot
        // silhouettes and item stacks on top of these.
        for (Slot slot : this.menu.slots) {
            drawSlot(g, x + slot.x, y + slot.y);
        }
    }

    // Raised beige panel with a 1px bevel, matching the vanilla inventory window.
    private static void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x, y, x + w, y + 1, PANEL_LIGHT);            // top
        g.fill(x, y, x + 1, y + h, PANEL_LIGHT);            // left
        g.fill(x + w - 1, y, x + w, y + h, PANEL_DARK);     // right
        g.fill(x, y + h - 1, x + w, y + h, PANEL_DARK);     // bottom
    }

    // A 2px beveled horizontal divider spanning the panel's inner width.
    private static void drawDivider(GuiGraphics g, int x, int dy, int w) {
        g.fill(x + 7, dy, x + w - 7, dy + 1, BEVEL_DARK);
        g.fill(x + 7, dy + 1, x + w - 7, dy + 2, BEVEL_LIGHT);
    }

    // A vanilla-style recessed 18x18 slot. (sx, sy) is the 16x16 content origin.
    private static void drawSlot(GuiGraphics g, int sx, int sy) {
        g.fill(sx - 1, sy - 1, sx + 17, sy,      BEVEL_DARK);   // top edge
        g.fill(sx - 1, sy - 1, sx,      sy + 17, BEVEL_DARK);   // left edge
        g.fill(sx - 1, sy + 16, sx + 17, sy + 17, BEVEL_LIGHT); // bottom edge
        g.fill(sx + 16, sy - 1, sx + 17, sy + 17, BEVEL_LIGHT); // right edge
        g.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);              // interior
    }

    // The title has the whole first line to itself; only a name wider than the panel is trimmed.
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        int max = this.imageWidth - this.titleLabelX * 2;
        Component name = this.title;
        if (this.font.width(name) > max) {
            name = Component.literal(this.font.plainSubstrByWidth(name.getString(), max - this.font.width("...")) + "...");
        }
        g.drawString(this.font, name, this.titleLabelX, this.titleLabelY, 0x404040, false);
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }
}
