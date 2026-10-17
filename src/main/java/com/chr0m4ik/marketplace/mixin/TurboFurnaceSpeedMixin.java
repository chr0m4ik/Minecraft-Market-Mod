package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.turbo.TurboFurnaceBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class TurboFurnaceSpeedMixin {

	@Inject(method = "getSpeedMultiplier", at = @At("HEAD"), cancellable = true)
	private void marketplace$turboSpeed(ServerLevel level, ItemStack fuel, CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof TurboFurnaceBlockEntity turbo) {
			cir.setReturnValue(turbo.speedMultiplier());
		}
	}
}
