package com.raiiiden.ragdollified.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Range-independent player skin resolution for corpses and ragdolls: mc.level.players() fails exactly
// when the owner is far away, so the connection PlayerInfo is used, with a per-UUID cache behind it.
public final class ClientPlayerSkinCache {

    public static final class Skin {
        public final ResourceLocation texture;
        public final boolean slim;
        Skin(ResourceLocation texture, boolean slim) { this.texture = texture; this.slim = slim; }
    }

    private static final Map<UUID, Skin> CACHE = new ConcurrentHashMap<>();

    private ClientPlayerSkinCache() {}

    // Resolve a player's skin at any range, never null: connection PlayerInfo first and cached on hit,
    // then the last cached real skin, then the default for the UUID.
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
