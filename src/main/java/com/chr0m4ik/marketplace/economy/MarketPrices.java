package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Состояние рынка: сколько чего продано и как это двигает цены.
 *
 * <p>Правила (заданы заказчиком):
 * <ul>
 *     <li>то, что продают, дешевеет: {@value #DISCOUNT_PER_STACK} базовой цены за каждый проданный
 *     стак этого предмета, но выручка не может упасть ниже {@code 1 - }{@value #MAX_DISCOUNT}
 *     от обычной;</li>
 *     <li>остальные предметы дорожают: {@value #BONUS_PER_STACK} за каждый проданный стак
 *     (любого предмета, кроме этого), максимум {@value #MAX_BONUS}.</li>
 * </ul>
 * Пример: яблоко 1000$. Продали 10 стаков яблок — торговец даёт за яблоко 700 вместо 750,
 * а алмазы дорожают на 1% (1010 вместо 1000). После 100 стаков яблоки упираются в предел
 * (600), алмазы — в +10% (1100).
 *
 * <p>Класс умеет работать и на клиенте: сервер присылает ему список проданного,
 * а цены считаются по тем же формулам.
 */
public final class MarketPrices {
	/** Сколько базовой цены «съедает» каждый проданный стак предмета. */
	public static final double DISCOUNT_PER_STACK = 0.005;

	/** Предел скидки: выручка не ниже 80% обычной. */
	public static final double MAX_DISCOUNT = 0.20;

	/** Насколько дорожают остальные предметы за каждый проданный стак. */
	public static final double BONUS_PER_STACK = 0.001;

	/** Предел надбавки. */
	public static final double MAX_BONUS = 0.10;

	private static final MarketPrices EMPTY = new MarketPrices(Map.of(), 0.0);

	private final Map<Item, Double> sold;
	private final double totalSold;

	private MarketPrices(Map<Item, Double> sold, double totalSold) {
		this.sold = sold;
		this.totalSold = totalSold;
	}

	/** Рынок без продаж: цены равны базовым. */
	public static MarketPrices empty() {
		return EMPTY;
	}

	/** Строит состояние рынка по таблице «предмет → сколько стаков продано». */
	public static MarketPrices of(Map<Item, Double> sold) {
		if (sold.isEmpty()) {
			return EMPTY;
		}

		double total = 0.0;

		for (double stacks : sold.values()) {
			total += stacks;
		}

		return new MarketPrices(Map.copyOf(sold), total);
	}

	/** Сколько стаков предмета продано. */
	public double soldStacks(Item item) {
		return sold.getOrDefault(item, 0.0);
	}

	/** Насколько дороже стали остальные предметы из-за активности рынка. */
	public double bonus(Item item) {
		double others = Math.max(0.0, this.totalSold - soldStacks(item));
		return Math.min(MAX_BONUS, BONUS_PER_STACK * others);
	}

	/** Текущая цена предмета: базовая плюс надбавка за активный рынок. */
	public double marketPrice(Item item) {
		return ItemPrices.priceOf(item) * (1.0 + bonus(item));
	}

	/**
	 * Текущая цена стака. Надбавка берётся только с самих предметов: зачарования
	 * и содержимое контейнеров дорожают вместе с рынком, но своей надбавки не получают.
	 */
	public double marketPrice(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double base = ItemPrices.priceOf(stack);
		double itemsOnly = ItemPrices.priceOf(stack.getItem()) * stack.getCount();
		return base + itemsOnly * bonus(stack.getItem());
	}

	/**
	 * Сколько дадут за стак.
	 *
	 * @param multiplier 0.75 у торговца, 1…2 у рынка (в зависимости от наценки).
	 */
	public double saleProceeds(ItemStack stack, double multiplier) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double normal = marketPrice(stack) * multiplier;
		double discountPerItem = ItemPrices.priceOf(stack.getItem()) * DISCOUNT_PER_STACK * soldStacks(stack.getItem());
		double discount = Math.min(discountPerItem * stack.getCount(), normal * MAX_DISCOUNT);
		return Math.max(0.0, normal - discount);
	}

	/** Есть ли на рынке вообще продажи. */
	public boolean isEmpty() {
		return sold.isEmpty();
	}

	/** Таблица продаж — нужна, чтобы отправить её на клиент. */
	public Map<Item, Double> soldMap() {
		return sold;
	}
}
