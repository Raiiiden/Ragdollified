package com.raiiiden.ragdollified.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Range-independent player skin resolution + cache for corpses and ragdolls.
 *
 * <p>A dead player's skin must stay visible on their corpse/ragdoll for everyone standing
 * near it — even after the owner walks out of render range (their {@code AbstractClientPlayer}
 * unloads) or disconnects entirely. Resolving from {@code mc.level.players()} only works while
 * the owner is loaded within render range, which is exactly the case that breaks: the observer
 * is next to the body, the owner is far away, so the entity is gone and the skin falls back to
 * default Steve/Alex.
 *
 * <p>The connection's {@link PlayerInfo} (the tab-list entry) persists for as long as the player
 * is connected, regardless of where either player is, so we resolve from there. We additionally
 * cache the last-known real skin per-UUID so it survives the owner disconnecting too.
 */
public final class ClientPlayerSkinCache {

    public static final class Skin {
        public final ResourceLocation texture;
        public final boolean slim;
        Skin(ResourceLocation texture, boolean slim) { this.texture = texture; this.slim = slim; }
    }

    private static final Map<UUID, Skin> CACHE = new ConcurrentHashMap<>();

    private ClientPlayerSkinCache() {}

    /**
     * Resolve a player's skin, range-independent. Resolution order:
     * <ol>
     *   <li>connection {@link PlayerInfo} — persists while connected at any range; cached on hit</li>
     *   <li>last cached real skin — survives the owner disconnecting</li>
     *   <li>default skin for the UUID</li>
     * </ol>
     * Never returns null.
     */
    public static Skin resolve(@Nullable UUID uuid) {
        if (uuid == null) {
            return new Skin(DefaultPlayerSkin.getDefaultSkin(), false);
        }
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(uuid) : null;
        if (info != null) {
            // getSkinLocation() lazily downloads/registers the skin texture; once registered it
            // stays in the TextureManager, so the cached ResourceLocation keeps rendering.
            Skin skin = new Skin(info.getSkinLocation(), "slim".equals(info.getModelName()));
            CACHE.put(uuid, skin);
            return skin;
        }
        Skin cached = CACHE.get(uuid);
        if (cached != null) return cached;
        return new Skin(DefaultPlayerSkin.getDefaultSkin(uuid),
                "slim".equals(DefaultPlayerSkin.getSkinModelName(uuid)));
    }

    public static void clear() { CACHE.clear(); }
}
