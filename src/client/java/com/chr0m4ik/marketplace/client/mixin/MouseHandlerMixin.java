package com.chr0m4ik.marketplace.client.mixin;

import com.chr0m4ik.marketplace.client.MarketplaceCursor;
import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	private double xpos;

	@Shadow
	private double ypos;

	@Inject(method = "onMove", at = @At("HEAD"))
	private void marketplace$rememberCursor(long window, double x, double y, double dx, double dy,
			CallbackInfo info) {
		MarketplaceCursor.remember(x, y);
	}

	@Inject(method = "releaseMouse", at = @At("RETURN"))
	private void marketplace$keepOnRelease(CallbackInfo info) {
		if (!marketplace$isOurScreen() || !MarketplaceCursor.hasPosition()) {
			return;
		}

		this.xpos = MarketplaceCursor.x();
		this.ypos = MarketplaceCursor.y();
		InputConstants.releaseMouse(Minecraft.getInstance().getWindow(), this.xpos, this.ypos);
	}

	private static boolean marketplace$isOurScreen() {
		return MarketplaceCursor.shouldKeep()
				|| Minecraft.getInstance().gui.screen() instanceof MarketplaceScreen;
	}

	@Inject(method = "grabMouse", at = @At("RETURN"))
	private void marketplace$restoreCursor(CallbackInfo info) {
		if (!marketplace$isOurScreen() || !MarketplaceCursor.hasPosition()) {
			return;
		}

		this.xpos = MarketplaceCursor.x();
		this.ypos = MarketplaceCursor.y();
		InputConstants.grabMouse(Minecraft.getInstance().getWindow(), this.xpos, this.ypos);
	}
}
