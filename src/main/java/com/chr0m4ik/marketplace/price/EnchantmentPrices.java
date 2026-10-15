package com.chr0m4ik.marketplace.price;

import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Сколько добавляют к цене зачарования.
 *
 * <p>Логика простая и не требует таблиц: у каждого зачарования в игре есть «вес»
 * ({@code weight}) — чем он меньше, тем реже зачарование встречается. Цена считается как
 * {@code базовая_цена / вес × уровень}. Поэтому «Острота I» стоит копейки, а «Починка»
 * или «Шёлковое касание» (вес 1) — в десять раз дороже.
 *
 * <p>Сверху добавляется наценка за сложность: предмет с одним зачарованием почти не дорожает,
 * а с пятью-восемью — заметно (см. {@link #complexity}).
 */
public final class EnchantmentPrices {
	/** Цена самого обычного зачарования (вес 10) первого уровня. */
	private static final double BASE_VALUE = 400_000.0;

	/** Самый большой вес зачарования в игре — считаем его «обычным». */
	private static final double COMMON_WEIGHT = 10.0;

	/** Насколько дороже становится предмет за каждое следующее зачарование. */
	private static final double COMPLEXITY_STEP = 0.25;

	private EnchantmentPrices() {
		// Класс только со статическими методами.
	}

	/** Цена всех зачарований сразу (без цены самого предмета). */
	public static double valueOf(ItemEnchantments enchantments) {
		if (enchantments.isEmpty()) {
			return 0.0;
		}

		double sum = 0.0;

		for (Holder<Enchantment> holder : enchantments.keySet()) {
			sum += valueOf(holder.value(), enchantments.getLevel(holder));
		}

		return sum * complexity(enchantments.size());
	}

	/** Цена одного зачарования на конкретном уровне. */
	public static double valueOf(Enchantment enchantment, int level) {
		int weight = Math.max(1, enchantment.getWeight());
		return BASE_VALUE * (COMMON_WEIGHT / weight) * level;
	}

	/**
	 * Наценка за сложность: одно зачарование — 1.0, два — 1.25, пять — 2.0, восемь — 2.75.
	 * Так собранный «идеальный» предмет стоит заметно дороже суммы своих книг.
	 */
	private static double complexity(int count) {
		return 1.0 + (count - 1) * COMPLEXITY_STEP;
	}
}
