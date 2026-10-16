package com.chr0m4ik.marketplace.event;

import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.core.registries.BuiltInRegistries;
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
		MarketEvents.Name name = MarketEvents.Name.random(random);
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
	 * Случайный раздел магазина. «Киты» пропускаем: это не раздел предметов, а наборы, и по
	 * предметам он не определяется ({@code ShopCatalog.categoryOf} его не возвращает) — ивент
	 * на нём не задел бы ничего.
	 */
	private static ShopCatalog.Category rollCategory(RandomSource random) {
		List<ShopCatalog.Category> pool = Arrays.stream(ShopCatalog.Category.values())
				.filter(category -> category != ShopCatalog.Category.KITS)
				.toList();

		return pool.get(random.nextInt(pool.size()));
	}

	/** id предмета строкой: одинакова на сервере и на клиенте. */
	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
