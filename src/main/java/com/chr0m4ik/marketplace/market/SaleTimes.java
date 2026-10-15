package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Сколько времени рынок продаёт предмет.
 *
 * <p>Файл {@code data/marketplace/market_times.json} создаётся генератором прайса: там для каждого
 * предмета лежит время продажи **одной штуки** в секундах (стак продаётся пропорционально дольше).
 * Формула: {@code 2 сек × цена^0,35} — земля уходит за пару секунд, элитры за несколько часов.
 *
 * <p>Настоящее время получается случайным: от {@value #MIN_FACTOR} до {@value #MAX_FACTOR} от
 * среднего, причём чем дороже предмет, тем выше шанс, что продажа затянется. Кроме того, наценка
 * замедляет продажу (см. {@link #markupSlowdown}).
 */
public final class SaleTimes {
	private static final String FILE_PATH = "/data/marketplace/market_times.json";
	private static final Codec<Map<String, Double>> TIMES_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);

	/** Самый быстрый вариант: в 20 раз быстрее среднего. */
	public static final double MIN_FACTOR = 0.05;

	/** Самый долгий вариант: в 10 раз дольше среднего. */
	public static final double MAX_FACTOR = 10.0;

	/** Если предмета нет в файле — считаем, что он продаётся минуту. */
	private static final double FALLBACK_SECONDS = 60.0;

	/** Минимум времени на одну продажу, в тиках (полсекунды). */
	public static final long MIN_TICKS = 10L;

	/**
	 * Потолок времени продажи: сутки реального времени. Нужен, потому что для дорогих предметов
	 * складываются сразу три множителя (долгое базовое время, случайный разброс до ×10 и замедление
	 * от наценки до ×10) — без потолка ордер мог бы висеть неделями.
	 */
	public static final long MAX_TICKS = 24L * 60L * 60L * 20L;

	private static Map<Item, Double> seconds = Map.of();
	private static boolean loadAttempted;
	private static boolean loaded;

	private SaleTimes() {
		// Класс только со статическими методами.
	}

	/** Читает времена продажи из ресурсов мода при первом обращении. */
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

			MarketplaceMod.LOGGER.info("Время продажи на рынке загружено: {} предметов", seconds.size());
		} catch (IOException | RuntimeException exception) {
			// Не прячем проблему: пишем понятную ошибку и остаёмся на запасном значении.
			MarketplaceMod.LOGGER.error("Не удалось прочитать файл времени продажи {}", FILE_PATH, exception);
		}
	}

	private static Optional<Item> resolveItem(String id) {
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

		if (item.isEmpty()) {
			MarketplaceMod.LOGGER.warn("Во времени продажи есть неизвестный предмет, запись пропущена: {}", id);
		}

		return item;
	}

	/** Сколько секунд продаётся одна штука предмета. */
	public static double secondsPerItem(Item item) {
		ensureLoaded();
		return seconds.getOrDefault(item, FALLBACK_SECONDS);
	}

	/**
	 * Во сколько раз наценка замедляет продажу (задано заказчиком):
	 * x1 — обычно, x1.25 — в 2 раза, x1.5 — в 5 раз, x1.75 — в 8 раз, x2 — в 10 раз.
	 */
	public static double markupSlowdown(double markup) {
		if (markup >= 2.0) {
			return 10.0;
		}

		if (markup >= 1.75) {
			return 8.0;
		}

		if (markup >= 1.5) {
			return 5.0;
		}

		if (markup >= 1.25) {
			return 2.0;
		}

		return 1.0;
	}

	/**
	 * Случайное отклонение времени. Чем дороже предмет, тем сильнее распределение смещается
	 * к долгой части диапазона: дешёвый обычно уходит быстро, дорогой — чаще висит долго.
	 */
	public static double randomFactor(double price, RandomSource random) {
		double skew = Math.max(0.35, Math.min(2.0, 2.0 - 0.15 * Math.log10(Math.max(1.0, price))));
		return MIN_FACTOR + Math.pow(random.nextDouble(), skew) * (MAX_FACTOR - MIN_FACTOR);
	}

	/** Сколько тиков будет продаваться этот стак при такой наценке. */
	public static long ticksFor(ItemStack stack, double markup, RandomSource random) {
		double secondsForStack = secondsPerItem(stack.getItem()) * stack.getCount();
		double slowdown = markupSlowdown(markup);
		double factor = randomFactor(ItemPrices.priceOf(stack), random);
		long ticks = Math.round(secondsForStack * slowdown * factor * 20.0);
		return Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));
	}

	/** Загрузились ли времена из файла. */
	public static boolean isLoaded() {
		ensureLoaded();
		return loaded;
	}
}
