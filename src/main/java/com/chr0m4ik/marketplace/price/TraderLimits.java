package com.chr0m4ik.marketplace.price;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Дневные лимиты торговца: сколько стаков каждого предмета он готов купить за игровой день.
 *
 * <p>Файл {@code data/marketplace/trader_limits.json} создаётся генератором прайса.
 * Почти все предметы имеют общий лимит ({@link #defaultLimit()}), а для редких указаны
 * меньшие значения: чем дороже предмет, тем он реже. Нестакающиеся предметы (оружие, броня,
 * тотемы) считаются как один стак за штуку.
 */
public final class TraderLimits {
	private static final String FILE_PATH = "/data/marketplace/trader_limits.json";
	private static final Codec<Map<String, Double>> LIMITS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);
	/** Если файл не прочитался — торговец будет брать по столько стаков. */
	private static final double FALLBACK_LIMIT = 30.0;

	private static Map<Item, Double> limits = Map.of();
	private static double defaultLimit = FALLBACK_LIMIT;
	private static boolean loadAttempted;
	private static boolean loaded;

	private TraderLimits() {
		// Класс только со статическими методами.
	}

	/** Читает лимиты из ресурсов мода при первом обращении. */
	public static synchronized void ensureLoaded() {
		if (loadAttempted) {
			return;
		}

		loadAttempted = true;

		Map<Item, Double> loadedLimits = new HashMap<>();

		try (InputStream stream = TraderLimits.class.getResourceAsStream(FILE_PATH)) {
			if (stream == null) {
				MarketplaceMod.LOGGER.error("Не найден файл лимитов {} — торговец будет брать обычное количество",
						FILE_PATH);
				return;
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();

				defaultLimit = object.get("default").getAsDouble();

				LIMITS_CODEC.parse(JsonOps.INSTANCE, object.get("limits")).getOrThrow()
						.forEach((id, stacks) -> resolveItem(id).ifPresent(item -> loadedLimits.put(item, stacks)));
			}

			limits = Map.copyOf(loadedLimits);
			loaded = true;

			MarketplaceMod.LOGGER.info("Лимиты торговца загружены: общий {} стаков, особых случаев {}",
					defaultLimit, limits.size());
		} catch (IOException | RuntimeException exception) {
			// Не прячем проблему: пишем понятную ошибку и остаёмся на общем лимите.
			MarketplaceMod.LOGGER.error("Не удалось прочитать файл лимитов {}", FILE_PATH, exception);
		}
	}

	private static Optional<Item> resolveItem(String id) {
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

		if (item.isEmpty()) {
			MarketplaceMod.LOGGER.warn("В лимитах есть неизвестный предмет, запись пропущена: {}", id);
		}

		return item;
	}

	/** Сколько стаков предмета торговец берёт за игровой день. */
	public static double limitOf(Item item) {
		ensureLoaded();
		return limits.getOrDefault(item, defaultLimit);
	}

	/** Общий лимит для предметов, которых нет в списке исключений. */
	public static double defaultLimit() {
		ensureLoaded();
		return defaultLimit;
	}

	/** Сколько предметов с особым лимитом — для проверок и логов. */
	public static int size() {
		ensureLoaded();
		return limits.size();
	}

	/** Загрузились ли лимиты из файла. */
	public static boolean isLoaded() {
		ensureLoaded();
		return loaded;
	}
}
