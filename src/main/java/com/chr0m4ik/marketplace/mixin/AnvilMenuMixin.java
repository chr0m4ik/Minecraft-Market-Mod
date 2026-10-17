package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.turbo.TurboUpgrade;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {

	@Shadow
	@Final
	private DataSlot cost;

	@Shadow
	private int repairItemCountCost;

	@Inject(method = "createResult", at = @At("RETURN"))
	private void marketplace$turboCombine(CallbackInfo ci) {
		AnvilMenu menu = (AnvilMenu) (Object) this;

		if (!menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty()) {
			return;
		}

		ItemStack left = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
		ItemStack right = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
		int level = TurboUpgrade.resultLevel(left, right);

		if (level == 0) {
			return;
		}

		menu.getSlot(AnvilMenu.RESULT_SLOT).set(TurboUpgrade.upgraded(left, level));

		// ванильный onTake очистил бы слот целиком.
		this.repairItemCountCost = 1;

		this.cost.set(TurboUpgrade.levelCost(level));
		menu.broadcastChanges();
	}
}
