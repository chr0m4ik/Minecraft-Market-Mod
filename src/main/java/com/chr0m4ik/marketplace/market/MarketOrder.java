package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.economy.MoneyFormat;
import net.minecraft.world.item.ItemStack;

public record MarketOrder(ItemStack stack, double price, double markup, long readyAt, boolean auto, String seller) {

	public double payout() {
		return MoneyFormat.round(this.price);
	}

	public boolean isReady(long now) {
		return this.auto && now >= this.readyAt;
	}

	public boolean waitingForBuyer() {
		return !this.auto;
	}

	public long ticksLeft(long now) {
		return this.auto ? Math.max(0L, this.readyAt - now) : 0L;
	}

	public boolean isEmpty() {
		return this.stack.isEmpty();
	}
}
