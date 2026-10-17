package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class SaleTimes {
	private static final String FILE_PATH = "/data/marketplace/market_times.json";
	private static final Codec<Map<String, Double>> TIMES_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);

	public static final double MIN_FACTOR = 0.05;

	public static final double MAX_FACTOR = 10.0;

	private static final double FALLBACK_SECONDS = 60.0;

	public static final long MIN_TICKS = 10L;

	public static final long MAX_TICKS = 72L * 60L * 60L * 20L;

	private static final int MAX_CONTENT_DEPTH = 8;

	private static Map<Item, Double> seconds = Map.of();
	private static boolean loadAttempted;
	private static boolean loaded;

	private SaleTimes() {
		// Класс только со статическими методами.
	}

	public static synchronized void ensureLoaded() {
		if (loadAttempted) {
			return;
		}

		loadAttempted = true;
		Map<Item, Double> loadedTimes = new HashMap<>();

		try (InputStream stream = SaleTimes.class.getResourceAsStream(FILE_PATH)) {
			if (stream == null) {
				MarketplaceMod.LOGGER.error("Не найден файл времени продажи {} — считаем всё по минуте", FILE_PATH);
				return;
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();

				TIMES_CODEC.parse(JsonOps.INSTANCE, object).getOrThrow()
						.forEach((id, value) -> resolveItem(id).ifPresent(item -> loadedTimes.put(item, value)));
			}

			seconds = Map.copyOf(loadedTimes);
			loaded = true;

			// MarketplaceMod.LOGGER.info("Время продажи на рынке загружено: {} предметов", seconds.size());
		} catch (IOException | RuntimeException exception) {

			MarketplaceMod.LOGGER.error("Не удалось прочитать файл времени продажи {}", FILE_PATH, exception);
		}
	}

	private static Optional<Item> resolveItem(String id) {
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

		if (item.isEmpty()) {
			// MarketplaceMod.LOGGER.warn("Во времени продажи есть неизвестный предмет, запись пропущена: {}", id);
		}

		return item;
	}

	public static double secondsPerItem(Item item) {
		ensureLoaded();
		return seconds.getOrDefault(item, FALLBACK_SECONDS);
	}

	public static double markupSlowdown(double markup) {
		double[] markups = EconomyConfig.marketMarkups();
		double[] slowdowns = EconomyConfig.markupSlowdowns();
		int pairs = Math.min(markups.length, slowdowns.length);

		if (pairs == 0) {
			return 1.0;
		}

		double result = slowdowns[0];

		for (int i = 0; i < pairs; i++) {
			if (markup + 1e-9 < markups[i]) {
				break;
			}

			result = slowdowns[i];
		}

		return result;
	}

	public static double randomFactor(double price, RandomSource random) {
		double skew = Math.max(0.35, Math.min(2.0, 2.0 - 0.15 * Math.log10(Math.max(1.0, price))));
		return MIN_FACTOR + Math.pow(random.nextDouble(), skew) * (MAX_FACTOR - MIN_FACTOR);
	}

	public static double secondsOf(ItemStack stack) {
		return secondsOf(stack, 0);
	}

	private static double secondsOf(ItemStack stack, int depth) {
		if (stack.isEmpty() || depth > MAX_CONTENT_DEPTH) {
			return 0.0;
		}

		double total = secondsPerItem(stack.getItem()) * stack.getCount();

		ItemContainerContents container = stack.get(DataComponents.CONTAINER);

		if (container != null) {
			for (ItemStack inside : container.itemCopies().toList()) {
				total += secondsOf(inside, depth + 1);
			}
		}

		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);

		if (bundle != null) {
			for (ItemStack inside : bundle.itemCopies().toList()) {
				total += secondsOf(inside, depth + 1);
			}
		}

		return total;
	}

	public static long ticksFor(ItemStack stack, double markup, RandomSource random) {
		double secondsForStack = secondsOf(stack);
		double slowdown = markupSlowdown(markup);
		double factor = randomFactor(ItemPrices.priceOf(stack), random);
		long ticks = Math.round(secondsForStack * slowdown * factor * 20.0);
		return Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));
	}

	public static long ticksForPrice(ItemStack stack, double price, RandomSource random) {

		double base = ItemPrices.basePriceOf(stack) * stack.getCount();

		if (base <= 0.0) {
			return MAX_TICKS;
		}

		double ratio = price / base;
		double secondsForStack = secondsOf(stack);
		double slowdown = markupSlowdownFor(ratio);
		double factor = randomFactor(ItemPrices.priceOf(stack), random);
		long ticks = Math.round(secondsForStack * slowdown * factor * 20.0);
		return Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));
	}

	public static double markupSlowdownFor(double ratio) {
		double[] markups = EconomyConfig.marketMarkups();
		double[] slowdowns = EconomyConfig.markupSlowdowns();
		int pairs = Math.min(markups.length, slowdowns.length);

		if (pairs == 0) {
			return 1.0;
		}

		if (ratio <= markups[0]) {
			return Math.max(0.1, ratio);
		}

		for (int index = 1; index < pairs; index++) {
			if (ratio <= markups[index]) {
				double span = markups[index] - markups[index - 1];
				double part = span <= 0.0 ? 0.0 : (ratio - markups[index - 1]) / span;
				return slowdowns[index - 1] + part * (slowdowns[index] - slowdowns[index - 1]);
			}
		}

		return slowdowns[pairs - 1];
	}

}
