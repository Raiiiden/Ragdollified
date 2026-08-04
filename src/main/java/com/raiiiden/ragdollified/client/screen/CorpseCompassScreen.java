package com.raiiiden.ragdollified.client.screen;

import com.raiiiden.ragdollified.item.CorpseCompassItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

// Locator screen for CorpseCompassItem, opened by right-clicking it: bound corpse coordinates
// and owner, a static body render with skin and baked death armor, and a clickable Corpse ID
// that copies the OP retrieve command. Client-side and informational, with no server menu.
public class CorpseCompassScreen extends Screen {

    private static final int PANEL_BG    = 0xFFC6C6C6;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_DARK  = 0xFF555555;
    private static final int BEVEL_DARK  = 0xFF373737;
    private static final int BEVEL_LIGHT = 0xFFFFFFFF;

    private final ItemStack stack;
    private final int imageWidth = 248;
    private final int imageHeight = 166;
    private int leftPos;
    private int topPos;

    @Nullable private final UUID corpseId;
    private final Vec3 targetPos;
    @Nullable private final ResourceKey<Level> targetDim;
    private final String ownerName;
    private final ItemStack helmet, chest, legs, boots;

    // Cached bounds of the clickable Corpse ID line (set each render, read on click/hover).
    private int idX, idY, idW, idH;
    private long copiedUntil = 0L;

    public static void open(ItemStack stack) {
        Minecraft.getInstance().setScreen(new CorpseCompassScreen(stack.copy()));
    }

    private CorpseCompassScreen(ItemStack stack) {
        super(Component.translatable("item.ragdollified.corpse_compass"));
        this.stack = stack;
        this.corpseId = CorpseCompassItem.getCorpseId(stack);
        this.targetPos = CorpseCompassItem.getTargetPos(stack);
        this.targetDim = CorpseCompassItem.getTargetDimension(stack);
        String owner = CorpseCompassItem.getOwnerName(stack);
        this.ownerName = owner.isEmpty() ? "Unknown" : owner;
        this.helmet = CorpseCompassItem.getArmor(stack, CorpseCompassItem.TAG_HELMET);
        this.chest  = CorpseCompassItem.getArmor(stack, CorpseCompassItem.TAG_CHEST);
        this.legs   = CorpseCompassItem.getArmor(stack, CorpseCompassItem.TAG_LEGS);
        this.boots  = CorpseCompassItem.getArmor(stack, CorpseCompassItem.TAG_BOOTS);
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - imageWidth) / 2;
        this.topPos = (this.height - imageHeight) / 2;

        // Small, right-aligned Done button (was oversized and left-of-center).
        int w = 54;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(leftPos + imageWidth - 8 - w, topPos + imageHeight - 26, w, 20).build());
    }

    private void copyCommand() {
        if (corpseId == null || this.minecraft == null) return;
        this.minecraft.keyboardHandler.setClipboard("/ragdollified retrievecorpse " + corpseId);
        this.copiedUntil = System.currentTimeMillis() + 1600L;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        drawPanel(g, leftPos, topPos, imageWidth, imageHeight);
        drawVDivider(g, leftPos + 100, topPos + 18, imageHeight - 44);

        g.drawString(this.font, Component.translatable("gui.ragdollified.corpse_compass.title", ownerName),
                leftPos + 8, topPos + 6, 0x404040, false);

        // Left: recessed preview box + static body render.
        int boxX0 = leftPos + 8, boxY0 = topPos + 20, boxX1 = leftPos + 96, boxY1 = topPos + 150;
        g.fill(boxX0, boxY0, boxX1, boxY1, 0xFF8B8B8B);
        drawInsetBorder(g, boxX0, boxY0, boxX1, boxY1);
        renderBody(g, mouseX, mouseY);

        // Right: corpse info.
        int rx = leftPos + 108, ry = topPos + 22;
        int line = this.font.lineHeight + 3;
        g.drawString(this.font, Component.translatable("gui.ragdollified.corpse_compass.location")
                .withStyle(ChatFormatting.DARK_GRAY), rx, ry, 0x404040, false);
        ry += line + 1;
        g.drawString(this.font, "X: " + (int) Math.floor(targetPos.x), rx, ry, 0x333333, false); ry += line;
        g.drawString(this.font, "Y: " + (int) Math.floor(targetPos.y), rx, ry, 0x333333, false); ry += line;
        g.drawString(this.font, "Z: " + (int) Math.floor(targetPos.z), rx, ry, 0x333333, false); ry += line + 2;
        String dim = targetDim != null ? targetDim.location().toString() : "?";
        g.drawString(this.font, "Dim: " + dim, rx, ry, 0x555555, false); ry += line + 6;

        // Clickable Corpse ID line (acts like a link; click copies the retrieve command).
        Component idLabel = Component.translatable("gui.ragdollified.corpse_compass.id",
                corpseId != null ? shortId(corpseId) : "unknown");
        this.idX = rx;
        this.idY = ry;
        this.idW = this.font.width(idLabel);
        this.idH = this.font.lineHeight;
        boolean hoverId = corpseId != null && inRect(mouseX, mouseY, idX, idY, idW, idH);
        int idColor = corpseId == null ? 0x888888 : (hoverId ? 0x1B6FB3 : 0x3A6EA5);
        g.drawString(this.font, idLabel, idX, idY, idColor, false);
        if (hoverId) {
            g.fill(idX, idY + idH, idX + idW, idY + idH + 1, 0xFF1B6FB3); // underline
        }

        if (System.currentTimeMillis() < copiedUntil) {
            g.drawString(this.font, Component.translatable("gui.ragdollified.corpse_compass.copied"),
                    rx, ry + line + 2, 0x2E7D32, false);
        }

        super.render(g, mouseX, mouseY, partialTick);

        if (hoverId) {
            g.renderTooltip(this.font, Component.translatable("gui.ragdollified.corpse_compass.copy.tooltip"),
                    mouseX, mouseY);
        }
    }

    private void renderBody(GuiGraphics g, int mouseX, int mouseY) {
        if (this.minecraft == null || this.minecraft.player == null) return;
        LocalPlayer player = this.minecraft.player;

        // Show the DEATH loadout, not the player's current gear: temporarily equip the baked armor
        // (and clear hands) around the render, then restore in finally. Safe — the world already
        // rendered this frame with the real gear, so there's no flicker, and setItemSlot is a
        // client-only visual change (no packets). Vanilla's renderer handles lighting, the
        // cast-shadow toggle, sizing, and mouse-follow correctly, which the hand-rolled path did not.
        ItemStack sHead = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack sChest = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack sLegs = player.getItemBySlot(EquipmentSlot.LEGS);
        ItemStack sFeet = player.getItemBySlot(EquipmentSlot.FEET);
        ItemStack sMain = player.getItemBySlot(EquipmentSlot.MAINHAND);
        ItemStack sOff = player.getItemBySlot(EquipmentSlot.OFFHAND);
        try {
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            player.setItemSlot(EquipmentSlot.CHEST, chest);
            player.setItemSlot(EquipmentSlot.LEGS, legs);
            player.setItemSlot(EquipmentSlot.FEET, boots);
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);

            int cx = leftPos + 52;
            int feetY = topPos + 142;
            int scale = 55;
            // Follow the mouse cursor (the in-GUI "crosshair"), like the inventory portrait.
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, feetY, scale,
                    (float) cx - mouseX, (float) (feetY - 90) - mouseY, player);
        } finally {
            player.setItemSlot(EquipmentSlot.HEAD, sHead);
            player.setItemSlot(EquipmentSlot.CHEST, sChest);
            player.setItemSlot(EquipmentSlot.LEGS, sLegs);
            player.setItemSlot(EquipmentSlot.FEET, sFeet);
            player.setItemSlot(EquipmentSlot.MAINHAND, sMain);
            player.setItemSlot(EquipmentSlot.OFFHAND, sOff);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && corpseId != null && inRect((int) mouseX, (int) mouseY, idX, idY, idW, idH)) {
            copyCommand();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean inRect(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8) + "…"; // full id is copied on click
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- vanilla-style panel drawing (shared look with CorpseScreen) ----

    private static void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x, y, x + w, y + 1, PANEL_LIGHT);
        g.fill(x, y, x + 1, y + h, PANEL_LIGHT);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_DARK);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_DARK);
    }

    private static void drawVDivider(GuiGraphics g, int x, int y, int h) {
        g.fill(x, y, x + 1, y + h, BEVEL_DARK);
        g.fill(x + 1, y, x + 2, y + h, BEVEL_LIGHT);
    }

    private static void drawInsetBorder(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0 - 1, y0 - 1, x1 + 1, y0, BEVEL_DARK);
        g.fill(x0 - 1, y0 - 1, x0, y1 + 1, BEVEL_DARK);
        g.fill(x0 - 1, y1, x1 + 1, y1 + 1, BEVEL_LIGHT);
        g.fill(x1, y0 - 1, x1 + 1, y1 + 1, BEVEL_LIGHT);
    }
}
