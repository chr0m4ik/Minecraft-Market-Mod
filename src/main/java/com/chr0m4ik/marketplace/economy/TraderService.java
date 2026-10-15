package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Сделка с торговцем.
 *
 * <p>Торговец платит {@code цена × }{@link ItemPrices#TRADER_MULTIPLIER} — то есть 75% от базовой
 * цены. Здесь только расчёт и выдача товара, никакого интерфейса: так проще проверять.
 */
public final class TraderService {
	/** Что выставлено на продажу. */
	public record Offer(int itemCount, double fullPrice) {
		/** Сколько заплатит торговец (с уценкой). */
		public double payout() {
			return MoneyFormat.round(this.fullPrice * ItemPrices.TRADER_MULTIPLIER);
		}

		public boolean isEmpty() {
			return this.itemCount == 0;
		}
	}

	private TraderService() {
		// Класс только со статическими методами.
	}

	/** Считает выставленный товар, ничего не забирая. */
	public static Offer evaluate(Container container, int slots) {
		int count = 0;
		double price = 0.0;

		for (int i = 0; i < slots; i++) {
			ItemStack stack = container.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			count += stack.getCount();
			price += ItemPrices.priceOf(stack);
		}

		return new Offer(count, price);
	}

	/** Считает товар и забирает его (сделка состоялась). */
	public static Offer take(Container container, int slots) {
		int count = 0;
		double price = 0.0;

		for (int i = 0; i < slots; i++) {
			ItemStack stack = container.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			count += stack.getCount();
			price += ItemPrices.priceOf(stack);
			container.setItem(i, ItemStack.EMPTY);
		}

		return new Offer(count, price);
	}
}
