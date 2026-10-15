package com.chr0m4ik.marketplace.price;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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
	/** Прайс внутри мода. Читаем его прямо из своего jar-файла, поэтому цены одинаковы на клиенте и на сервере. */
	private static final String FILE_PATH = "/data/marketplace/prices.json";

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

	private static Map<Item, Double> prices = Map.of();
	private static Set<Item> notBuyable = Set.of();
	/** Пытались ли уже загрузить прайс (чтобы не пробовать повторно при ошибке). */
	private static boolean loadAttempted;
	private static boolean loaded;

	private ItemPrices() {
		// Класс только со статическими методами.
	}

	/**
	 * Читает прайс-лист из ресурсов мода. Вызывается сама при первом обращении —
	 * и на сервере, и на клиенте (клиенту цены нужны для подсказок предметов).
	 */
	public static synchronized void ensureLoaded() {
		if (loadAttempted) {
			return;
		}

		loadAttempted = true;

		Map<Item, Double> loadedPrices = new HashMap<>();
		Set<Item> loadedNotBuyable = new HashSet<>();

		try (InputStream stream = ItemPrices.class.getResourceAsStream(FILE_PATH)) {
			if (stream == null) {
				MarketplaceMod.LOGGER.error("Не найден прайс-лист {} — цены неизвестны, покупать нельзя ничего", FILE_PATH);
				return;
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();

				PRICES_CODEC.parse(JsonOps.INSTANCE, object.get("prices")).getOrThrow()
						.forEach((id, price) -> resolveItem(id).ifPresent(item -> loadedPrices.put(item, price)));

				NAMES_CODEC.parse(JsonOps.INSTANCE, object.get("notBuyable")).getOrThrow()
						.forEach(id -> resolveItem(id).ifPresent(loadedNotBuyable::add));
			}

			prices = Map.copyOf(loadedPrices);
			notBuyable = Set.copyOf(loadedNotBuyable);
			loaded = true;

			MarketplaceMod.LOGGER.info("Прайс-лист загружен: {} предметов, {} нельзя купить", prices.size(), notBuyable.size());
		} catch (IOException | RuntimeException exception) {
			// Не прячем проблему: пишем понятную ошибку и оставляем прайс пустым.
			MarketplaceMod.LOGGER.error("Не удалось прочитать прайс-лист {}", FILE_PATH, exception);
		}
	}

	/** Находит предмет по его id. Неизвестные id пропускаем с предупреждением. */
	private static Optional<Item> resolveItem(String id) {
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

		if (item.isEmpty()) {
			MarketplaceMod.LOGGER.warn("В прайсе есть неизвестный предмет, запись пропущена: {}", id);
		}

		return item;
	}

	/** Базовая цена предмета (за 1 штуку). */
	public static double priceOf(Item item) {
		ensureLoaded();
		return prices.getOrDefault(item, UNKNOWN_PRICE);
	}

	/** Базовая цена всего стака, включая зачарования. */
	public static double priceOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		ensureLoaded();
		double price = prices.getOrDefault(stack.getItem(), UNKNOWN_PRICE) * stack.getCount();

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
		ensureLoaded();
		double total = 0.0;

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			total += priceOf(inventory.getItem(slot));
		}

		return total;
	}

	/** Есть ли предмет в прайсе. */
	public static boolean isPriced(Item item) {
		ensureLoaded();
		return prices.containsKey(item);
	}

	/** Можно ли купить предмет у рынка. */
	public static boolean isBuyable(Item item) {
		ensureLoaded();
		return loaded && prices.containsKey(item) && !notBuyable.contains(item);
	}

	/** Загружен ли прайс (если нет — цены считаются неизвестными). */
	public static boolean isLoaded() {
		ensureLoaded();
		return loaded;
	}

	/** Сколько предметов в прайсе — для проверок и логов. */
	public static int size() {
		ensureLoaded();
		return prices.size();
	}
}
