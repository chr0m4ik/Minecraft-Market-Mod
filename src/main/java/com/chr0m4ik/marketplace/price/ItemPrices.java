package com.chr0m4ik.marketplace.price;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.event.EventService;
import com.chr0m4ik.marketplace.market.SpecialCatalog;
import com.chr0m4ik.marketplace.turbo.TurboUpgrade;
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

public final class ItemPrices {

	private static final String FILE_PATH = "/data/marketplace/prices.json";

	public static final double UNKNOWN_PRICE = 1.0;

	private static final int MAX_CONTENT_DEPTH = 8;

	private static final Codec<Map<String, Double>> PRICES_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);
	private static final Codec<List<String>> NAMES_CODEC = Codec.STRING.listOf();

	private static Map<Item, Double> prices = Map.of();
	private static Set<Item> notBuyable = Set.of();

	private static boolean loadAttempted;
	private static boolean loaded;

	private ItemPrices() {
		// Класс только со статическими методами.
	}

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

			// MarketplaceMod.LOGGER.info("Прайс-лист загружен: {} предметов, {} нельзя купить", prices.size(), notBuyable.size());
		} catch (IOException | RuntimeException exception) {

			MarketplaceMod.LOGGER.error("Не удалось прочитать прайс-лист {}", FILE_PATH, exception);
		}
	}

	private static Optional<Item> resolveItem(String id) {
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

		if (item.isEmpty()) {
			// MarketplaceMod.LOGGER.warn("В прайсе есть неизвестный предмет, запись пропущена: {}", id);
		}

		return item;
	}

	public static double priceOf(Item item) {
		ensureLoaded();
		return prices.getOrDefault(item, UNKNOWN_PRICE);
	}

	public static double priceOf(ItemStack stack) {
		return priceOf(stack, 0);
	}

	private static double priceOf(ItemStack stack, int depth) {
		if (stack.isEmpty() || depth > MAX_CONTENT_DEPTH) {
			return 0.0;
		}

		ensureLoaded();

		double price = basePriceOf(stack) * PriceDrift.multiplier(stack.getItem())
				* EventService.multiplier(stack.getItem()) * stack.getCount();

		// Зачарования на самом предмете.
		ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);

		if (enchantments != null) {
			price += EnchantmentPrices.valueOnItem(stack, enchantments);
		}

		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (stored != null) {
			price += EnchantmentPrices.valueOf(stored);
		}

		price += contentsPrice(stack, depth);

		return price;
	}

	public static double basePriceOf(ItemStack stack) {
		if (stack.has(DataComponents.POTION_CONTENTS)) {
			return PotionPrices.priceOf(stack);
		}

		if (stack.is(Items.OMINOUS_BOTTLE)) {
			OminousBottleAmplifier amplifier = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
			return PotionPrices.ominousPrice(amplifier == null ? 0 : amplifier.value());
		}

		if (TurboUpgrade.isTurbo(stack)) {
			return SpecialCatalog.turboPriceOf(TurboUpgrade.levelOf(stack)).orElse(UNKNOWN_PRICE);
		}

		return prices.getOrDefault(stack.getItem(), UNKNOWN_PRICE);
	}

	public static double unitPriceOf(ItemStack stack) {
		return stack.isEmpty() ? 0.0 : basePriceOf(stack);
	}

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

	public static boolean isPriced(Item item) {
		ensureLoaded();
		return prices.containsKey(item);
	}

	public static boolean isBuyable(Item item) {
		ensureLoaded();
		return loaded && prices.containsKey(item) && !notBuyable.contains(item);
	}

	public static boolean isBlacklisted(Item item) {
		ensureLoaded();
		return notBuyable.contains(item);
	}

	public static boolean isLoaded() {
		ensureLoaded();
		return loaded;
	}

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

	public static int size() {
		ensureLoaded();
		return prices.size();
	}
}
