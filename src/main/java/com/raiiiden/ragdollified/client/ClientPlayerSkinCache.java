package com.raiiiden.ragdollified.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Range-independent player skin resolution and cache for corpses and ragdolls.
//
// A dead player's skin has to stay on their body for anyone standing near it, including after
// the owner leaves render range or disconnects. Resolving from mc.level.players() breaks in
// exactly that case — observer next to the body, owner far away, entity unloaded, skin falls
// back to Steve. The connection's PlayerInfo (tab-list entry) survives at any range, so it is
// the source here, with a per-UUID cache of the last real skin to survive a disconnect too.
public final class ClientPlayerSkinCache {

    public static final class Skin {
        public final ResourceLocation texture;
        public final boolean slim;
        Skin(ResourceLocation texture, boolean slim) { this.texture = texture; this.slim = slim; }
    }

    private static final Map<UUID, Skin> CACHE = new ConcurrentHashMap<>();

    private ClientPlayerSkinCache() {}

    // Resolve a player's skin at any range, never null. Tries the connection PlayerInfo first
    // (good while connected, cached on hit), then the last cached real skin (survives a
    // disconnect), then the default skin for the UUID.
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
