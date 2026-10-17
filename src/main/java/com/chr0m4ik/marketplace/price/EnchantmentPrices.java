package com.chr0m4ik.marketplace.price;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class EnchantmentPrices {

	private static final double BASE_VALUE = 400_000.0;

	private static final double COMMON_WEIGHT = 10.0;

	private static final double COMPLEXITY_STEP = 0.25;

	private static final double LEVEL_STEP = 2.2;

	private static final double BUNDLE_STEP = 0.10;
	private static final double BUNDLE_FLOOR = 0.55;

	private static final double JUNK_CAP = 1_500_000.0;

	private static final double JUNK_FACTOR_MAX = 0.15;

	private static final List<Map.Entry<String, Double>> MATERIAL_FACTORS = List.of(
			Map.entry("netherite", 0.95),
			Map.entry("diamond", 0.60),
			Map.entry("iron", 0.12),
			Map.entry("chainmail", 0.03),
			Map.entry("copper", 0.03),
			Map.entry("golden", 0.02),
			Map.entry("stone", 0.015),
			Map.entry("wooden", 0.01),
			Map.entry("leather", 0.01));

	private static final Map<String, Double> ITEM_FACTORS = Map.ofEntries(

			Map.entry("minecraft:turtle_helmet", 0.12),

			Map.entry("minecraft:wolf_armor", 0.15),

			Map.entry("minecraft:bow", 0.30),
			Map.entry("minecraft:crossbow", 0.30),
			Map.entry("minecraft:shield", 0.30),
			Map.entry("minecraft:fishing_rod", 0.30),
			Map.entry("minecraft:carrot_on_a_stick", 0.30),
			Map.entry("minecraft:warped_fungus_on_a_stick", 0.30),

			Map.entry("minecraft:trident", 1.0),
			Map.entry("minecraft:mace", 1.0),
			Map.entry("minecraft:elytra", 1.0));

	private static final double DEFAULT_FACTOR = 0.10;

	private static final double BOOK_FACTOR = 1.0;

	private static final Map<String, Double> SPECIAL = Map.of(
			"minecraft:binding_curse", 100_000.0,
			"minecraft:vanishing_curse", 250_000.0,

			"minecraft:bane_of_arthropods", 150_000.0,
			"minecraft:thorns", 300_000.0,
			"minecraft:channeling", 300_000.0,
			"minecraft:aqua_affinity", 150_000.0
	);

	private static final Set<String> TREASURE = Set.of(
			"minecraft:mending",
			"minecraft:frost_walker",
			"minecraft:soul_speed",
			"minecraft:swift_sneak",
			"minecraft:wind_burst"
	);

	private static final double TREASURE_MULTIPLIER = 2.0;

	private static final Map<Item, Double> FACTOR_CACHE = new HashMap<>();

	private EnchantmentPrices() {
		// Класс только со статическими методами.
	}

	public static double valueOf(ItemEnchantments enchantments) {
		return sumOf(enchantments) * complexity(enchantments.size());
	}

	public static double valueOnItem(ItemStack stack, ItemEnchantments enchantments) {
		if (enchantments.isEmpty()) {
			return 0.0;
		}

		double books = sumOf(enchantments);
		double factor = tierFactor(stack);
		double value = books * factor * bundleFactor(enchantments.size());

		if (factor <= JUNK_FACTOR_MAX) {
			value = Math.min(value, JUNK_CAP);
		}

		return Math.min(value, books);
	}

	private static double sumOf(ItemEnchantments enchantments) {
		double sum = 0.0;

		for (Holder<Enchantment> holder : enchantments.keySet()) {
			sum += valueOf(holder, enchantments.getLevel(holder));
		}

		return sum;
	}

	public static double tierFactor(ItemStack stack) {
		if (stack.isEmpty()) {
			return DEFAULT_FACTOR;
		}

		return FACTOR_CACHE.computeIfAbsent(stack.getItem(), EnchantmentPrices::computeTierFactor);
	}

	private static double computeTierFactor(Item item) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);

		if (id.getPath().equals("enchanted_book")) {
			return BOOK_FACTOR;
		}

		Double exact = ITEM_FACTORS.get(id.toString());

		if (exact != null) {
			return exact;
		}

		String path = id.getPath();

		for (Map.Entry<String, Double> entry : MATERIAL_FACTORS) {
			if (path.startsWith(entry.getKey() + "_")) {
				return entry.getValue();
			}
		}

		return DEFAULT_FACTOR;
	}

	private static double bundleFactor(int count) {
		return Math.max(BUNDLE_FLOOR, 1.0 - BUNDLE_STEP * (count - 1));
	}

	public static double valueOf(Holder<Enchantment> holder, int level) {
		String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
		Double special = SPECIAL.get(id);

		if (special != null) {
			return special;
		}

		double price = valueOf(holder.value(), level);
		return TREASURE.contains(id) ? price * TREASURE_MULTIPLIER : price;
	}

	public static double valueOf(Enchantment enchantment, int level) {
		int weight = Math.max(1, enchantment.getWeight());
		return BASE_VALUE * (COMMON_WEIGHT / weight) * Math.pow(LEVEL_STEP, Math.max(0, level - 1));
	}

	private static double complexity(int count) {
		return 1.0 + (count - 1) * COMPLEXITY_STEP;
	}
}
