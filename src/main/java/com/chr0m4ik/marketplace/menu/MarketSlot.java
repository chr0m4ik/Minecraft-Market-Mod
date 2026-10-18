package com.chr0m4ik.marketplace.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class MarketSlot extends Slot {
	private final MarketplaceMenu menu;

	public MarketSlot(MarketplaceMenu menu, Container container, int index, int x, int y) {
		super(container, index, x, y);
		this.menu = menu;
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return this.menu.isCellUnlocked(this.getContainerSlot()) && !this.menu.isCellOrder(this.getContainerSlot());
	}

	@Override
	public boolean mayPickup(Player player) {
		return this.menu.isCellUnlocked(this.getContainerSlot()) && !this.menu.isCellOrder(this.getContainerSlot());
	}

	@Override
	public void set(ItemStack stack) {
		// Ячейка ордера — копия сейва: клики (перетаскивание, shift, хотбар) не должны её менять.
		if (this.menu.isCellOrder(this.getContainerSlot())) {
			return;
		}

		super.set(stack);
	}
}
