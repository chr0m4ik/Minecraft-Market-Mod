package com.chr0m4ik.marketplace.price;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Прайс-лист мода: сколько стоит каждый предмет.
 *
 * <p>Цены лежат в файле {@code data/marketplace/prices.json} внутри мода. Файл создаётся
 * генератором {@code tools/generate-prices.js} (см. AGENTS.md) и правится руками.
 *
 * <p>Правила торговли, из-за которых перепродажа не приносит денег:
 * <ul>
 *     <li>купить у рынка — {@link #BUY_MULTIPLIER} от цены (дорого);</li>
 *     <li>продать на рынке — от {@code price} до {@code price * MAX_MARKET_MARKUP} в зависимости от наценки;</li>
 *     <li>сдать торговцу — {@link #TRADER_MULTIPLIER} от цены.</li>
 * </ul>
 * Так как покупка дороже самой выгодной продажи, «купить — продать» всегда в убыток.
 */
public final class ItemPrices {
	/** Файл с прайсом внутри мода. */
	private static final Identifier FILE = MarketplaceMod.id("prices.json");

	/** Цена предмета, которого нет в прайсе: продать можно за копейку, купить нельзя. */
	public static final double UNKNOWN_PRICE = 1.0;

	/** Во сколько раз покупка дороже базовой цены. Должно быть больше {@link #MAX_MARKET_MARKUP}. */
	public static final double BUY_MULTIPLIER = 2.5;

	/** Сколько от базовой цены даёт торговец (мгновенная продажа). */
	public static final double TRADER_MULTIPLIER = 0.75;

	/** Самая большая наценка на рынке (модификатор x2). */
	public static final double MAX_MARKET_MARKUP = 2.0;

	private static final Codec<Map<String, Double>> PRICES_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);
	private static final Codec<List<String>> NAMES_CODEC = Codec.STRING.listOf();

	private static final Map<Item, Double> PRICES = new HashMap<>();
	private static final Set<Item> NOT_BUYABLE = new HashSet<>();
	private static boolean loaded;

	private ItemPrices() {
		// Класс только со статическими методами.
	}

	/** Вызывается при старте сервера: читает прайс из ресурсов мода. */
	public static void load(MinecraftServer server) {
		PRICES.clear();
		NOT_BUYABLE.clear();
		loaded = false;

		List<Resource> stack = server.getResourceManager().getResourceStack(FILE);

		if (stack.isEmpty()) {
			MarketplaceMod.LOGGER.error("Не найден прайс-лист {} — цены не загружены, покупать нельзя ничего", FILE);
			return;
		}

		// Последний в списке — ресурс с самым высоким приоритетом (наш мод).
		Resource resource = stack.getLast();

		try (Reader reader = resource.openAsReader()) {
			JsonElement root = JsonParser.parseReader(reader);
			JsonObject object = root.getAsJsonObject();

			Map<String, Double> prices = PRICES_CODEC.parse(JsonOps.INSTANCE, object.get("prices")).getOrThrow();
			List<String> notBuyable = NAMES_CODEC.parse(JsonOps.INSTANCE, object.get("notBuyable")).getOrThrow();

			prices.forEach(ItemPrices::register);
			notBuyable.forEach(ItemPrices::registerNotBuyable);
			loaded = true;

			MarketplaceMod.LOGGER.info("Прайс-лист загружен: {} предметов, {} нельзя купить", PRICES.size(), NOT_BUYABLE.size());
		} catch (IOException | RuntimeException exception) {
			// Не прячем проблему: пишем понятную ошибку и оставляем прайс пустым.
			MarketplaceMod.LOGGER.error("Не удалось прочитать прайс-лист {}", FILE, exception);
		}
	}

	private static void register(String id, double price) {
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

		if (item.isEmpty()) {
			MarketplaceMod.LOGGER.warn("В прайсе есть неизвестный предмет, запись пропущена: {}", id);
			return;
		}

		PRICES.put(item.get(), price);
	}

	private static void registerNotBuyable(String id) {
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

		if (item.isEmpty()) {
			MarketplaceMod.LOGGER.warn("В списке «нельзя купить» неизвестный предмет, запись пропущена: {}", id);
			return;
		}

		NOT_BUYABLE.add(item.get());
	}

	/** Базовая цена предмета (за 1 штуку). */
	public static double priceOf(Item item) {
		return PRICES.getOrDefault(item, UNKNOWN_PRICE);
	}

	/** Базовая цена всего стака, включая зачарования. */
	public static double priceOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double price = priceOf(stack.getItem()) * stack.getCount();

		// Зачарования на самом предмете.
		ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);

		if (enchantments != null) {
			price += EnchantmentPrices.valueOf(enchantments);
		}

		// Зачарования внутри книги.
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (stored != null) {
			price += EnchantmentPrices.valueOf(stored);
		}

		return price;
	}

	/** Сколько стоит всё, что лежит в инвентаре игрока (по цене продажи за 1x). */
	public static double priceOfInventory(Inventory inventory) {
		double total = 0.0;

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			total += priceOf(inventory.getItem(slot));
		}

		return total;
	}

	/** Есть ли предмет в прайсе. */
	public static boolean isPriced(Item item) {
		return PRICES.containsKey(item);
	}

	/** Можно ли купить предмет у рынка. */
	public static boolean isBuyable(Item item) {
		return loaded && PRICES.containsKey(item) && !NOT_BUYABLE.contains(item);
	}

	/** Загружен ли прайс (если нет — цены считаются неизвестными). */
	public static boolean isLoaded() {
		return loaded;
	}

	/** Сколько предметов в прайсе — для проверок и логов. */
	public static int size() {
		return PRICES.size();
	}
}
