package com.raiiiden.ragdollified.mixin;

import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Vanilla keeps a blast's centre and size private, and the server needs both to throw the bodies already lying in it.
@Mixin(Explosion.class)
public interface ExplosionAccessor {
    @Accessor("x")
    double ragdollified$getX();

    @Accessor("y")
    double ragdollified$getY();

    @Accessor("z")
    double ragdollified$getZ();

    @Accessor("radius")
    float ragdollified$getRadius();
}
