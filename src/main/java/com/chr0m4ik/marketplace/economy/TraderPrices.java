package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Цены торговца — его личная особенность.
 *
 * <p>Правила (заданы заказчиком):
 * <ul>
 *     <li>то, что у торговца много покупали, он берёт дешевле: {@value #DISCOUNT_PER_STACK} базовой
 *     цены за каждый проданный стак этого предмета, но выручка не может упасть ниже
 *     {@code 1 - }{@value #MAX_DISCOUNT} от обычной;</li>
 *     <li>за активную торговлю он доплачивает за остальные предметы: {@value #BONUS_PER_STACK}
 *     за каждый проданный стак, максимум {@value #MAX_BONUS};</li>
 *     <li>цены восстанавливаются линейно за один игровой день.</li>
 * </ul>
 * Пример: яблоко 1000$. Продали 10 стаков яблок — торговец даёт за яблоко 700 вместо 750,
 * а за алмазы доплачивает 1%.
 *
 * <p><b>Важно:</b> это касается только торговца. Рынок (продажа слотами) и покупка предметов
 * всегда считаются от базовой цены — так решил заказчик.
 *
 * <p>Класс умеет работать и на клиенте: сервер присылает список проданного, а цены считаются
 * по тем же формулам, поэтому подсказки совпадают с тем, что заплатит сервер.
 */
public final class TraderPrices {
	/** Сколько базовой цены «съедает» каждый проданный стак предмета. */
	public static final double DISCOUNT_PER_STACK = 0.005;

	/** Предел уценки: торговец платит не меньше 80% обычной цены. */
	public static final double MAX_DISCOUNT = 0.20;

	/** Насколько торговец доплачивает за остальные предметы за каждый проданный стак. */
	public static final double BONUS_PER_STACK = 0.001;

	/** Предел надбавки. */
	public static final double MAX_BONUS = 0.10;

	private static final TraderPrices EMPTY = new TraderPrices(Map.of(), 0.0);

	private final Map<Item, Double> sold;
	private final double totalSold;

	private TraderPrices(Map<Item, Double> sold, double totalSold) {
		this.sold = sold;
		this.totalSold = totalSold;
	}

	/** Торговец ещё ничего не покупал: цены равны базовым. */
	public static TraderPrices empty() {
		return EMPTY;
	}

	/** Строит цены по таблице «предмет → сколько стаков продано». */
	public static TraderPrices of(Map<Item, Double> sold) {
		if (sold.isEmpty()) {
			return EMPTY;
		}

		double total = 0.0;

		for (double stacks : sold.values()) {
			total += stacks;
		}

		return new TraderPrices(Map.copyOf(sold), total);
	}

	/** Сколько стаков предмета уже продали торговцу. */
	public double soldStacks(Item item) {
		return sold.getOrDefault(item, 0.0);
	}

	/** Насколько торговец доплачивает за этот предмет из-за активности. */
	public double bonus(Item item) {
		double others = Math.max(0.0, this.totalSold - soldStacks(item));
		return Math.min(MAX_BONUS, BONUS_PER_STACK * others);
	}

	/** Цена предмета у торговца (без уценки за перенасыщение). */
	public double price(Item item) {
		return ItemPrices.priceOf(item) * (1.0 + bonus(item));
	}

	/**
	 * Цена стака у торговца. Надбавка берётся только с самих предметов: зачарования
	 * и содержимое контейнеров дорожают вместе с торговцем, но своей надбавки не получают.
	 */
	public double price(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double base = ItemPrices.priceOf(stack);
		double itemsOnly = ItemPrices.priceOf(stack.getItem()) * stack.getCount();
		return base + itemsOnly * bonus(stack.getItem());
	}

	/** Сколько торговец заплатит за этот стак — уже с уценкой и с его долей (75%). */
	public double payout(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double normal = price(stack) * ItemPrices.TRADER_MULTIPLIER;
		double perItem = ItemPrices.priceOf(stack.getItem()) * DISCOUNT_PER_STACK * soldStacks(stack.getItem());
		double discount = Math.min(perItem * stack.getCount(), normal * MAX_DISCOUNT);
		return Math.max(0.0, normal - discount);
	}

	/** Покупал ли торговец что-нибудь вообще. */
	public boolean isEmpty() {
		return sold.isEmpty();
	}

	/** Таблица продаж — нужна, чтобы отправить её на клиент. */
	public Map<Item, Double> soldMap() {
		return sold;
	}
}
