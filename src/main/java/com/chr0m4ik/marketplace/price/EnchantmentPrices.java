package com.chr0m4ik.marketplace.price;

import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;
import java.util.Set;

/**
 * Сколько добавляют к цене зачарования.
 *
 * <p>Логика простая и не требует таблиц: у каждого зачарования в игре есть «вес»
 * ({@code weight}) — чем он меньше, тем реже зачарование встречается. Цена считается как
 * {@code базовая_цена / вес × 2,2^(уровень−1)}. Поэтому «Острота I» стоит копейки, а «Починка»
 * (вес 2) — в пять раз дороже, «Шёлковое касание» (вес 1) — в десять.
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

	/**
	 * Во сколько раз дороже каждый следующий уровень зачарования.
	 * В наковальне книга следующего уровня получается слиянием двух книг предыдущего, поэтому
	 * рост двойной, плюс 10% за потраченный опыт и износ наковальни: 2 × 1,1 = 2,2.
	 */
	private static final double LEVEL_STEP = 2.2;

	/**
	 * Особые цены. Проклятия по весу выходят самыми дорогими, хотя только мешают, а некоторые
	 * зачарования наоборот почти бесполезны и столько стоить не должны. Id проверены по данным игры.
	 */
	private static final Map<String, Double> SPECIAL = Map.of(
			"minecraft:binding_curse", 100_000.0,
			"minecraft:vanishing_curse", 250_000.0,
			// «Мусорные» зачарования: по весу дорогие, по делу — почти не нужны.
			"minecraft:bane_of_arthropods", 150_000.0,
			"minecraft:thorns", 300_000.0,
			"minecraft:channeling", 300_000.0,
			"minecraft:aqua_affinity", 150_000.0
	);

	/**
	 * Сокровища: их нельзя получить из стола зачаровывания, только из лута, поэтому цена вдвое выше.
	 * Список взят из тега игры {@code minecraft:treasure}; проклятия сюда не входят — у них своя цена.
	 */
	private static final Set<String> TREASURE = Set.of(
			"minecraft:mending",
			"minecraft:frost_walker",
			"minecraft:soul_speed",
			"minecraft:swift_sneak",
			"minecraft:wind_burst"
	);

	/** Во сколько раз дороже сокровища. */
	private static final double TREASURE_MULTIPLIER = 2.0;

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
			sum += valueOf(holder, enchantments.getLevel(holder));
		}

		return sum * complexity(enchantments.size());
	}

	/** Цена одного зачарования на конкретном уровне. */
	public static double valueOf(Holder<Enchantment> holder, int level) {
		String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
		Double special = SPECIAL.get(id);

		if (special != null) {
			return special;
		}

		double price = valueOf(holder.value(), level);
		return TREASURE.contains(id) ? price * TREASURE_MULTIPLIER : price;
	}

	/** Цена по весу зачарования (для зачарований без особой цены). */
	public static double valueOf(Enchantment enchantment, int level) {
		int weight = Math.max(1, enchantment.getWeight());
		return BASE_VALUE * (COMMON_WEIGHT / weight) * Math.pow(LEVEL_STEP, Math.max(0, level - 1));
	}

	/**
	 * Наценка за сложность: одно зачарование — 1.0, два — 1.25, пять — 2.0, восемь — 2.75.
	 * Так собранный «идеальный» предмет стоит заметно дороже суммы своих книг.
	 */
	private static double complexity(int count) {
		return 1.0 + (count - 1) * COMPLEXITY_STEP;
	}
}
