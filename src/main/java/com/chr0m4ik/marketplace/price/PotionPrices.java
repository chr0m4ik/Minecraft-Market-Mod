package com.chr0m4ik.marketplace.price;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.Map;

/**
 * Цена зелий считается по ингредиентам варки, а не по предмету.
 *
 * <p>Все зелья — это один предмет ({@code minecraft:potion}), поэтому в прайсе у них одна цена.
 * Здесь же цена собирается по цепочке рецептов: бутылочка воды, затем каждый ингредиент по пути
 * (незер-бородавка → ингредиент эффекта → усилители), плюс топливо для варки и наценка.
 *
 * <p>Формула заказчика:
 * {@code (250 (бутылочка) + ингредиенты цепочки + огненный порошок / 8 + порох или дыхание дракона) × 1,2}.
 * Цены ингредиентов берутся из общего прайса ({@link ItemPrices}), поэтому при изменении цены сахара
 * или слезы гаста цена зелья пересчитается сама.
 *
 * <p>Зелье удачи варить нельзя (в 26.3 рецепта нет), в магазине его тоже нет — на всякий случай
 * у него есть запасная цена, чтобы оно никогда не оказалось бесплатным.
 */
public final class PotionPrices {
	/** Бутылочка воды — основа любого зелья. */
	private static final double WATER_BOTTLE = 250.0;

	/** Наценка за работу: варка, время, топка. */
	private static final double MARKUP = 1.2;

	/** Во сколько раз делим цену огненного порошка: одного порошка хватает на несколько варок. */
	private static final double FUEL_DIVISOR = 8.0;

	/** Цена зелья удачи: рецепта нет, в выживании не получить. */
	private static final double LUCK = 500_000.0;

	/** Цена зелий дурного знамения по уровням силы (0…4). */
	private static final double[] OMINOUS = {100_000.0, 150_000.0, 250_000.0, 400_000.0, 600_000.0};

	/** Рецепт: из какого зелья и каким ингредиентом получается это. */
	private record Recipe(String base, Item ingredient) {
	}

	/** Таблица варки (данные игры 26.3, рецепты проверены по jar и вики). */
	private static final Map<String, Recipe> RECIPES = Map.ofEntries(
			Map.entry("awkward", new Recipe("water", Items.NETHER_WART)),
			Map.entry("thick", new Recipe("water", Items.GLOWSTONE_DUST)),
			Map.entry("mundane", new Recipe("water", Items.STONE)),
			Map.entry("night_vision", new Recipe("awkward", Items.GOLDEN_CARROT)),
			Map.entry("long_night_vision", new Recipe("night_vision", Items.REDSTONE)),
			Map.entry("invisibility", new Recipe("night_vision", Items.FERMENTED_SPIDER_EYE)),
			Map.entry("long_invisibility", new Recipe("invisibility", Items.REDSTONE)),
			Map.entry("leaping", new Recipe("awkward", Items.RABBIT_FOOT)),
			Map.entry("long_leaping", new Recipe("leaping", Items.REDSTONE)),
			Map.entry("strong_leaping", new Recipe("leaping", Items.GLOWSTONE_DUST)),
			Map.entry("fire_resistance", new Recipe("awkward", Items.MAGMA_CREAM)),
			Map.entry("long_fire_resistance", new Recipe("fire_resistance", Items.REDSTONE)),
			Map.entry("swiftness", new Recipe("awkward", Items.SUGAR)),
			Map.entry("long_swiftness", new Recipe("swiftness", Items.REDSTONE)),
			Map.entry("strong_swiftness", new Recipe("swiftness", Items.GLOWSTONE_DUST)),
			Map.entry("slowness", new Recipe("swiftness", Items.FERMENTED_SPIDER_EYE)),
			Map.entry("long_slowness", new Recipe("long_swiftness", Items.FERMENTED_SPIDER_EYE)),
			Map.entry("strong_slowness", new Recipe("slowness", Items.GLOWSTONE_DUST)),
			Map.entry("turtle_master", new Recipe("awkward", Items.TURTLE_HELMET)),
			Map.entry("long_turtle_master", new Recipe("turtle_master", Items.REDSTONE)),
			Map.entry("strong_turtle_master", new Recipe("turtle_master", Items.GLOWSTONE_DUST)),
			Map.entry("water_breathing", new Recipe("awkward", Items.PUFFERFISH)),
			Map.entry("long_water_breathing", new Recipe("water_breathing", Items.REDSTONE)),
			Map.entry("healing", new Recipe("awkward", Items.GLISTERING_MELON_SLICE)),
			Map.entry("strong_healing", new Recipe("healing", Items.GLOWSTONE_DUST)),
			Map.entry("harming", new Recipe("poison", Items.FERMENTED_SPIDER_EYE)),
			Map.entry("strong_harming", new Recipe("harming", Items.GLOWSTONE_DUST)),
			Map.entry("poison", new Recipe("awkward", Items.SPIDER_EYE)),
			Map.entry("long_poison", new Recipe("poison", Items.REDSTONE)),
			Map.entry("strong_poison", new Recipe("poison", Items.GLOWSTONE_DUST)),
			Map.entry("regeneration", new Recipe("awkward", Items.GHAST_TEAR)),
			Map.entry("long_regeneration", new Recipe("regeneration", Items.REDSTONE)),
			Map.entry("strong_regeneration", new Recipe("regeneration", Items.GLOWSTONE_DUST)),
			Map.entry("strength", new Recipe("awkward", Items.BLAZE_POWDER)),
			Map.entry("long_strength", new Recipe("strength", Items.REDSTONE)),
			Map.entry("strong_strength", new Recipe("strength", Items.GLOWSTONE_DUST)),
			Map.entry("weakness", new Recipe("water", Items.FERMENTED_SPIDER_EYE)),
			Map.entry("long_weakness", new Recipe("weakness", Items.REDSTONE)),
			Map.entry("slow_falling", new Recipe("awkward", Items.PHANTOM_MEMBRANE)),
			Map.entry("long_slow_falling", new Recipe("slow_falling", Items.REDSTONE)),
			Map.entry("wind_charged", new Recipe("awkward", Items.BREEZE_ROD)),
			Map.entry("weaving", new Recipe("awkward", Items.COBWEB)),
			Map.entry("oozing", new Recipe("awkward", Items.SLIME_BLOCK)),
			Map.entry("infested", new Recipe("awkward", Items.STONE))
	);

	private PotionPrices() {
		// Класс только со статическими методами.
	}

	/**
	 * Цена зелья в стаке. Для зелья без содержимого («неваримое») берём цену бутылочки воды.
	 * Зелье удачи в магазине не продаётся, но цену ему всё равно даём.
	 */
	public static double priceOf(ItemStack stack) {
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		String path = contents == null ? "water" : idOf(contents);

		if (path.equals("luck")) {
			return LUCK;
		}

		boolean splash = stack.is(Items.SPLASH_POTION);
		boolean lingering = stack.is(Items.LINGERING_POTION);

		// Чистая бутылочка воды — ровно 250, топливо к ней не применяем.
		if (path.equals("water") && !splash && !lingering) {
			return WATER_BOTTLE;
		}

		double sum = WATER_BOTTLE + chain(path) + fuel();

		if (splash) {
			sum += ItemPrices.priceOf(Items.GUNPOWDER);
		}

		if (lingering) {
			sum += ItemPrices.priceOf(Items.GUNPOWDER) + ItemPrices.priceOf(Items.DRAGON_BREATH);
		}

		return sum * MARKUP;
	}

	/** Цена зелья дурного знамения по уровню силы (0…4). */
	public static double ominousPrice(int level) {
		return OMINOUS[Math.max(0, Math.min(level, OMINOUS.length - 1))];
	}

	/** Сумма цен всех ингредиентов цепочки до бутылочки воды. */
	private static double chain(String path) {
		Recipe recipe = RECIPES.get(path);

		if (recipe == null) {
			return 0.0;
		}

		return chain(recipe.base()) + ItemPrices.priceOf(recipe.ingredient());
	}

	/** Топливо: огненный порошок, поделённый на несколько варок. */
	private static double fuel() {
		return ItemPrices.priceOf(Items.BLAZE_POWDER) / FUEL_DIVISOR;
	}

	/** Идентификатор зелья в виде пути (например, {@code strong_healing}). */
	private static String idOf(PotionContents contents) {
		return contents.potion()
				.map(holder -> holder.unwrapKey().map(key -> key.identifier().getPath()).orElse("water"))
				.orElse("water");
	}

	/** Идентификатор зелья из держателя (используется для подсказок). */
	public static String idOf(Holder<Potion> potion) {
		return potion.unwrapKey().map(key -> key.identifier().getPath()).orElse("water");
	}
}
