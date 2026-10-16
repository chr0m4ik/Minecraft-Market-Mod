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
 *     <li>{@code craft_markup} и {@code smelt_markup} — наценки генератора прайса (для справки);</li>
 *     <li>{@code sounds} — играть ли звуки мода ({@code false} выключает их все);</li>
 *     <li>{@code event_*} — крупные экономические ивенты: шанс в игровой день, длительность
 *     в секундах, максимальный процент изменения цены, максимальное число предметов и слухи
 *     (см. {@link #eventMaxPercent()} и {@code event/EventService}).</li>
 * </ul>
 *
 * <p><b>Проверка при старте.</b> Покупка обязана быть дороже самой выгодной продажи даже
 * в худшем для игрока случае (дрейф и ивент): {@code buy × (1 − амплитуда) × (1 − ивент)} должно
 * быть больше {@code max(наценки) × (1 + амплитуда) × (1 − ивент)} и больше
 * {@code торговец × (1 + надбавка) × (1 + амплитуда) × (1 − ивент)}. Ивент симметричен (одинаково
 * удешевляет и покупку, и продажу), поэтому его множитель стоит с обеих сторон неравенства —
 * именно так посчитаны числа в логе. Если условие нарушено, в лог пишется {@code ERROR}
 * с понятным текстом — иначе игрок сможет покупать предмет и продавать его же дороже,
 * то есть печатать деньги.
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

	/** Играть ли звуки мода. Заказчик может выключить их одним флагом в файле. */
	private static final boolean DEFAULT_SOUNDS = true;

	/** Ивенты: шанс в игровой день, длительность в секундах, максимальный процент изменения цены. */
	private static final double DEFAULT_EVENT_DAILY_CHANCE = 0.33;
	private static final double DEFAULT_EVENT_MIN_SECONDS = 180.0;
	private static final double DEFAULT_EVENT_MAX_SECONDS = 600.0;
	private static final double DEFAULT_EVENT_MAX_PERCENT = 0.2;
	private static final int DEFAULT_EVENT_MAX_ITEMS = 10;
	private static final double DEFAULT_EVENT_RUMOR_CHANCE = 0.5;
	private static final double DEFAULT_EVENT_RUMOR_MIN_SECONDS = 30.0;
	private static final double DEFAULT_EVENT_RUMOR_MAX_SECONDS = 120.0;

	/**
	 * Пределы, за которые мод не выпускает настройки ивентов (при выходе — ERROR в лог
	 * и значение по умолчанию). {@value #EVENT_PERCENT_LIMIT} — договорённые ±20%:
	 * больше нельзя, иначе ивент перестаёт быть «до ±20%» и ломает проверку экономики.
	 */
	private static final double EVENT_PERCENT_LIMIT = 0.2;

	/** Самая короткая и самая длинная длительность ивента, которую примет мод (секунды). */
	private static final double EVENT_MIN_SECONDS_LIMIT = 1.0;
	private static final double EVENT_MAX_SECONDS_LIMIT = 3600.0;

	/** Больше этого числа предметов ивент не затронет: договорились про «до 10». */
	private static final int EVENT_MAX_ITEMS_LIMIT = 10;

	/** Настройки крупных ивентов (см. {@code event/EventService}). */
	private record EventSettings(double dailyChance, double minSeconds, double maxSeconds, double maxPercent,
			int maxItems, double rumorChance, double rumorMinSeconds, double rumorMaxSeconds) {
	}

	/** Набор значений из файла. Массивы не меняются после чтения. */
	private record Values(double buyMultiplier, double traderMultiplier, double[] marketMarkups,
			double[] markupSlowdowns, double driftAmplitude, double craftMarkup, double smeltMarkup,
			boolean sounds, EventSettings events) {
	}

	private static final EventSettings EVENT_DEFAULTS = new EventSettings(DEFAULT_EVENT_DAILY_CHANCE,
			DEFAULT_EVENT_MIN_SECONDS, DEFAULT_EVENT_MAX_SECONDS, DEFAULT_EVENT_MAX_PERCENT,
			DEFAULT_EVENT_MAX_ITEMS, DEFAULT_EVENT_RUMOR_CHANCE, DEFAULT_EVENT_RUMOR_MIN_SECONDS,
			DEFAULT_EVENT_RUMOR_MAX_SECONDS);

	private static final Values DEFAULTS = new Values(DEFAULT_BUY_MULTIPLIER, DEFAULT_TRADER_MULTIPLIER,
			DEFAULT_MARKET_MARKUPS, DEFAULT_MARKUP_SLOWDOWNS, DEFAULT_DRIFT_AMPLITUDE,
			DEFAULT_CRAFT_MARKUP, DEFAULT_SMELT_MARKUP, DEFAULT_SOUNDS, EVENT_DEFAULTS);

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
				// Настройки ивентов проверяем сразу: заказчик может вписать что угодно, а мод
				// обязан работать только с разумными числами (процент ≤ 0.2 и так далее).
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
						flag(object, "sounds", DEFAULT_SOUNDS),
						events);
				values = parsed;
				loaded = true;

				MarketplaceMod.LOGGER.info("Настройки экономики загружены: покупка ×{}, торговец ×{},"
								+ " наценки рынка {}, дрейф ±{}%, наценка крафта {}, переплавки {}, звуки {}",
						parsed.buyMultiplier(), parsed.traderMultiplier(), format(parsed.marketMarkups()),
						parsed.driftAmplitude() * 100.0, parsed.craftMarkup(), parsed.smeltMarkup(),
						parsed.sounds() ? "включены" : "выключены");
				MarketplaceMod.LOGGER.info("Настройки ивентов: шанс {}% в игровой день, длительность"
								+ " {}–{} с, максимум ±{}%, до {} предметов, слух с шансом {}% за {}–{} с",
						one(events.dailyChance() * 100.0), one(events.minSeconds()), one(events.maxSeconds()),
						one(events.maxPercent() * 100.0), events.maxItems(),
						one(events.rumorChance() * 100.0), one(events.rumorMinSeconds()),
						one(events.rumorMaxSeconds()));
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

	/** Да/нет из файла; если поля нет — значение по умолчанию. */
	private static boolean flag(JsonObject object, String key, boolean fallback) {
		JsonElement element = object.get(key);

		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			MarketplaceMod.LOGGER.warn("В настройках экономики нет флага «{}» — беру значение по умолчанию {}",
					key, fallback);
			return fallback;
		}

		return element.getAsBoolean();
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
	 * Приводит настройки ивентов к разумным. Всё, что вне пределов, заменяется значением
	 * по умолчанию, а в лог идёт {@code ERROR} с числами — молчаливых исправлений не делаем:
	 * заказчик должен видеть, что именно в файле не так.
	 */
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

	/** Шанс: должен быть в пределах 0…1, иначе берём значение по умолчанию. */
	private static double chance(double value, String key, double fallback) {
		if (value < 0.0 || value > 1.0) {
			MarketplaceMod.LOGGER.error("НАСТРОЙКА ИВЕНТОВ НЕДОПУСТИМА: {} = {}, а допустимо от 0 до 1."
							+ " Беру {} в {}",
					key, num(value), num(fallback), FILE_PATH);
			return fallback;
		}

		return value;
	}

	/** Длительность в секундах: от {@value #EVENT_MIN_SECONDS_LIMIT} до {@value #EVENT_MAX_SECONDS_LIMIT}. */
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

	/**
	 * Проверка «печати денег». Покупка должна быть дороже любой продажи даже в худшем
	 * для мода случае: купили на самом дне дрейфа и во время ивента на падение, продали
	 * на самом пике. Ивент симметричен (одинаково опускает и покупку, и продажу), поэтому
	 * его множитель стоит с обеих сторон неравенства — ровно как в числах лога.
	 */
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
			MarketplaceMod.LOGGER.warn("В настройках экономики market_markups ({}) и markup_slowdowns ({})"
							+ " разной длины — лишние значения не используются",
					checked.marketMarkups().length, checked.markupSlowdowns().length);
		}

		if (ok) {
			MarketplaceMod.LOGGER.info("Проверка экономики пройдена: худшая покупка {} > рынок {} и торговец {}",
					num(buy), num(market), num(trader));
			// Мгновенной наживы нет: ивент симметричен. Но купить предмет во время ивента на падение
			// и продать его уже после окончания ивента — выгодно, и это заложено в согласованной схеме.
			// Пишем числа в лог, чтобы это не всплыло сюрпризом.
			double afterEvent = maxOf(checked.marketMarkups()) * high;
			MarketplaceMod.LOGGER.info("Известное следствие ивентов: во время падения цены предмет стоит {}"
							+ " (buy_multiplier {} × дрейф {} × ивент {}), а после окончания ивента рынок"
							+ " платит до {} — разница до {} в пользу покупки на падении",
					num(buy), num(checked.buyMultiplier()), num(low), num(eventLow), num(afterEvent),
					num(afterEvent - buy));
		}
	}

	/** Число для лога: 4 знака после точки и точка как разделитель, независимо от языка системы. */
	private static String num(double value) {
		return String.format(java.util.Locale.ROOT, "%.4f", value);
	}

	/** Число для лога покороче: один знак после точки (проценты, секунды ивентов). */
	private static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
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

	/**
	 * Включены ли звуки мода ({@code sounds} в {@code economy.json}). При {@code false}
	 * не играет ни один звук мода: проверка стоит в одном месте — {@code MarketSounds}.
	 */
	public static boolean soundsEnabled() {
		ensureLoaded();
		return values.sounds();
	}

	// --- Крупные ивенты (см. event/EventService) ---

	/** Шанс, что в игровой день случится ивент (0…1). Бросок делается раз в игровой день. */
	public static double eventDailyChance() {
		ensureLoaded();
		return values.events().dailyChance();
	}

	/** Самая короткая длительность ивента в секундах реального времени. */
	public static double eventMinSeconds() {
		ensureLoaded();
		return values.events().minSeconds();
	}

	/** Самая длинная длительность ивента в секундах реального времени. */
	public static double eventMaxSeconds() {
		ensureLoaded();
		return values.events().maxSeconds();
	}

	/**
	 * Максимальное изменение цены ивентом: доля от базовой цены (0.2 = ±20%).
	 * Больше {@value #EVENT_PERCENT_LIMIT} мод не принимает — см. {@link #sanitize}.
	 */
	public static double eventMaxPercent() {
		ensureLoaded();
		return values.events().maxPercent();
	}

	/** Сколько предметов максимум затронет ивент на несколько предметов (договорились про «до 10»). */
	public static int eventMaxItems() {
		ensureLoaded();
		return values.events().maxItems();
	}

	/** Шанс, что перед ивентом придёт слух (0…1). */
	public static double eventRumorChance() {
		ensureLoaded();
		return values.events().rumorChance();
	}

	/** За сколько секунд до начала ивента слух может прийти (минимум). */
	public static double eventRumorMinSeconds() {
		ensureLoaded();
		return values.events().rumorMinSeconds();
	}

	/** За сколько секунд до начала ивента слух может прийти (максимум). */
	public static double eventRumorMaxSeconds() {
		ensureLoaded();
		return values.events().rumorMaxSeconds();
	}

	/** Прочитан ли файл настроек (если нет — работают значения по умолчанию). */
	public static boolean isLoaded() {
		ensureLoaded();
		return loaded;
	}
}
