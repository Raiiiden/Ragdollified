package com.raiiiden.ragdollified.mixin;

import com.raiiiden.ragdollified.client.ClientRagdollManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class ClientLevelBlockChangeMixin {

    @Inject(method = "onBlockStateChange", at = @At("TAIL"))
    private void ragdollified$queueCollisionUpdate(BlockPos pos, BlockState oldState,
                                                   BlockState newState, CallbackInfo ci) {
        Level level = (Level) (Object) this;
        if (level.isClientSide && oldState != newState) {
            ClientRagdollManager.enqueueBlockChange(pos);
        }
    }
}
