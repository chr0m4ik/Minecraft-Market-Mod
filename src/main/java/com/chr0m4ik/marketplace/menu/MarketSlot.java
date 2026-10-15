package com.chr0m4ik.marketplace.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Ячейка рынка: обычный слот, но если ячейку ещё не купили — в неё нельзя ничего положить
 * и из неё нельзя ничего взять (там лежит красная панель).
 */
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
}
