package me.tehnothedragon.rt_props.mixins

import me.tehnothedragon.rt_props.datapack.resources.BlockModificationResource
import net.minecraft.world.level.block.Block
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Block::class)
class BlockMixin {
    @Inject(
        method = ["getFriction"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun getFriction(
        callableInfoReturnable: CallbackInfoReturnable<Float>
    ) {
        val block = this as Block
        val modification = BlockModificationResource.getBlockModification(block) ?: return

        modification.friction.ifPresent {
            callableInfoReturnable.returnValue = it
        }
    }

    @Inject(
        method = ["getSpeedFactor"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun getSpeedFactor(
        callableInfoReturnable: CallbackInfoReturnable<Float>
    ) {
        val block = this as Block
        val modification = BlockModificationResource.getBlockModification(block) ?: return

        modification.factors.ifPresent {
            it.speed.ifPresent {
                callableInfoReturnable.returnValue = it
            }
        }
    }

    @Inject(
        method = ["getJumpFactor"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun getJumpFactor(
        callableInfoReturnable: CallbackInfoReturnable<Float>
    ) {
        val block = this as Block
        val modification = BlockModificationResource.getBlockModification(block) ?: return

        modification.factors.ifPresent {
            it.jump.ifPresent {
                callableInfoReturnable.returnValue = it
            }
        }
    }
}