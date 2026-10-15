package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.world.item.ItemStack;

/**
 * Один ордер на рынке: что продаём, с какой наценкой и когда продажа закончится.
 *
 * <p>Ордер живёт в сейве мира, поэтому продажа идёт и когда игрок вышел из игры.
 *
 * @param stack   что продаётся (целый стак);
 * @param markup  наценка: x1…x2;
 * @param readyAt игровое время (в тиках), когда продажа завершится.
 */
public record MarketOrder(ItemStack stack, double markup, long readyAt) {
	/** Сколько дадут за ордер. */
	public double payout() {
		return MoneyFormat.round(ItemPrices.priceOf(this.stack) * this.markup);
	}

	/** Сколько тиков осталось ждать. */
	public long ticksLeft(long now) {
		return Math.max(0L, this.readyAt - now);
	}

	/** Готов ли ордер к продаже. */
	public boolean isReady(long now) {
		return now >= this.readyAt;
	}

	public boolean isEmpty() {
		return this.stack.isEmpty();
	}
}
