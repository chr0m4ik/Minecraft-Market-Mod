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

/**
 * Экономические множители мода: одно место, где заказчик крутит цифры без пересборки.
 *
 * <p>Файл {@code data/marketplace/economy.json} лежит в ресурсах мода и читается прямо из
 * jar-файла — так же, как прайс ({@link com.chr0m4ik.marketplace.price.ItemPrices#ensureLoaded()}).
 * Поэтому и клиент, и сервер видят одни и те же числа, а сеть и ресурс-менеджер сервера
 * для этого не нужны.
 *
 * <p>Что здесь есть:
 * <ul>
 *     <li>{@code buy_multiplier} — во сколько раз покупка дороже базовой цены;</li>
 *     <li>{@code trader_multiplier} — доля цены, которую даёт торговец;</li>
 *     <li>{@code market_markups} — наценки рынка (x1…x2);</li>
 *     <li>{@code markup_slowdowns} — во сколько раз наценка замедляет продажу (пары с {@code market_markups} по порядку);</li>
 *     <li>{@code drift_amplitude} — амплитуда микро-дрейфа (±доля цены);</li>
 *     <li>{@code craft_markup} и {@code smelt_markup} — наценки генератора прайса (для справки).</li>
 * </ul>
 *
 * <p><b>Проверка при старте.</b> Покупка обязана быть дороже самой выгодной продажи даже
 * в худшем для игрока случае (дрейф): {@code buy × (1 − амплитуда)} должно быть больше
 * {@code max(наценки) × (1 + амплитуда)} и больше {@code торговец × (1 + надбавка) × (1 + амплитуда)}.
 * Если условие нарушено, в лог пишется {@code ERROR} с понятным текстом — иначе игрок сможет
 * покупать предмет и продавать его же дороже, то есть печатать деньги.
 *
 * <p>Надбавка торговца берётся из {@link TraderPrices#MAX_BONUS}: это его личная особенность,
 * а не мировая настройка экономики.
 */
public final class EconomyConfig {
	/** Путь к настройкам внутри мода. */
	private static final String FILE_PATH = "/data/marketplace/economy.json";

	/** Значения по умолчанию: с ними мод работает, если файл не прочитан. */
	private static final double DEFAULT_BUY_MULTIPLIER = 2.2;
	private static final double DEFAULT_TRADER_MULTIPLIER = 0.75;
	private static final double[] DEFAULT_MARKET_MARKUPS = {1.0, 1.25, 1.5, 1.75, 2.0};
	private static final double[] DEFAULT_MARKUP_SLOWDOWNS = {1.0, 2.0, 5.0, 8.0, 10.0};
	private static final double DEFAULT_DRIFT_AMPLITUDE = 0.03;
	private static final double DEFAULT_CRAFT_MARKUP = 1.25;
	private static final double DEFAULT_SMELT_MARKUP = 1.15;

	/** Набор значений из файла. Массивы не меняются после чтения. */
	private record Values(double buyMultiplier, double traderMultiplier, double[] marketMarkups,
			double[] markupSlowdowns, double driftAmplitude, double craftMarkup, double smeltMarkup) {
	}

	private static final Values DEFAULTS = new Values(DEFAULT_BUY_MULTIPLIER, DEFAULT_TRADER_MULTIPLIER,
			DEFAULT_MARKET_MARKUPS, DEFAULT_MARKUP_SLOWDOWNS, DEFAULT_DRIFT_AMPLITUDE,
			DEFAULT_CRAFT_MARKUP, DEFAULT_SMELT_MARKUP);

	private static volatile Values values = DEFAULTS;
	private static volatile boolean loadAttempted;
	private static volatile boolean loaded;

	private EconomyConfig() {
		// Класс только со статическими методами.
	}

	/**
	 * Читает настройки из ресурсов мода и проверяет их. Вызывается при загрузке мода
	 * (и на клиенте, и на сервере) и сама — при первом обращении к любому значению.
	 */
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
				Values parsed = new Values(
						number(object, "buy_multiplier", DEFAULT_BUY_MULTIPLIER),
						number(object, "trader_multiplier", DEFAULT_TRADER_MULTIPLIER),
						array(object, "market_markups", DEFAULT_MARKET_MARKUPS),
						array(object, "markup_slowdowns", DEFAULT_MARKUP_SLOWDOWNS),
						number(object, "drift_amplitude", DEFAULT_DRIFT_AMPLITUDE),
						number(object, "craft_markup", DEFAULT_CRAFT_MARKUP),
						number(object, "smelt_markup", DEFAULT_SMELT_MARKUP));
				values = parsed;
				loaded = true;

				MarketplaceMod.LOGGER.info("Настройки экономики загружены: покупка ×{}, торговец ×{},"
								+ " наценки рынка {}, дрейф ±{}%, наценка крафта {}, переплавки {}",
						parsed.buyMultiplier(), parsed.traderMultiplier(), format(parsed.marketMarkups()),
						parsed.driftAmplitude() * 100.0, parsed.craftMarkup(), parsed.smeltMarkup());
				validate(parsed);
			}
		} catch (IOException | RuntimeException exception) {
			// Не прячем проблему: пишем понятную ошибку и работаем на значениях по умолчанию.
			MarketplaceMod.LOGGER.error("Не удалось прочитать настройки экономики {}", FILE_PATH, exception);
			validate(DEFAULTS);
		}
	}

	/** Число из файла; если поля нет — значение по умолчанию. */
	private static double number(JsonObject object, String key, double fallback) {
		JsonElement element = object.get(key);

		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			MarketplaceMod.LOGGER.warn("В настройках экономики нет числа «{}» — беру значение по умолчанию {}",
					key, fallback);
			return fallback;
		}

		return element.getAsDouble();
	}

	/** Список чисел из файла; если поля нет или список пуст — значения по умолчанию. */
	private static double[] array(JsonObject object, String key, double[] fallback) {
		JsonElement element = object.get(key);

		if (element == null || !element.isJsonArray()) {
			MarketplaceMod.LOGGER.warn("В настройках экономики нет списка «{}» — беру значения по умолчанию",
					key);
			return fallback;
		}

		JsonArray json = element.getAsJsonArray();
		double[] result = new double[json.size()];

		for (int i = 0; i < json.size(); i++) {
			JsonElement value = json.get(i);

			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				MarketplaceMod.LOGGER.warn("В настройках экономики «{}» содержит не число — беру значения по умолчанию",
						key);
				return fallback;
			}

			result[i] = value.getAsDouble();
		}

		if (result.length == 0) {
			MarketplaceMod.LOGGER.warn("В настройках экономики список «{}» пуст — беру значения по умолчанию", key);
			return fallback;
		}

		return result;
	}

	/** Список чисел в виде строки для лога: «1.0, 1.25, 1.5». */
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

	/**
	 * Проверка «печати денег». Покупка должна быть дороже любой продажи даже в худшем
	 * для мода случае: купили на самом дне дрейфа, продали на самом пике.
	 */
	private static void validate(Values checked) {
		double amplitude = Math.max(0.0, checked.driftAmplitude());
		double low = 1.0 - amplitude;
		double high = 1.0 + amplitude;
		double buy = checked.buyMultiplier() * low;
		double market = maxOf(checked.marketMarkups()) * high;
		double trader = checked.traderMultiplier() * (1.0 + TraderPrices.MAX_BONUS) * high;
		boolean ok = true;

		if (buy <= market) {
			ok = false;
			MarketplaceMod.LOGGER.error("НАСТРОЙКИ ЭКОНОМИКИ ПОЗВОЛЯЮТ ПЕЧАТАТЬ ДЕНЬГИ: покупка даёт {}"
							+ " (buy_multiplier {} × {}), а рынок платит до {} (наценка {} × {}) — покупка обязана"
							+ " быть дороже. Уменьшите market_markups или увеличьте buy_multiplier в {}",
					num(buy), checked.buyMultiplier(), num(low), num(market),
					num(maxOf(checked.marketMarkups())), num(high), FILE_PATH);
		}

		if (buy <= trader) {
			ok = false;
			MarketplaceMod.LOGGER.error("НАСТРОЙКИ ЭКОНОМИКИ ПОЗВОЛЯЮТ ПЕЧАТАТЬ ДЕНЬГИ: покупка даёт {}"
							+ " (buy_multiplier {} × {}), а торговец с максимальной надбавкой {} платит до {}"
							+ " (trader_multiplier {} × {} × {}) — покупка обязана быть дороже. Уменьшите"
							+ " trader_multiplier или увеличьте buy_multiplier в {}",
					num(buy), checked.buyMultiplier(), num(low), num(TraderPrices.MAX_BONUS), num(trader),
					checked.traderMultiplier(), num(1.0 + TraderPrices.MAX_BONUS), num(high), FILE_PATH);
		}

		if (checked.marketMarkups().length != checked.markupSlowdowns().length) {
			MarketplaceMod.LOGGER.warn("В настройках экономики market_markups ({}) и markup_slowdowns ({})"
							+ " разной длины — лишние значения не используются",
					checked.marketMarkups().length, checked.markupSlowdowns().length);
		}

		if (ok) {
			MarketplaceMod.LOGGER.info("Проверка экономики пройдена: худшая покупка {} > рынок {} и торговец {}",
					num(buy), num(market), num(trader));
		}
	}

	/** Число для лога: 4 знака после точки и точка как разделитель, независимо от языка системы. */
	private static String num(double value) {
		return String.format(java.util.Locale.ROOT, "%.4f", value);
	}

	/** Самая большая наценка рынка. */
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

	/** Во сколько раз покупка дороже базовой цены. */
	public static double buyMultiplier() {
		ensureLoaded();
		return values.buyMultiplier();
	}

	/** Сколько от базовой цены даёт торговец (без его личных уценок и надбавок). */
	public static double traderMultiplier() {
		ensureLoaded();
		return values.traderMultiplier();
	}

	/** Наценки рынка, между которыми переключается игрок (x1…x2). */
	public static double[] marketMarkups() {
		ensureLoaded();
		return values.marketMarkups().clone();
	}

	/** Во сколько раз каждая наценка рынка замедляет продажу (пары с {@link #marketMarkups()}). */
	public static double[] markupSlowdowns() {
		ensureLoaded();
		return values.markupSlowdowns().clone();
	}

	/** Амплитуда микро-дрейфа цены: доля от базовой цены. */
	public static double driftAmplitude() {
		ensureLoaded();
		return values.driftAmplitude();
	}

	/** Наценка генератора прайса на крафт (сам мод её не применяет — только для справки). */
	public static double craftMarkup() {
		ensureLoaded();
		return values.craftMarkup();
	}

	/** Наценка генератора прайса на переплавку (сам мод её не применяет — только для справки). */
	public static double smeltMarkup() {
		ensureLoaded();
		return values.smeltMarkup();
	}

	/** Прочитан ли файл настроек (если нет — работают значения по умолчанию). */
	public static boolean isLoaded() {
		ensureLoaded();
		return loaded;
	}
}
