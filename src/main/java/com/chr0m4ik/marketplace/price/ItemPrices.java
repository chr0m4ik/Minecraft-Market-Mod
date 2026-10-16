package com.chr0m4ik.marketplace.price;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.event.EventService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * <p>Правила торговли, из-за которых перепродажа не приносит денег (числа лежат в
 * {@code data/marketplace/economy.json}, читает их
 * {@link com.chr0m4ik.marketplace.economy.EconomyConfig}):
 * <ul>
 *     <li>купить у рынка — {@code buy_multiplier} от цены (дорого);</li>
 *     <li>продать на рынке — от {@code price} до {@code price × max(наценки)} в зависимости от наценки;</li>
 *     <li>сдать торговцу — {@code trader_multiplier} от цены.</li>
 * </ul>
 * Покупка дороже самой выгодной продажи даже с учётом микро-дрейфа, поэтому «купить — продать»
 * всегда в убыток. Это же условие проверяется при старте мода.
 *
 * <p>К базовой цене стака добавляется микро-дрейф — фон ±3% ({@link PriceDrift}) — и множитель
 * крупного ивента, если предмет под него попал ({@code event/EventService}). Оба встроены именно
 * здесь, поэтому работают одинаково везде: покупка в магазине, продажа торговцу, рынок, подсказки
 * предметов, HUD и стоимость наборов. Ивент симметричен (опускает или поднимает и покупку, и
 * продажу), поэтому «купить — продать» наживы не даёт: это проверяется при старте мода.
 *
 * <p><b>Что именно «дышит».</b> Множитель дрейфа берётся у предмета самого стака, то есть
 * {@code базовая цена предмета × множитель}. Зачарования дрейфу не подвержены. Содержимое
 * контейнеров считается отдельными предметами ({@link #contentsPrice}), поэтому каждый предмет
 * внутри шлякера, бочки или мешка дрейфует своим собственным множителем — цена контейнера
 * целиком отдельно не дрейфует. Цена зелья собирается по ингредиентам ({@link PotionPrices})
 * и дрейфует как сам предмет-бутылочка.
 */
public final class ItemPrices {
	/** Прайс внутри мода. Читаем его прямо из своего jar-файла, поэтому цены одинаковы на клиенте и на сервере. */
	private static final String FILE_PATH = "/data/marketplace/prices.json";

	/** Цена предмета, которого нет в прайсе: продать можно за копейку, купить нельзя. */
	public static final double UNKNOWN_PRICE = 1.0;

	/** Насколько глубоко разворачиваем содержимое контейнеров (защита от зацикливания). */
	private static final int MAX_CONTENT_DEPTH = 8;

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

	/**
	 * Базовая цена предмета за 1 штуку без микро-дрейфа. Дрейф встроен в
	 * {@link #priceOf(ItemStack)} — там множитель уже учтён.
	 */
	public static double priceOf(Item item) {
		ensureLoaded();
		return prices.getOrDefault(item, UNKNOWN_PRICE);
	}

	/** Базовая цена всего стака, включая зачарования и содержимое контейнеров. */
	public static double priceOf(ItemStack stack) {
		return priceOf(stack, 0);
	}

	private static double priceOf(ItemStack stack, int depth) {
		if (stack.isEmpty() || depth > MAX_CONTENT_DEPTH) {
			return 0.0;
		}

		ensureLoaded();
		// Базовая цена «дышит» (микро-дрейф) и попадает под крупный ивент: оба множителя берутся
		// у предмета самого стака. Зачарования добавляются ниже без множителей, а содержимое
		// контейнеров считается своими предметами — у каждого из них собственные множители.
		double price = basePriceOf(stack) * PriceDrift.multiplier(stack.getItem())
				* EventService.multiplier(stack.getItem()) * stack.getCount();

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

		// Содержимое шалкеров, бочек и мешков тоже стоит денег.
		price += contentsPrice(stack, depth);

		return price;
	}

	/**
	 * Цена одной штуки. У зелий она считается по ингредиентам варки, у зелья дурного знамения —
	 * по уровню силы, у всех остальных предметов берётся из прайса.
	 */
	private static double basePriceOf(ItemStack stack) {
		if (stack.has(DataComponents.POTION_CONTENTS)) {
			return PotionPrices.priceOf(stack);
		}

		if (stack.is(Items.OMINOUS_BOTTLE)) {
			OminousBottleAmplifier amplifier = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
			return PotionPrices.ominousPrice(amplifier == null ? 0 : amplifier.value());
		}

		return prices.getOrDefault(stack.getItem(), UNKNOWN_PRICE);
	}

	/** Стоимость того, что лежит внутри предмета (шалкер, мешок). */
	private static double contentsPrice(ItemStack stack, int depth) {
		double total = 0.0;

		ItemContainerContents container = stack.get(DataComponents.CONTAINER);

		if (container != null) {
			for (ItemStack inside : container.itemCopies().toList()) {
				total += priceOf(inside, depth + 1);
			}
		}

		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);

		if (bundle != null) {
			for (ItemStack inside : bundle.itemCopies().toList()) {
				total += priceOf(inside, depth + 1);
			}
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

	/**
	 * Все предметы, которые есть в прайсе и которые можно купить, в порядке реестра.
	 * Нужно ивентам: из этого списка выбираются цели ({@code event/EventService}).
	 * Список собирается по требованию — ивент выбирается редко, кэш тут не нужен.
	 */
	public static List<Item> buyableItems() {
		ensureLoaded();
		List<Item> result = new ArrayList<>();

		for (Item item : BuiltInRegistries.ITEM) {
			if (prices.containsKey(item) && !notBuyable.contains(item)) {
				result.add(item);
			}
		}

		return List.copyOf(result);
	}

	/** Сколько предметов в прайсе — для проверок и логов. */
	public static int size() {
		ensureLoaded();
		return prices.size();
	}
}
