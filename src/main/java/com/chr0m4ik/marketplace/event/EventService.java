package com.chr0m4ik.marketplace.event;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Крупные экономические ивенты: что это за ивент сейчас и как он меняет цены.
 *
 * <p><b>Сервер — единственный источник истины.</b> Описание активного ивента лежит в сейве мира
 * ({@code economy/MarketplaceState}), а сервер рассылает его клиенту тем же пакетом, что и зерно
 * мира с игровым временем ({@code network/MarketplaceStatusPayload}). Клиент кладёт описание сюда
 * ({@link #applyFromServer}) и считает множитель <b>тем же кодом</b> ({@link #multiplier}), что и
 * сервер: {@link MarketEvent#affects} — чистая функция от id предмета и описания ивента. Поэтому
 * подсказки в окнах совпадают с тем, что игрок платит на самом деле, — как у микро-дрейфа
 * ({@code PriceDrift}).
 *
 * <p>Множитель кэшируется по предмету и пересчитывается только когда ивент сменился
 * ({@link #revision()}): цену спрашивают каждый кадр, а ивент живёт минутами.
 *
 * <p>Кто, когда и насколько запускает ивент, решает серверная часть — {@link #register()} и
 * {@link #tick}: раз в игровой день с шансом из настроек, только пока в мире есть игроки.
 */
public final class EventService {
	/**
	 * Самая слабая доля от максимального процента: изменение меньше четверти максимума игрок
	 * просто не заметит, поэтому ивент всегда заметный. Направление и величина — из настроек.
	 */
	private static final double MIN_MAGNITUDE_SHARE = 0.25;

	/**
	 * Как часто ивент задевает один предмет, несколько предметов или целый раздел (в процентах,
	 * сумма первых двух — остальное приходится на раздел). Выбор каждый раз случайный.
	 */
	private static final int COVERAGE_ONE_PERCENT = 40;
	private static final int COVERAGE_FEW_PERCENT = 30;

	/** Что именно задевает ивент. */
	private enum Coverage {
		/** Один предмет. */
		ONE,
		/** Несколько предметов (от двух до «максимум предметов» из настроек). */
		FEW,
		/** Целый раздел магазина. */
		CATEGORY
	}

	/** Описание активного ивента. На сервере — из сейва, на клиенте — из пакета. */
	private static volatile MarketEvent current;

	/**
	 * Номер изменения описания. По нему видно, что ивент сменился: кэш множителей сбрасывается,
	 * а сервер понимает, что клиенту пора отправить новое описание. Номер, а не хэш описания, —
	 * чтобы два разных ивента не могли случайно совпасть.
	 */
	private static final AtomicLong REVISION = new AtomicLong();

	/**
	 * Кэш множителей предметов на текущий ивент. Клиент и сервер в одиночной игре делят статику,
	 * поэтому карта потокобезопасная. Чистится при смене ивента.
	 */
	private static final Map<Item, Double> CACHE = new ConcurrentHashMap<>();

	private EventService() {
		// Класс только со статическими методами.
	}

	// --- Общая часть: одинаково на сервере и на клиенте ---

	/** Множитель цены для предмета от активного ивента (1.0, если ивента нет). */
	public static double multiplier(Item item) {
		MarketEvent event = current;

		if (event == null) {
			return 1.0;
		}

		Double cached = CACHE.get(item);

		if (cached != null) {
			return cached;
		}

		double value = event.multiplierFor(item);
		CACHE.put(item, value);
		return value;
	}

	/** Активный ивент, если он сейчас идёт. */
	public static Optional<MarketEvent> current() {
		return Optional.ofNullable(current);
	}

	/** Номер изменения описания ивента: 0 — ивента не было ни разу. */
	public static long revision() {
		return REVISION.get();
	}

	/** Клиент получил описание ивента с сервера. */
	public static void applyFromServer(MarketEvent event) {
		apply(event);
	}

	/** Ивент закончился (или клиент вышел из мира) — цены снова обычные. */
	public static void clearCurrent() {
		apply(null);
	}

	/** Кладёт новое описание. Если оно то же самое — ничего не делает (и кэш не сбрасывает). */
	private static void apply(MarketEvent event) {
		if (Objects.equals(current, event)) {
			return;
		}

		current = event;
		CACHE.clear();
		REVISION.incrementAndGet();
	}

	// --- Серверная часть: выбор ивента ---

	/**
	 * Бросок ивента целиком: название, цель, множитель и длительность. Все числа — из настроек
	 * ({@code data/marketplace/economy.json}), ничего не зашито в код.
	 *
	 * @return описание ивента или пусто, если целей не нашлось (пустой прайс — тогда ивент не начинаем)
	 */
	public static Optional<MarketEvent> roll(RandomSource random) {
		return roll(MarketEvents.Name.random(random), random);
	}

	/**
	 * Ивент с заданным названием (ручной запуск командой) — цель, множитель и длительность
	 * всё равно случайные.
	 */
	public static Optional<MarketEvent> roll(MarketEvents.Name name, RandomSource random) {
		double multiplier = rollMultiplier(name, random);
		long duration = rollDuration(random);
		Coverage coverage = rollCoverage(random);

		switch (coverage) {
			case ONE -> {
				return target(random, 1).map(targets ->
						MarketEvent.ofItems(name, targets, multiplier, duration));
			}
			case FEW -> {
				int max = Math.max(1, EconomyConfig.eventMaxItems());
				int count = max <= 1 ? 1 : 2 + random.nextInt(max - 1);
				return target(random, count).map(targets ->
						MarketEvent.ofItems(name, targets, multiplier, duration));
			}
			default -> {
				return Optional.of(MarketEvent.ofCategory(name, rollCategory(random), multiplier, duration));
			}
		}
	}

	// --- Серверная часть: когда ивент идёт ---

	/** Подписка на тик сервера: ивенты идут по активному времени мира (см. {@link #tick}). */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(EventService::tick);
	}

	/**
	 * Один тик сервера. Ивенты живут по «активному времени» — оно копится только пока в мире
	 * есть игроки, поэтому без игроков идущий ивент замирает, а новый не начинается.
	 *
	 * <p>Порядок:
	 * <ol>
	 *     <li>описание ивента берём из сейва мира (после перезапуска сервера цены сразу считаются
	 *     по нему);</li>
	 *     <li>если в мире никого нет — выходим: ни времени, ни новых ивентов;</li>
	 *     <li>тикает идущий ивент, начинается запланированный;</li>
	 *     <li>приходит слух, если он выпал;</li>
	 *     <li>раз в игровой день делается бросок: случится ивент или нет.</li>
	 * </ol>
	 */
	private static void tick(MinecraftServer server) {
		if (server.overworld() == null) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(server);
		// Сейв мира — источник истины: цена считается по нему, а не по тому, что осталось в памяти.
		apply(state.activeEvent());

		if (server.getPlayerList().getPlayers().isEmpty()) {
			// Пауза: без игроков ивент не тикает и новый не выпадает — время мира для него стоит.
			return;
		}

		state.addActiveTick();
		long now = state.activeTicks();

		if (state.activeEvent() != null) {
			// Одновременно идёт только один ивент: если в этот же день выпал следующий,
			// он подождёт запланированного времени и начнётся, когда освободится место.
			long left = state.eventRemaining() - 1L;

			if (left > 0L) {
				state.setEventRemaining(left);
			} else {
				finish(server, state);
			}
		} else if (state.pendingEvent() != null && now >= state.eventPendingAt()) {
			begin(server, state);
		}

		tellRumor(server, state, now);
		rollDay(server, state, now);
	}

	/** Начинает запланированный ивент. */
	private static void begin(MinecraftServer server, MarketplaceState state) {
		MarketEvent event = state.pendingEvent();

		if (event == null) {
			return;
		}

		state.startEvent(event, event.durationTicks());
		apply(state.activeEvent());
		EventMessages.announce(server, event);
		MarketplaceMod.LOGGER.info("Ивент «{}» начался: цель {}, цена {}, длительность {} с активного времени",
				event.name().id(), EventMessages.targetText(event), event.percentText(),
				MarketEvents.seconds(event.durationTicks()));
	}

	/** Заканчивает идущий ивент. */
	private static void finish(MinecraftServer server, MarketplaceState state) {
		MarketEvent event = state.activeEvent();

		if (event == null) {
			return;
		}

		state.clearEvent();
		apply(state.activeEvent());
		EventMessages.announceEnd(server, event);
		MarketplaceMod.LOGGER.info("Ивент «{}» закончился: цены вернулись к обычным", event.name().id());
	}

	/**
	 * Рассказывает слух, если он выпал при броске дня и время пришло. Слух говорит о том же
	 * ивенте, который потом начнётся, — это не догадка, а часть его описания.
	 */
	private static void tellRumor(MinecraftServer server, MarketplaceState state, long now) {
		MarketEvent pending = state.pendingEvent();

		if (pending == null || state.eventRumorDone() || state.eventRumorAt() < 0L
				|| now < state.eventRumorAt()) {
			return;
		}

		state.setEventRumorDone();
		EventMessages.rumor(server, pending);
	}

	/**
	 * Бросок на игровой день: один раз за день и только когда в мире есть игроки. С шансом
	 * из настроек ивент выпадает и назначается на случайное время внутри игрового дня
	 * (24 000 тиков активного времени — те же 20 минут, что длится игровой день).
	 */
	private static void rollDay(MinecraftServer server, MarketplaceState state, long now) {
		long day = GameTime.day(server);

		if (state.eventRolledDay() == day) {
			return;
		}

		state.setEventRolledDay(day);
		RandomSource random = server.overworld().getRandom();

		if (random.nextDouble() >= EconomyConfig.eventDailyChance()) {
			MarketplaceMod.LOGGER.info("Ивент на игровой день {} не выпал (шанс {}%)",
					day, one(EconomyConfig.eventDailyChance() * 100.0));
			return;
		}

		Optional<MarketEvent> rolled = roll(random);

		if (rolled.isEmpty()) {
			MarketplaceMod.LOGGER.warn("Ивент на игровой день {} выпал, но цели не нашлись — пропускаю", day);
			return;
		}

		MarketEvent event = rolled.get();
		long at = now + random.nextInt((int) GameTime.TICKS_PER_DAY);
		long rumorAt = rollRumorAt(random, now, at);
		state.scheduleEvent(event, at, rumorAt);
		MarketplaceMod.LOGGER.info("Ивент на игровой день {} выпал: «{}», цель {}, цена {}, начало через {} с,"
						+ " длительность {} с, слух {}",
				day, event.name().id(), EventMessages.targetText(event), event.percentText(),
				MarketEvents.seconds(at - now), MarketEvents.seconds(event.durationTicks()),
				rumorAt < 0L ? "не выпал" : "через " + MarketEvents.seconds(at - rumorAt) + " с");
	}

	/**
	 * Когда рассказать слух: за случайное время (30–120 с по настройкам) до начала ивента.
	 * {@code -1} — слуха не будет: он не выпал по шансу или ивент начнётся раньше, чем успеет
	 * пройти задержка (тогда слух был бы уже после начала).
	 */
	private static long rollRumorAt(RandomSource random, long now, long at) {
		if (random.nextDouble() >= EconomyConfig.eventRumorChance()) {
			return -1L;
		}

		double min = Math.max(0.0, EconomyConfig.eventRumorMinSeconds());
		double max = Math.max(min, EconomyConfig.eventRumorMaxSeconds());
		long delay = MarketEvents.ticks(min + random.nextDouble() * (max - min));
		long moment = at - delay;
		return moment > now ? moment : -1L;
	}

	/**
	 * Запускает ивент вручную ({@code /market event start <id>}) — для проверки механики.
	 *
	 * @return запущенный ивент или пусто, если ивент уже идёт (второй одновременно не создаём)
	 */
	public static Optional<MarketEvent> startNow(MinecraftServer server, MarketEvents.Name name) {
		MarketplaceState state = MarketplaceEconomy.state(server);

		if (state.activeEvent() != null) {
			return Optional.empty();
		}

		Optional<MarketEvent> event = roll(name, server.overworld().getRandom());

		event.ifPresent(started -> {
			state.startEvent(started, started.durationTicks());
			apply(state.activeEvent());
			EventMessages.announce(server, started);
			MarketplaceMod.LOGGER.info("Ивент «{}» запущен командой: цель {}, цена {}, длительность {} с",
					started.name().id(), EventMessages.targetText(started), started.percentText(),
					MarketEvents.seconds(started.durationTicks()));
		});

		return event;
	}

	/**
	 * Останавливает активный ивент ({@code /market event stop}).
	 *
	 * @return остановленный ивент или пусто, если останавливать нечего
	 */
	public static Optional<MarketEvent> stop(MinecraftServer server) {
		MarketplaceState state = MarketplaceEconomy.state(server);
		MarketEvent event = state.activeEvent();

		if (event == null) {
			return Optional.empty();
		}

		state.clearEvent();
		apply(state.activeEvent());
		EventMessages.announceEnd(server, event);
		MarketplaceMod.LOGGER.info("Ивент «{}» остановлен командой", event.name().id());
		return Optional.of(event);
	}

	/** Сколько тиков активного времени осталось идущему ивенту (0 — ивента нет). */
	public static long remainingTicks(MinecraftServer server) {
		return MarketplaceEconomy.state(server).eventRemaining();
	}

	private static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** Направление и величина изменения цены: до ±{@code event_max_percent} из настроек. */
	private static double rollMultiplier(MarketEvents.Name name, RandomSource random) {
		double max = Math.max(0.0, EconomyConfig.eventMaxPercent());
		double magnitude = max * (MIN_MAGNITUDE_SHARE + random.nextDouble() * (1.0 - MIN_MAGNITUDE_SHARE));
		boolean rise = switch (name.direction()) {
			case RISE -> true;
			case FALL -> false;
			case ANY -> random.nextBoolean();
		};

		return rise ? 1.0 + magnitude : 1.0 - magnitude;
	}

	/** Длительность: от {@code event_min_seconds} до {@code event_max_seconds} из настроек. */
	private static long rollDuration(RandomSource random) {
		double min = Math.max(0.0, EconomyConfig.eventMinSeconds());
		double max = Math.max(min, EconomyConfig.eventMaxSeconds());
		return MarketEvents.ticks(min + random.nextDouble() * (max - min));
	}

	/** Что задевает ивент: один предмет, несколько или раздел. */
	private static Coverage rollCoverage(RandomSource random) {
		int roll = random.nextInt(100);

		if (roll < COVERAGE_ONE_PERCENT) {
			return Coverage.ONE;
		}

		return roll < COVERAGE_ONE_PERCENT + COVERAGE_FEW_PERCENT ? Coverage.FEW : Coverage.CATEGORY;
	}

	/** Случайные предметы из прайса (только те, что магазин продаёт). */
	private static Optional<List<String>> target(RandomSource random, int count) {
		List<Item> pool = ItemPrices.buyableItems();

		if (pool.isEmpty()) {
			return Optional.empty();
		}

		List<String> picked = new ArrayList<>(count);

		// Предметы не повторяем: ивент на «алмаз, алмаз, алмаз» выглядел бы как один предмет.
		while (picked.size() < count && picked.size() < pool.size()) {
			String id = itemId(pool.get(random.nextInt(pool.size())));

			if (!picked.contains(id)) {
				picked.add(id);
			}
		}

		return picked.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(picked));
	}

	/**
	 * Случайный раздел магазина. «Киты» и «Особые предметы» пропускаем: это не разделы предметов
	 * (наборы и книги своих зачарований), по предметам они не определяются
	 * ({@code ShopCatalog.categoryOf} их не возвращает) — ивент на них не задел бы ничего.
	 */
	private static ShopCatalog.Category rollCategory(RandomSource random) {
		List<ShopCatalog.Category> pool = Arrays.stream(ShopCatalog.Category.values())
				.filter(category -> category != ShopCatalog.Category.KITS
						&& category != ShopCatalog.Category.CUSTOM_KITS
						&& category != ShopCatalog.Category.AUCTION
						&& category != ShopCatalog.Category.SPECIAL)
				.toList();

		return pool.get(random.nextInt(pool.size()));
	}

	/** id предмета строкой: одинакова на сервере и на клиенте. */
	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
