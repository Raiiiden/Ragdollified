package com.raiiiden.ragdollified.client;

import com.raiiiden.ragdollified.config.RagdollifiedConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Moving piston blocks shove ragdolls ahead of them the way vanilla shoves entities; the terrain colliders never see a block mid-stroke, so without this it lands on top of the body.
@OnlyIn(Dist.CLIENT)
public final class RagdollPistonPusher {
    private RagdollPistonPusher() {}

    // One moving block's collision this tick, in world space, and the way it is travelling.
    public record MovingBlock(Direction direction, List<AABB> boxes, AABB bounds) {}

    private static final Set<BlockPos> TRACKED = ConcurrentHashMap.newKeySet();
    private static volatile List<MovingBlock> moving = List.of();

    // A moving piston block just appeared here. Main thread, from the block-change hook.
    public static void track(BlockPos pos) {
        TRACKED.add(pos.immutable());
    }

    // Read every tracked block entity once the level has ticked it, for the physics tick about to be submitted. Main thread.
    public static void capture(ClientLevel level) {
        if (TRACKED.isEmpty()) {
            moving = List.of();
            return;
        }
        boolean enabled = level != null && RagdollifiedConfig.get(RagdollifiedConfig.PISTONS_PUSH_RAGDOLLS);
        List<MovingBlock> found = new ArrayList<>();
        Iterator<BlockPos> iterator = TRACKED.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (level == null || !(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity piston)) {
                iterator.remove();
                continue;
            }
            if (!enabled) continue;
            // The shape vanilla collides entities with, already moved to where this tick's progress put it.
            VoxelShape shape = piston.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            List<AABB> boxes = new ArrayList<>();
            for (AABB box : shape.toAabbs()) boxes.add(box.move(pos));
            found.add(new MovingBlock(piston.getMovementDirection(), List.copyOf(boxes), shape.bounds().move(pos)));
        }
        moving = found.isEmpty() ? List.of() : List.copyOf(found);
    }

    // Shove every body this client simulates out of the moving blocks' way. Physics worker, before the step.
    static void apply(Collection<ClientRagdoll> ragdolls) {
        List<MovingBlock> blocks = moving;
        if (blocks.isEmpty() || ragdolls.isEmpty()) return;
        for (ClientRagdoll ragdoll : ragdolls) {
            if (ragdoll.isDestroyed() || ragdoll.isReplicated()) continue;
            for (MovingBlock block : blocks) ragdoll.pushOutOfMovingBlock(block);
        }
    }

    public static void clear() {
        TRACKED.clear();
        moving = List.of();
    }
}
