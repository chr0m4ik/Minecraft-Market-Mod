package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.mining.BreakContext;
import com.chr0m4ik.marketplace.mining.MagnetHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

	@Shadow
	@Final
	protected ServerPlayer player;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void marketplace$beginBreak(BlockPos pos, CallbackInfoReturnable<Boolean> info) {

		BlockState state = this.player.level().getBlockState(pos);

		if (BreakContext.begin(this.player, state.getBlock())) {

			MagnetHandler.onSwingStart(this.player);
		}
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void marketplace$endBreak(BlockPos pos, CallbackInfoReturnable<Boolean> info) {
		BreakContext.end();
	}
}
