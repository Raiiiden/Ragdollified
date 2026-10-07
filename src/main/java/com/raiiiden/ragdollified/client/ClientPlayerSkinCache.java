package com.raiiiden.ragdollified.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Finds a player's skin at any range, and keeps a copy on disk so accounts with no skin online still show theirs.
public final class ClientPlayerSkinCache {

    public static final class Skin {
        public final ResourceLocation texture;
        public final boolean slim;
        // Null when the player has no cape or hides it. Never saved to disk, only kept for the session.
        @Nullable public final ResourceLocation cape;
        Skin(ResourceLocation texture, boolean slim) { this(texture, slim, null); }
        Skin(ResourceLocation texture, boolean slim, @Nullable ResourceLocation cape) {
            this.texture = texture; this.slim = slim; this.cape = cape;
        }
    }

    private static final Map<UUID, Skin> CACHE = new ConcurrentHashMap<>();
    // Players with nothing saved yet, so a miss costs one check instead of one every frame.
    private static final Set<UUID> NO_DISK_COPY = ConcurrentHashMap.newKeySet();
    private static final String SKIN_FOLDER = "ragdollified/skins";
    // Anything bigger than this is not a skin and is not worth copying.
    private static final int MAX_SKIN_SIZE = 1024;

    private ClientPlayerSkinCache() {}

    // Never null: the living player, then the server list, then this session, then disk, then the default.
    public static Skin resolve(@Nullable UUID uuid) {
        if (uuid == null) {
            return new Skin(DefaultPlayerSkin.getDefaultSkin(), false);
        }
        Minecraft mc = Minecraft.getInstance();

        // Asked first because skin mods change what the player shows, not what the server sent.
        Skin live = fromLivingPlayer(mc, uuid);
        if (live != null) {
            remember(uuid, live);
            return live;
        }

        PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(uuid) : null;
        if (info != null) {
            // The game loads the skin the first time this is asked and keeps it afterwards.
            ResourceLocation texture = info.getSkinLocation();
            // A default here means no skin at all, and keeping it would hide the real one.
            if (!isDefaultSkin(texture)) {
                Skin skin = new Skin(texture, "slim".equals(info.getModelName()), info.getCapeLocation());
                remember(uuid, skin);
                return skin;
            }
        }

        Skin cached = CACHE.get(uuid);
        if (cached != null) return cached;

        Skin saved = loadFromDisk(uuid);
        if (saved != null) {
            CACHE.put(uuid, saved);
            return saved;
        }

        // A default skin can still come with a cape.
        return new Skin(DefaultPlayerSkin.getDefaultSkin(uuid),
                "slim".equals(DefaultPlayerSkin.getSkinModelName(uuid)),
                info != null ? info.getCapeLocation() : null);
    }

    // Remember nearby players while they are alive, so a body dropped later already has a skin.
    public static void cacheNearbyPlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Player player : mc.level.players()) {
            resolve(player.getUUID());
        }
    }

    private static Skin fromLivingPlayer(Minecraft mc, UUID uuid) {
        if (mc.level == null) return null;
        Player player = mc.level.getPlayerByUUID(uuid);
        if (!(player instanceof AbstractClientPlayer client)) return null;
        ResourceLocation texture = client.getSkinTextureLocation();
        if (texture == null || isDefaultSkin(texture)) return null;
        // Asked of the player rather than the server list, so the cape toggle in skin options is kept.
        ResourceLocation cape = client.isCapeLoaded() && client.isModelPartShown(PlayerModelPart.CAPE)
                ? client.getCloakTextureLocation() : null;
        return new Skin(texture, "slim".equals(client.getModelName()), cape);
    }

    private static void remember(UUID uuid, Skin skin) {
        Skin previous = CACHE.put(uuid, skin);
        if (previous == null || !previous.texture.equals(skin.texture)) saveToDisk(uuid, skin);
    }

    // Every built-in skin sits in one folder, so this covers Steve, Alex and the rest.
    private static boolean isDefaultSkin(ResourceLocation texture) {
        return "minecraft".equals(texture.getNamespace())
                && texture.getPath().startsWith("textures/entity/player/");
    }

    private static Path skinFile(UUID uuid, boolean slim) {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve(SKIN_FOLDER).resolve(uuid + (slim ? "-slim.png" : ".png"));
    }

    // Copy the skin out of the picture the game already loaded, since nothing else has it.
    private static void saveToDisk(UUID uuid, Skin skin) {
        if (!RenderSystem.isOnRenderThread()) return;
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(skin.texture, null);
        if (texture == null) return;

        NativeImage image = null;
        try {
            texture.bind();
            int width = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width > MAX_SKIN_SIZE || height > MAX_SKIN_SIZE) return;
            image = new NativeImage(width, height, false);
            image.downloadTexture(0, false);
        } catch (Throwable t) {
            if (image != null) image.close();
            Ragdollified.LOGGER.debug("Could not read back the skin texture for {}", uuid, t);
            return;
        }

        NativeImage copy = image;
        Path file = skinFile(uuid, skin.slim);
        Path stale = skinFile(uuid, !skin.slim);
        Util.ioPool().execute(() -> {
            try (NativeImage held = copy) {
                Files.createDirectories(file.getParent());
                held.writeToFile(file);
                // Arm width is in the name, so the other file has to go or it wins later.
                Files.deleteIfExists(stale);
            } catch (Exception e) {
                Ragdollified.LOGGER.debug("Could not save the cached skin for {}", uuid, e);
            }
        });
        NO_DISK_COPY.remove(uuid);
    }

    private static Skin loadFromDisk(UUID uuid) {
        if (NO_DISK_COPY.contains(uuid)) return null;
        // Loading a picture needs the drawing thread, so this once the caller gets the default.
        if (!RenderSystem.isOnRenderThread()) return null;

        for (boolean slim : new boolean[]{false, true}) {
            Path file = skinFile(uuid, slim);
            if (!Files.isRegularFile(file)) continue;
            try (InputStream in = Files.newInputStream(file)) {
                ResourceLocation id = new ResourceLocation(Ragdollified.MODID, "cached_skin/" + uuid);
                Minecraft.getInstance().getTextureManager()
                        .register(id, new DynamicTexture(NativeImage.read(in)));
                return new Skin(id, slim);
            } catch (Exception e) {
                Ragdollified.LOGGER.debug("Could not load the cached skin for {}", uuid, e);
            }
        }
        NO_DISK_COPY.add(uuid);
        return null;
    }

    public static void clear() {
        CACHE.clear();
        NO_DISK_COPY.clear();
    }
}
