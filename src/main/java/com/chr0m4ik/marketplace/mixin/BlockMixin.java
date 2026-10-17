package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.mining.MagnetHandler;
import com.chr0m4ik.marketplace.mining.SmelterHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Block.class)
public abstract class BlockMixin {

	@ModifyVariable(
			method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
			at = @At("HEAD"),
			argsOnly = true)
	private static ItemStack marketplace$specialDrop(ItemStack itemStack) {
		ItemStack result = SmelterHandler.smelt(itemStack);
		MagnetHandler.absorb(result);
		return result;
	}
}
