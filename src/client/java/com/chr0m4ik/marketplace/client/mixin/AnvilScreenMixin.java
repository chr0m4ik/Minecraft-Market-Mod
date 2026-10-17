package com.chr0m4ik.marketplace.client.mixin;

import com.chr0m4ik.marketplace.turbo.TurboUpgrade;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {

	@ModifyConstant(method = "extractLabels", constant = @Constant(intValue = 40))
	private int marketplace$turboCostLabel(int original) {
		AbstractContainerMenu menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();

		if (menu instanceof AnvilMenu anvil
				&& TurboUpgrade.isTurbo(anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem())) {
			return Integer.MAX_VALUE;
		}

		return original;
	}
}
