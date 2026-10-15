package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Данные экономики, которые лежат прямо в сейве мира (файл {@code data/marketplace_economy.dat}).
 *
 * <p>Внутри:
 * <ul>
 *     <li>балансы игроков («UUID → сумма»);</li>
 *     <li>сколько стаков каждого предмета игрок уже сдал торговцу за текущий игровой день —
 *     чтобы работал дневной лимит закупки.</li>
 * </ul>
 * Игрок, которого нет в таблице, считается имеющим 0 долларов.
 */
public class MarketplaceState extends SavedData {
	/** Ключ в NBT, под которым лежит таблица балансов. */
	private static final String BALANCES_KEY = "balances";
	/** Ключ в NBT, под которым лежат дневные лимиты торговца. */
	private static final String LIMITS_KEY = "trader_limits";
	private static final String DAY_KEY = "day";
	private static final String SOLD_KEY = "sold";
	/** Ключ в NBT, под которым лежит история продаж (для динамики цен). */
	private static final String MARKET_KEY = "market";
	private static final String MARKET_AT_KEY = "at";

	private final Map<UUID, Double> balances = new HashMap<>();
	/** Игрок → (id предмета → сколько стаков уже сдано за день). */
	private final Map<UUID, Map<String, Double>> soldToday = new HashMap<>();
	/** Игрок → номер игрового дня, к которому относятся данные выше. */
	private final Map<UUID, Long> limitDay = new HashMap<>();
	/** id предмета → сколько стаков продано (рынок общий для всего мира). */
	private final Map<String, Double> marketSold = new HashMap<>();
	/** id предмета → когда это значение последний раз обновлялось. */
	private final Map<String, Long> marketSoldAt = new HashMap<>();

	public MarketplaceState() {
	}

	/** Переводит NBT в наш объект и обратно. */
	private static final Codec<MarketplaceState> CODEC = CompoundTag.CODEC.xmap(
			MarketplaceState::fromTag,
			MarketplaceState::toTag
	);

	/** Описание данных для игры: имя файла в сейве, конструктор и codec. */
	public static final SavedDataType<MarketplaceState> TYPE = new SavedDataType<>(
			MarketplaceMod.id("economy"),
			MarketplaceState::new,
			CODEC,
			null // datafixer не нужен: свои старые форматы мы разбираем сами
	);

	/** Возвращает баланс игрока. Если игрока нет в таблице — 0. */
	public double getBalance(UUID playerId) {
		return balances.getOrDefault(playerId, 0.0);
	}

	/** Записывает баланс игрока и помечает данные как изменённые, чтобы мир их сохранил. */
	public void setBalance(UUID playerId, double amount) {
		balances.put(playerId, MoneyFormat.round(amount));
		setDirty();
	}

	// --- Дневной лимит торговца ---

	/**
	 * Сбрасывает счётчик закупок, если начался новый игровой день.
	 * Вызывать перед проверкой лимита.
	 */
	public void refreshTraderDay(UUID playerId, long day) {
		Long known = limitDay.get(playerId);

		if (known != null && known == day) {
			return;
		}

		limitDay.put(playerId, day);
		soldToday.remove(playerId);
		setDirty();
	}

	/** Сколько стаков предмета игрок уже сдал торговцу сегодня. */
	public double soldStacks(UUID playerId, Item item) {
		Map<String, Double> sold = soldToday.get(playerId);

		if (sold == null) {
			return 0.0;
		}

		return sold.getOrDefault(itemId(item), 0.0);
	}

	/** Записывает, что игрок сдал ещё сколько-то стаков предмета. */
	public void addSoldStacks(UUID playerId, Item item, double stacks) {
		soldToday.computeIfAbsent(playerId, id -> new HashMap<>())
				.merge(itemId(item), stacks, Double::sum);
		setDirty();
	}

	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	// --- Рынок: история продаж для динамики цен ---

	/**
	 * Сколько стаков предмета продано на текущий момент. Цены восстанавливаются линейно:
	 * за один игровой день после продажи надбавка и скидка полностью исчезают.
	 */
	public double marketSold(String itemId, long now) {
		Double sold = marketSold.get(itemId);

		if (sold == null) {
			return 0.0;
		}

		long updatedAt = marketSoldAt.getOrDefault(itemId, now);
		long elapsed = Math.max(0L, now - updatedAt);

		if (elapsed >= GameTime.TICKS_PER_DAY) {
			return 0.0;
		}

		return sold * (1.0 - (double) elapsed / GameTime.TICKS_PER_DAY);
	}

	/** Записывает, что предмет продали ещё на столько-то стаков. */
	public void addMarketSale(Item item, double stacks, long now) {
		String id = itemId(item);
		marketSold.put(id, marketSold(id, now) + stacks);
		marketSoldAt.put(id, now);
		setDirty();
	}

	/** Текущее состояние рынка: что и сколько продано, с учётом восстановления. */
	public MarketPrices marketSnapshot(long now) {
		Map<Item, Double> sold = new HashMap<>();

		for (String id : marketSold.keySet()) {
			double stacks = marketSold(id, now);

			if (stacks <= 0.0001) {
				continue;
			}

			Optional<Item> item = resolveItem(id);
			item.ifPresent(value -> sold.put(value, stacks));
		}

		return MarketPrices.of(sold);
	}

	private static Optional<Item> resolveItem(String id) {
		try {
			return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
		} catch (RuntimeException exception) {
			// Битую запись в сейве пропускаем, но говорим об этом вслух.
			MarketplaceMod.LOGGER.warn("В истории продаж некорректный id предмета, запись пропущена: {}", id);
			return Optional.empty();
		}
	}

	// --- Превращение в NBT и обратно ---

	private static MarketplaceState fromTag(CompoundTag tag) {
		MarketplaceState state = new MarketplaceState();
		CompoundTag storedBalances = tag.getCompoundOrEmpty(BALANCES_KEY);

		for (String key : storedBalances.keySet()) {
			Optional<UUID> playerId = parseUuid(key);

			if (playerId.isEmpty()) {
				// Не падаем и не прячем проблему: пишем в лог, что именно не так.
				MarketplaceMod.LOGGER.warn("В сейве найден некорректный UUID игрока, запись пропущена: {}", key);
				continue;
			}

			state.balances.put(playerId.get(), MoneyFormat.round(storedBalances.getDoubleOr(key, 0.0)));
		}

		CompoundTag storedLimits = tag.getCompoundOrEmpty(LIMITS_KEY);

		for (String key : storedLimits.keySet()) {
			Optional<UUID> playerId = parseUuid(key);

			if (playerId.isEmpty()) {
				MarketplaceMod.LOGGER.warn("В сейве найден некорректный UUID игрока в лимитах, запись пропущена: {}", key);
				continue;
			}

			CompoundTag playerTag = storedLimits.getCompoundOrEmpty(key);
			state.limitDay.put(playerId.get(), playerTag.getLongOr(DAY_KEY, 0L));

			Map<String, Double> sold = new HashMap<>();
			CompoundTag soldTag = playerTag.getCompoundOrEmpty(SOLD_KEY);

			for (String itemKey : soldTag.keySet()) {
				sold.put(itemKey, soldTag.getDoubleOr(itemKey, 0.0));
			}

			state.soldToday.put(playerId.get(), sold);
		}

		CompoundTag storedMarket = tag.getCompoundOrEmpty(MARKET_KEY);

		for (String itemKey : storedMarket.keySet()) {
			CompoundTag entry = storedMarket.getCompoundOrEmpty(itemKey);
			state.marketSold.put(itemKey, entry.getDoubleOr(SOLD_KEY, 0.0));
			state.marketSoldAt.put(itemKey, entry.getLongOr(MARKET_AT_KEY, 0L));
		}

		return state;
	}

	private static CompoundTag toTag(MarketplaceState state) {
		CompoundTag tag = new CompoundTag();
		CompoundTag storedBalances = new CompoundTag();

		state.balances.forEach((playerId, amount) -> storedBalances.putDouble(playerId.toString(), amount));
		tag.put(BALANCES_KEY, storedBalances);

		CompoundTag storedLimits = new CompoundTag();

		for (Map.Entry<UUID, Map<String, Double>> entry : state.soldToday.entrySet()) {
			CompoundTag playerTag = new CompoundTag();
			playerTag.putLong(DAY_KEY, state.limitDay.getOrDefault(entry.getKey(), 0L));

			CompoundTag soldTag = new CompoundTag();
			entry.getValue().forEach(soldTag::putDouble);
			playerTag.put(SOLD_KEY, soldTag);

			storedLimits.put(entry.getKey().toString(), playerTag);
		}

		tag.put(LIMITS_KEY, storedLimits);

		CompoundTag storedMarket = new CompoundTag();

		for (Map.Entry<String, Double> entry : state.marketSold.entrySet()) {
			CompoundTag itemTag = new CompoundTag();
			itemTag.putDouble(SOLD_KEY, entry.getValue());
			itemTag.putLong(MARKET_AT_KEY, state.marketSoldAt.getOrDefault(entry.getKey(), 0L));
			storedMarket.put(entry.getKey(), itemTag);
		}

		tag.put(MARKET_KEY, storedMarket);
		return tag;
	}

	private static Optional<UUID> parseUuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
