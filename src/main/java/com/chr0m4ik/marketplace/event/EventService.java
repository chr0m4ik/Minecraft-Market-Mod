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

public final class EventService {

	private static final double MIN_MAGNITUDE_SHARE = 0.25;

	private static final int COVERAGE_ONE_PERCENT = 40;
	private static final int COVERAGE_FEW_PERCENT = 30;

	private enum Coverage {

		ONE,

		FEW,

		CATEGORY
	}

	private static volatile MarketEvent current;

	private static final AtomicLong REVISION = new AtomicLong();

	private static final Map<Item, Double> CACHE = new ConcurrentHashMap<>();

	private EventService() {
		// Класс только со статическими методами.
	}

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

	public static Optional<MarketEvent> current() {
		return Optional.ofNullable(current);
	}

	public static long revision() {
		return REVISION.get();
	}

	public static void applyFromServer(MarketEvent event) {
		apply(event);
	}

	public static void clearCurrent() {
		apply(null);
	}

	private static void apply(MarketEvent event) {
		if (Objects.equals(current, event)) {
			return;
		}

		current = event;
		CACHE.clear();
		REVISION.incrementAndGet();
	}

	// --- Серверная часть: выбор ивента ---

	public static Optional<MarketEvent> roll(RandomSource random) {
		return roll(MarketEvents.Name.random(random), random);
	}

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

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(EventService::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.overworld() == null) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(server);

		apply(state.activeEvent());

		if (server.getPlayerList().getPlayers().isEmpty()) {

			return;
		}

		state.addActiveTick();
		long now = state.activeTicks();

		if (state.activeEvent() != null) {

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

	private static void begin(MinecraftServer server, MarketplaceState state) {
		MarketEvent event = state.pendingEvent();

		if (event == null) {
			return;
		}

		state.startEvent(event, event.durationTicks());
		apply(state.activeEvent());
		EventMessages.announce(server, event);
		// MarketplaceMod.LOGGER.info("Ивент «{}» начался: цель {}, цена {}, длительность {} с активного времени",
				// event.name().id(), EventMessages.targetText(event), event.percentText(),
				// MarketEvents.seconds(event.durationTicks()));
	}

	private static void finish(MinecraftServer server, MarketplaceState state) {
		MarketEvent event = state.activeEvent();

		if (event == null) {
			return;
		}

		state.clearEvent();
		apply(state.activeEvent());
		EventMessages.announceEnd(server, event);
		// MarketplaceMod.LOGGER.info("Ивент «{}» закончился: цены вернулись к обычным", event.name().id());
	}

	private static void tellRumor(MinecraftServer server, MarketplaceState state, long now) {
		MarketEvent pending = state.pendingEvent();

		if (pending == null || state.eventRumorDone() || state.eventRumorAt() < 0L
				|| now < state.eventRumorAt()) {
			return;
		}

		state.setEventRumorDone();
		EventMessages.rumor(server, pending);
	}

	private static void rollDay(MinecraftServer server, MarketplaceState state, long now) {
		long day = GameTime.day(server);

		if (state.eventRolledDay() == day) {
			return;
		}

		state.setEventRolledDay(day);
		RandomSource random = server.overworld().getRandom();

		if (random.nextDouble() >= EconomyConfig.eventDailyChance()) {
			// MarketplaceMod.LOGGER.info("Ивент на игровой день {} не выпал (шанс {}%)",
					// day, one(EconomyConfig.eventDailyChance() * 100.0));
			return;
		}

		Optional<MarketEvent> rolled = roll(random);

		if (rolled.isEmpty()) {
			// MarketplaceMod.LOGGER.warn("Ивент на игровой день {} выпал, но цели не нашлись — пропускаю", day);
			return;
		}

		MarketEvent event = rolled.get();
		long at = now + random.nextInt((int) GameTime.TICKS_PER_DAY);
		long rumorAt = rollRumorAt(random, now, at);
		state.scheduleEvent(event, at, rumorAt);
		// MarketplaceMod.LOGGER.info("Ивент на игровой день {} выпал: «{}», цель {}, цена {}, начало через {} с,"
						// + " длительность {} с, слух {}",
				// day, event.name().id(), EventMessages.targetText(event), event.percentText(),
				// MarketEvents.seconds(at - now), MarketEvents.seconds(event.durationTicks()),
				// rumorAt < 0L ? "не выпал" : "через " + MarketEvents.seconds(at - rumorAt) + " с");
	}

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
			// MarketplaceMod.LOGGER.info("Ивент «{}» запущен командой: цель {}, цена {}, длительность {} с",
					// started.name().id(), EventMessages.targetText(started), started.percentText(),
					// MarketEvents.seconds(started.durationTicks()));
		});

		return event;
	}

	public static Optional<MarketEvent> stop(MinecraftServer server) {
		MarketplaceState state = MarketplaceEconomy.state(server);
		MarketEvent event = state.activeEvent();

		if (event == null) {
			return Optional.empty();
		}

		state.clearEvent();
		apply(state.activeEvent());
		EventMessages.announceEnd(server, event);
		// MarketplaceMod.LOGGER.info("Ивент «{}» остановлен командой", event.name().id());
		return Optional.of(event);
	}

	public static long remainingTicks(MinecraftServer server) {
		return MarketplaceEconomy.state(server).eventRemaining();
	}

	private static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

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

	private static long rollDuration(RandomSource random) {
		double min = Math.max(0.0, EconomyConfig.eventMinSeconds());
		double max = Math.max(min, EconomyConfig.eventMaxSeconds());
		return MarketEvents.ticks(min + random.nextDouble() * (max - min));
	}

	private static Coverage rollCoverage(RandomSource random) {
		int roll = random.nextInt(100);

		if (roll < COVERAGE_ONE_PERCENT) {
			return Coverage.ONE;
		}

		return roll < COVERAGE_ONE_PERCENT + COVERAGE_FEW_PERCENT ? Coverage.FEW : Coverage.CATEGORY;
	}

	private static Optional<List<String>> target(RandomSource random, int count) {
		List<Item> pool = ItemPrices.buyableItems();

		if (pool.isEmpty()) {
			return Optional.empty();
		}

		List<String> picked = new ArrayList<>(count);

		while (picked.size() < count && picked.size() < pool.size()) {
			String id = itemId(pool.get(random.nextInt(pool.size())));

			if (!picked.contains(id)) {
				picked.add(id);
			}
		}

		return picked.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(picked));
	}

	private static ShopCatalog.Category rollCategory(RandomSource random) {
		List<ShopCatalog.Category> pool = Arrays.stream(ShopCatalog.Category.values())
				.filter(category -> category != ShopCatalog.Category.KITS
						&& category != ShopCatalog.Category.CUSTOM_KITS
						&& category != ShopCatalog.Category.AUCTION
						&& category != ShopCatalog.Category.SPECIAL)
				.toList();

		return pool.get(random.nextInt(pool.size()));
	}

	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
