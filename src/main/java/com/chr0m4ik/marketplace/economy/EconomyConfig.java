package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class EconomyConfig {

	private static final String FILE_PATH = "/data/marketplace/economy.json";

	private static final double DEFAULT_BUY_MULTIPLIER = 2.2;
	private static final double DEFAULT_TRADER_MULTIPLIER = 0.75;
	private static final double[] DEFAULT_MARKET_MARKUPS = {1.0, 1.25, 1.5, 1.75, 2.0};
	private static final double[] DEFAULT_MARKUP_SLOWDOWNS = {1.0, 2.0, 5.0, 8.0, 10.0};
	private static final double DEFAULT_DRIFT_AMPLITUDE = 0.03;
	private static final double DEFAULT_CRAFT_MARKUP = 1.25;
	private static final double DEFAULT_SMELT_MARKUP = 1.15;

	private static final double DEFAULT_CUSTOM_KIT_MARKUP = 1.2;

	private static final boolean DEFAULT_SOUNDS = true;

	private static final double DEFAULT_EVENT_DAILY_CHANCE = 0.33;
	private static final double DEFAULT_EVENT_MIN_SECONDS = 180.0;
	private static final double DEFAULT_EVENT_MAX_SECONDS = 600.0;
	private static final double DEFAULT_EVENT_MAX_PERCENT = 0.2;
	private static final int DEFAULT_EVENT_MAX_ITEMS = 10;
	private static final double DEFAULT_EVENT_RUMOR_CHANCE = 0.5;
	private static final double DEFAULT_EVENT_RUMOR_MIN_SECONDS = 30.0;
	private static final double DEFAULT_EVENT_RUMOR_MAX_SECONDS = 120.0;

	private static final double EVENT_PERCENT_LIMIT = 0.2;

	private static final double EVENT_MIN_SECONDS_LIMIT = 1.0;
	private static final double EVENT_MAX_SECONDS_LIMIT = 3600.0;

	private static final int EVENT_MAX_ITEMS_LIMIT = 10;

	private record EventSettings(double dailyChance, double minSeconds, double maxSeconds, double maxPercent,
			int maxItems, double rumorChance, double rumorMinSeconds, double rumorMaxSeconds) {
	}

	private record Values(double buyMultiplier, double traderMultiplier, double[] marketMarkups,
			double[] markupSlowdowns, double driftAmplitude, double craftMarkup, double smeltMarkup,
			double customKitMarkup, boolean sounds, EventSettings events) {
	}

	private static final EventSettings EVENT_DEFAULTS = new EventSettings(DEFAULT_EVENT_DAILY_CHANCE,
			DEFAULT_EVENT_MIN_SECONDS, DEFAULT_EVENT_MAX_SECONDS, DEFAULT_EVENT_MAX_PERCENT,
			DEFAULT_EVENT_MAX_ITEMS, DEFAULT_EVENT_RUMOR_CHANCE, DEFAULT_EVENT_RUMOR_MIN_SECONDS,
			DEFAULT_EVENT_RUMOR_MAX_SECONDS);

	private static final Values DEFAULTS = new Values(DEFAULT_BUY_MULTIPLIER, DEFAULT_TRADER_MULTIPLIER,
			DEFAULT_MARKET_MARKUPS, DEFAULT_MARKUP_SLOWDOWNS, DEFAULT_DRIFT_AMPLITUDE,
			DEFAULT_CRAFT_MARKUP, DEFAULT_SMELT_MARKUP, DEFAULT_CUSTOM_KIT_MARKUP, DEFAULT_SOUNDS,
			EVENT_DEFAULTS);

	private static volatile Values values = DEFAULTS;
	private static volatile boolean loadAttempted;
	private static volatile boolean loaded;

	private EconomyConfig() {
		// Класс только со статическими методами.
	}

	public static synchronized void ensureLoaded() {
		if (loadAttempted) {
			return;
		}

		loadAttempted = true;

		try (InputStream stream = EconomyConfig.class.getResourceAsStream(FILE_PATH)) {
			if (stream == null) {
				MarketplaceMod.LOGGER.error("Не найден файл настроек экономики {} — работаем на значениях по умолчанию",
						FILE_PATH);
				validate(DEFAULTS);
				return;
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();

				EventSettings events = sanitize(new EventSettings(
						number(object, "event_daily_chance", DEFAULT_EVENT_DAILY_CHANCE),
						number(object, "event_min_seconds", DEFAULT_EVENT_MIN_SECONDS),
						number(object, "event_max_seconds", DEFAULT_EVENT_MAX_SECONDS),
						number(object, "event_max_percent", DEFAULT_EVENT_MAX_PERCENT),
						(int) Math.round(number(object, "event_max_items", DEFAULT_EVENT_MAX_ITEMS)),
						number(object, "event_rumor_chance", DEFAULT_EVENT_RUMOR_CHANCE),
						number(object, "event_rumor_min_seconds", DEFAULT_EVENT_RUMOR_MIN_SECONDS),
						number(object, "event_rumor_max_seconds", DEFAULT_EVENT_RUMOR_MAX_SECONDS)));
				Values parsed = new Values(
						number(object, "buy_multiplier", DEFAULT_BUY_MULTIPLIER),
						number(object, "trader_multiplier", DEFAULT_TRADER_MULTIPLIER),
						array(object, "market_markups", DEFAULT_MARKET_MARKUPS),
						array(object, "markup_slowdowns", DEFAULT_MARKUP_SLOWDOWNS),
						number(object, "drift_amplitude", DEFAULT_DRIFT_AMPLITUDE),
						number(object, "craft_markup", DEFAULT_CRAFT_MARKUP),
						number(object, "smelt_markup", DEFAULT_SMELT_MARKUP),
						sanitizeCustomKitMarkup(object),
						flag(object, "sounds", DEFAULT_SOUNDS),
						events);
				values = parsed;
				loaded = true;

				// MarketplaceMod.LOGGER.info("Настройки экономики загружены: покупка ×{}, торговец ×{},"
								// + " наценки рынка {}, дрейф ±{}%, наценка крафта {}, переплавки {},"
								// + " свои сеты ×{}, звуки {}",
						// parsed.buyMultiplier(), parsed.traderMultiplier(), format(parsed.marketMarkups()),
						// parsed.driftAmplitude() * 100.0, parsed.craftMarkup(), parsed.smeltMarkup(),
						// parsed.customKitMarkup(), parsed.sounds() ? "включены" : "выключены");
				// MarketplaceMod.LOGGER.info("Настройки ивентов: шанс {}% в игровой день, длительность"
								// + " {}–{} с, максимум ±{}%, до {} предметов, слух с шансом {}% за {}–{} с",
						// one(events.dailyChance() * 100.0), one(events.minSeconds()), one(events.maxSeconds()),
						// one(events.maxPercent() * 100.0), events.maxItems(),
						// one(events.rumorChance() * 100.0), one(events.rumorMinSeconds()),
						// one(events.rumorMaxSeconds()));
				validate(parsed);
			}
		} catch (IOException | RuntimeException exception) {

			MarketplaceMod.LOGGER.error("Не удалось прочитать настройки экономики {}", FILE_PATH, exception);
			validate(DEFAULTS);
		}
	}

	private static double number(JsonObject object, String key, double fallback) {
		JsonElement element = object.get(key);

		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			// MarketplaceMod.LOGGER.warn("В настройках экономики нет числа «{}» — беру значение по умолчанию {}",
					// key, fallback);
			return fallback;
		}

		return element.getAsDouble();
	}

	private static boolean flag(JsonObject object, String key, boolean fallback) {
		JsonElement element = object.get(key);

		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			// MarketplaceMod.LOGGER.warn("В настройках экономики нет флага «{}» — беру значение по умолчанию {}",
					// key, fallback);
			return fallback;
		}

		return element.getAsBoolean();
	}

	private static double[] array(JsonObject object, String key, double[] fallback) {
		JsonElement element = object.get(key);

		if (element == null || !element.isJsonArray()) {
			// MarketplaceMod.LOGGER.warn("В настройках экономики нет списка «{}» — беру значения по умолчанию",
					// key);
			return fallback;
		}

		JsonArray json = element.getAsJsonArray();
		double[] result = new double[json.size()];

		for (int i = 0; i < json.size(); i++) {
			JsonElement value = json.get(i);

			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				// MarketplaceMod.LOGGER.warn("В настройках экономики «{}» содержит не число — беру значения по умолчанию",
						// key);
				return fallback;
			}

			result[i] = value.getAsDouble();
		}

		if (result.length == 0) {
			// MarketplaceMod.LOGGER.warn("В настройках экономики список «{}» пуст — беру значения по умолчанию", key);
			return fallback;
		}

		return result;
	}

	private static String format(double[] list) {
		StringBuilder text = new StringBuilder();

		for (double value : list) {
			if (text.length() > 0) {
				text.append(", ");
			}

			text.append(value);
		}

		return text.toString();
	}

	private static EventSettings sanitize(EventSettings settings) {
		double dailyChance = chance(settings.dailyChance(), "event_daily_chance", DEFAULT_EVENT_DAILY_CHANCE);
		double rumorChance = chance(settings.rumorChance(), "event_rumor_chance", DEFAULT_EVENT_RUMOR_CHANCE);
		double percent = settings.maxPercent();

		if (percent <= 0.0 || percent > EVENT_PERCENT_LIMIT) {
			MarketplaceMod.LOGGER.error("НАСТРОЙКА ИВЕНТОВ НЕДОПУСТИМА: event_max_percent = {}, а допустимо"
							+ " больше 0 и не больше {} (±{}%). Беру значение по умолчанию {} в {}",
					num(percent), num(EVENT_PERCENT_LIMIT), num(EVENT_PERCENT_LIMIT * 100.0),
					num(DEFAULT_EVENT_MAX_PERCENT), FILE_PATH);
			percent = DEFAULT_EVENT_MAX_PERCENT;
		}

		double minSeconds = seconds(settings.minSeconds(), "event_min_seconds", DEFAULT_EVENT_MIN_SECONDS);
		double maxSeconds = seconds(settings.maxSeconds(), "event_max_seconds", DEFAULT_EVENT_MAX_SECONDS);

		if (minSeconds > maxSeconds) {
			MarketplaceMod.LOGGER.error("НАСТРОЙКА ИВЕНТОВ НЕДОПУСТИМА: event_min_seconds = {} больше"
							+ " event_max_seconds = {} — беру {} с для обоих в {}",
					num(minSeconds), num(maxSeconds), num(minSeconds), FILE_PATH);
			maxSeconds = minSeconds;
		}

		int maxItems = settings.maxItems();

		if (maxItems < 1 || maxItems > EVENT_MAX_ITEMS_LIMIT) {
			MarketplaceMod.LOGGER.error("НАСТРОЙКА ИВЕНТОВ НЕДОПУСТИМА: event_max_items = {}, а допустимо"
							+ " от 1 до {}. Беру {} в {}",
					maxItems, EVENT_MAX_ITEMS_LIMIT, DEFAULT_EVENT_MAX_ITEMS, FILE_PATH);
			maxItems = DEFAULT_EVENT_MAX_ITEMS;
		}

		double rumorMin = seconds(settings.rumorMinSeconds(), "event_rumor_min_seconds",
				DEFAULT_EVENT_RUMOR_MIN_SECONDS);
		double rumorMax = seconds(settings.rumorMaxSeconds(), "event_rumor_max_seconds",
				DEFAULT_EVENT_RUMOR_MAX_SECONDS);

		if (rumorMin > rumorMax) {
			MarketplaceMod.LOGGER.error("НАСТРОЙКА ИВЕНТОВ НЕДОПУСТИМА: event_rumor_min_seconds = {} больше"
							+ " event_rumor_max_seconds = {} — беру {} с для обоих в {}",
					num(rumorMin), num(rumorMax), num(rumorMin), FILE_PATH);
			rumorMax = rumorMin;
		}

		return new EventSettings(dailyChance, minSeconds, maxSeconds, percent, maxItems, rumorChance,
				rumorMin, rumorMax);
	}

	private static double chance(double value, String key, double fallback) {
		if (value < 0.0 || value > 1.0) {
			MarketplaceMod.LOGGER.error("НАСТРОЙКА ИВЕНТОВ НЕДОПУСТИМА: {} = {}, а допустимо от 0 до 1."
							+ " Беру {} в {}",
					key, num(value), num(fallback), FILE_PATH);
			return fallback;
		}

		return value;
	}

	private static double seconds(double value, String key, double fallback) {
		if (value < EVENT_MIN_SECONDS_LIMIT || value > EVENT_MAX_SECONDS_LIMIT) {
			MarketplaceMod.LOGGER.error("НАСТРОЙКА ИВЕНТОВ НЕДОПУСТИМА: {} = {}, а допустимо от {} до {} с."
							+ " Беру {} в {}",
					key, num(value), num(EVENT_MIN_SECONDS_LIMIT), num(EVENT_MAX_SECONDS_LIMIT),
					num(fallback), FILE_PATH);
			return fallback;
		}

		return value;
	}

	private static void validate(Values checked) {
		double amplitude = Math.max(0.0, checked.driftAmplitude());
		double event = Math.max(0.0, checked.events().maxPercent());
		double low = 1.0 - amplitude;
		double high = 1.0 + amplitude;
		double eventLow = 1.0 - event;
		double buy = checked.buyMultiplier() * low * eventLow;
		double market = maxOf(checked.marketMarkups()) * high * eventLow;
		double trader = checked.traderMultiplier() * (1.0 + TraderPrices.MAX_BONUS) * high * eventLow;
		boolean ok = true;

		if (buy <= market) {
			ok = false;
			MarketplaceMod.LOGGER.error("НАСТРОЙКИ ЭКОНОМИКИ ПОЗВОЛЯЮТ ПЕЧАТАТЬ ДЕНЬГИ: покупка даёт {}"
							+ " (buy_multiplier {} × дрейф {} × ивент {}), а рынок платит до {}"
							+ " (наценка {} × дрейф {} × ивент {}) — покупка обязана быть дороже."
							+ " Уменьшите market_markups или event_max_percent, либо увеличьте"
							+ " buy_multiplier в {}",
					num(buy), num(checked.buyMultiplier()), num(low), num(eventLow), num(market),
					num(maxOf(checked.marketMarkups())), num(high), num(eventLow), FILE_PATH);
		}

		if (buy <= trader) {
			ok = false;
			MarketplaceMod.LOGGER.error("НАСТРОЙКИ ЭКОНОМИКИ ПОЗВОЛЯЮТ ПЕЧАТАТЬ ДЕНЬГИ: покупка даёт {}"
							+ " (buy_multiplier {} × дрейф {} × ивент {}), а торговец с максимальной"
							+ " надбавкой {} платит до {} (trader_multiplier {} × {} × дрейф {} × ивент {})"
							+ " — покупка обязана быть дороже. Уменьшите trader_multiplier или"
							+ " event_max_percent, либо увеличьте buy_multiplier в {}",
					num(buy), num(checked.buyMultiplier()), num(low), num(eventLow),
					num(TraderPrices.MAX_BONUS), num(trader), num(checked.traderMultiplier()),
					num(1.0 + TraderPrices.MAX_BONUS), num(high), num(eventLow), FILE_PATH);
		}

		if (checked.marketMarkups().length != checked.markupSlowdowns().length) {
			// MarketplaceMod.LOGGER.warn("В настройках экономики market_markups ({}) и markup_slowdowns ({})"
							// + " разной длины — лишние значения не используются",
					// checked.marketMarkups().length, checked.markupSlowdowns().length);
		}

		if (ok) {
			// MarketplaceMod.LOGGER.info("Проверка экономики пройдена: худшая покупка {} > рынок {} и торговец {}",
					// num(buy), num(market), num(trader));

			double afterEvent = maxOf(checked.marketMarkups()) * high;
			// MarketplaceMod.LOGGER.info("Известное следствие ивентов: во время падения цены предмет стоит {}"
							// + " (buy_multiplier {} × дрейф {} × ивент {}), а после окончания ивента рынок"
							// + " платит до {} — разница до {} в пользу покупки на падении",
					// num(buy), num(checked.buyMultiplier()), num(low), num(eventLow), num(afterEvent),
					// num(afterEvent - buy));
		}
	}

	private static String num(double value) {
		return String.format(java.util.Locale.ROOT, "%.4f", value);
	}

	private static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	public static double maxMarketMarkup() {
		ensureLoaded();
		return maxOf(values.marketMarkups());
	}

	private static double maxOf(double[] list) {
		double max = Double.NEGATIVE_INFINITY;

		for (double value : list) {
			max = Math.max(max, value);
		}

		return max == Double.NEGATIVE_INFINITY ? 0.0 : max;
	}

	public static double buyMultiplier() {
		ensureLoaded();
		return values.buyMultiplier();
	}

	public static double traderMultiplier() {
		ensureLoaded();
		return values.traderMultiplier();
	}

	public static double[] marketMarkups() {
		ensureLoaded();
		return values.marketMarkups().clone();
	}

	public static double[] markupSlowdowns() {
		ensureLoaded();
		return values.markupSlowdowns().clone();
	}

	public static double driftAmplitude() {
		ensureLoaded();
		return values.driftAmplitude();
	}

	public static double craftMarkup() {
		ensureLoaded();
		return values.craftMarkup();
	}

	public static double smeltMarkup() {
		ensureLoaded();
		return values.smeltMarkup();
	}

	public static double customKitMarkup() {
		ensureLoaded();
		return values.customKitMarkup();
	}

	private static double sanitizeCustomKitMarkup(JsonObject object) {
		double markup = number(object, "custom_kit_markup", DEFAULT_CUSTOM_KIT_MARKUP);

		if (markup < 1.0) {
			MarketplaceMod.LOGGER.error("СВОИ СЕТЫ ПОЗВОЛЯЮТ ПЕЧАТАТЬ ДЕНЬГИ: custom_kit_markup = {}, а допустимо"
							+ " не меньше 1.0 (иначе сет дешевле своего содержимого). Беру {} в {}",
					num(markup), num(DEFAULT_CUSTOM_KIT_MARKUP), FILE_PATH);
			return DEFAULT_CUSTOM_KIT_MARKUP;
		}

		if (markup < 1.05) {
			// MarketplaceMod.LOGGER.warn("Наценка своих сетов {} очень низкая: рынок платит 100% цены, из-за"
							// + " микро-дрейфа (±3%) такой сет можно разбирать с небольшой прибылью", num(markup));
		}

		return markup;
	}

	public static boolean soundsEnabled() {
		ensureLoaded();
		return values.sounds();
	}

	// --- Крупные ивенты (см. event/EventService) ---

	public static double eventDailyChance() {
		ensureLoaded();
		return values.events().dailyChance();
	}

	public static double eventMinSeconds() {
		ensureLoaded();
		return values.events().minSeconds();
	}

	public static double eventMaxSeconds() {
		ensureLoaded();
		return values.events().maxSeconds();
	}

	public static double eventMaxPercent() {
		ensureLoaded();
		return values.events().maxPercent();
	}

	public static int eventMaxItems() {
		ensureLoaded();
		return values.events().maxItems();
	}

	public static double eventRumorChance() {
		ensureLoaded();
		return values.events().rumorChance();
	}

	public static double eventRumorMinSeconds() {
		ensureLoaded();
		return values.events().rumorMinSeconds();
	}

	public static double eventRumorMaxSeconds() {
		ensureLoaded();
		return values.events().rumorMaxSeconds();
	}

}
