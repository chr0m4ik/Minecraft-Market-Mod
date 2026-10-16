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
 *
 * <p>Время считается вместе с содержимым контейнеров (см. {@link #secondsOf}): шалкер с полным
 * составом продаётся столько же, сколько его состав по отдельности. Это осознанный баланс
 * «прибыль против времени» — см. комментарий в {@link #secondsOf}.
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
	 * Потолок времени продажи: трое реальных суток. Нужен, потому что для дорогих предметов
	 * складываются сразу три множителя: время самого предмета **вместе с содержимым**
	 * (см. {@link #secondsOf}), случайный разброс до ×10 и замедление от наценки до ×10.
	 *
	 * <p>Суток не хватало: набор «ПВП про» на x2 продаётся дольше 24 часов, и потолок обрезал
	 * бы замысел заказчика («прибыль против времени» — см. {@code KitCatalog}). Поэтому потолок
	 * поднят до 72 часов: даже с потолком один ордер с полным набором на x2 занимает дни.
	 */
	public static final long MAX_TICKS = 72L * 60L * 60L * 20L;

	/** Насколько глубоко разворачиваем содержимое контейнеров (защита от зацикливания). */
	private static final int MAX_CONTENT_DEPTH = 8;

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
	 * Во сколько раз наценка замедляет продажу (задано заказчиком): x1 — обычно, x1.25 — в 2 раза,
	 * x1.5 — в 5 раз, x1.75 — в 8 раз, x2 — в 10 раз. Пары «наценка → замедление» лежат в
	 * {@code data/marketplace/economy.json} ({@code market_markups} и {@code markup_slowdowns}),
	 * поэтому заказчик может их менять без пересборки. Для наценки между ступенями берётся
	 * нижняя ступень, для наценки выше самой большой — замедление последней ступени.
	 */
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

	/**
	 * Случайное отклонение времени. Чем дороже предмет, тем сильнее распределение смещается
	 * к долгой части диапазона: дешёвый обычно уходит быстро, дорогой — чаще висит долго.
	 */
	public static double randomFactor(double price, RandomSource random) {
		double skew = Math.max(0.35, Math.min(2.0, 2.0 - 0.15 * Math.log10(Math.max(1.0, price))));
		return MIN_FACTOR + Math.pow(random.nextDouble(), skew) * (MAX_FACTOR - MIN_FACTOR);
	}

	/**
	 * Время продажи одной штуки вместе с содержимым контейнеров. Считаем рекурсивно, как
	 * лимиты торговца ({@code TraderService.limitCost}): время самого предмета плюс время
	 * каждого предмета внутри. Так шалкер с полным составом продаётся столько же, сколько
	 * его содержимое по отдельности, — это осознанный баланс «прибыль против времени»:
	 * наценка x2 к такому ордеру обменивает двойную выплату на дни ожидания.
	 */
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

	/** Сколько тиков будет продаваться этот стак при такой наценке. */
	public static long ticksFor(ItemStack stack, double markup, RandomSource random) {
		double secondsForStack = secondsOf(stack);
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
