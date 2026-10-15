package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.market.MarketOrder;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	/** Ключ в NBT, под которым лежит история продаж торговцу (для его динамики цен). */
	private static final String TRADER_SALES_KEY = "trader_sales";
	private static final String SOLD_AT_KEY = "at";
	/** Ключи для ордеров рынка. */
	private static final String ORDERS_KEY = "orders";
	private static final String ORDER_ITEM_KEY = "item";
	private static final String ORDER_MARKUP_KEY = "markup";
	private static final String ORDER_READY_KEY = "ready";
	private static final String BOUGHT_SLOTS_KEY = "bought_slots";
	private static final String PENDING_SALES_KEY = "pending_sales";
	private static final String PENDING_COUNT_KEY = "count";
	private static final String PENDING_TOTAL_KEY = "total";

	private final Map<UUID, Double> balances = new HashMap<>();
	/** Игрок → (id предмета → сколько стаков уже сдано за день). */
	private final Map<UUID, Map<String, Double>> soldToday = new HashMap<>();
	/** Игрок → номер игрового дня, к которому относятся данные выше. */
	private final Map<UUID, Long> limitDay = new HashMap<>();
	/** id предмета → сколько стаков куплено торговцем (у торговца своя динамика цен). */
	private final Map<String, Double> traderSold = new HashMap<>();
	/** id предмета → когда это значение последний раз обновлялось. */
	private final Map<String, Long> traderSoldAt = new HashMap<>();
	/** Игрок → (номер ячейки рынка → ордер). */
	private final Map<UUID, Map<Integer, MarketOrder>> orders = new HashMap<>();
	/** Игрок → сколько слотов рынка куплено сверх бесплатных. */
	private final Map<UUID, Integer> boughtSlots = new HashMap<>();
	/** Игрок → [сколько ордеров продалось без него, на какую сумму] — показать при входе. */
	private final Map<UUID, double[]> pendingSales = new HashMap<>();

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

	// --- Торговец: история закупок для его динамики цен ---

	/**
	 * Сколько стаков предмета торговец купил на текущий момент. Его цены восстанавливаются
	 * линейно: за один игровой день после продажи надбавка и уценка полностью исчезают.
	 */
	public double traderSold(String itemId, long now) {
		Double sold = traderSold.get(itemId);

		if (sold == null) {
			return 0.0;
		}

		long updatedAt = traderSoldAt.getOrDefault(itemId, now);
		long elapsed = Math.max(0L, now - updatedAt);

		if (elapsed >= GameTime.TICKS_PER_DAY) {
			return 0.0;
		}

		return sold * (1.0 - (double) elapsed / GameTime.TICKS_PER_DAY);
	}

	/** Записывает, что торговец купил предмет ещё на столько-то стаков. */
	public void addTraderSale(Item item, double stacks, long now) {
		String id = itemId(item);
		traderSold.put(id, traderSold(id, now) + stacks);
		traderSoldAt.put(id, now);
		setDirty();
	}

	/** Текущие цены торговца: что и сколько он купил, с учётом восстановления. */
	public TraderPrices traderSnapshot(long now) {
		Map<Item, Double> sold = new HashMap<>();

		for (String id : traderSold.keySet()) {
			double stacks = traderSold(id, now);

			if (stacks <= 0.0001) {
				continue;
			}

			Optional<Item> item = resolveItem(id);
			item.ifPresent(value -> sold.put(value, stacks));
		}

		return TraderPrices.of(sold);
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

	// --- Рынок: ордера на продажу ---

	/** Ордер в этой ячейке (или null, если ячейка свободна). */
	public MarketOrder order(UUID playerId, int slot) {
		Map<Integer, MarketOrder> playerOrders = orders.get(playerId);
		return playerOrders == null ? null : playerOrders.get(slot);
	}

	/** Все ячейки с ордерами. */
	public Map<Integer, MarketOrder> orders(UUID playerId) {
		return orders.getOrDefault(playerId, Map.of());
	}

	/** Игроки, у которых есть хоть один ордер (нужно, чтобы обрабатывать продажи). */
	public Set<UUID> playersWithOrders() {
		return Set.copyOf(orders.keySet());
	}

	public void setOrder(UUID playerId, int slot, MarketOrder order) {
		orders.computeIfAbsent(playerId, id -> new HashMap<>()).put(slot, order);
		setDirty();
	}

	/** Убирает ордер из ячейки и возвращает его (или null, если его там не было). */
	public MarketOrder removeOrder(UUID playerId, int slot) {
		Map<Integer, MarketOrder> playerOrders = orders.get(playerId);

		if (playerOrders == null) {
			return null;
		}

		MarketOrder removed = playerOrders.remove(slot);

		if (removed != null) {
			setDirty();
		}

		return removed;
	}

	/** Сколько слотов рынка игрок купил сверх бесплатных. */
	public int boughtSlots(UUID playerId) {
		return boughtSlots.getOrDefault(playerId, 0);
	}

	public void setBoughtSlots(UUID playerId, int count) {
		boughtSlots.put(playerId, count);
		setDirty();
	}

	/** Запомнить продажу, которая случилась, пока игрока не было в игре. */
	public void addPendingSale(UUID playerId, double amount) {
		double[] pending = pendingSales.computeIfAbsent(playerId, id -> new double[2]);
		pending[0] += 1.0;
		pending[1] += amount;
		setDirty();
	}

	/** Забрать накопленные продажи (при входе игрока): [количество, сумма]. */
	public double[] takePendingSales(UUID playerId) {
		double[] pending = pendingSales.remove(playerId);

		if (pending != null) {
			setDirty();
		}

		return pending;
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

		CompoundTag storedMarket = tag.getCompoundOrEmpty(TRADER_SALES_KEY);

		for (String itemKey : storedMarket.keySet()) {
			CompoundTag entry = storedMarket.getCompoundOrEmpty(itemKey);
			state.traderSold.put(itemKey, entry.getDoubleOr(SOLD_KEY, 0.0));
			state.traderSoldAt.put(itemKey, entry.getLongOr(SOLD_AT_KEY, 0L));
		}

		// Ордера рынка: ключ — номер ячейки, значение — предмет, наценка и время готовности.
		CompoundTag storedOrders = tag.getCompoundOrEmpty(ORDERS_KEY);

		for (String playerKey : storedOrders.keySet()) {
			Optional<UUID> playerId = parseUuid(playerKey);

			if (playerId.isEmpty()) {
				MarketplaceMod.LOGGER.warn("В сейве некорректный UUID игрока в ордерах, запись пропущена: {}", playerKey);
				continue;
			}

			CompoundTag playerOrders = storedOrders.getCompoundOrEmpty(playerKey);
			Map<Integer, MarketOrder> parsed = new HashMap<>();

			for (String slotKey : playerOrders.keySet()) {
				int slot = parseSlot(slotKey, playerKey);
				CompoundTag entry = playerOrders.getCompoundOrEmpty(slotKey);
				Optional<ItemStack> item = ItemStack.CODEC
						.parse(NbtOps.INSTANCE, entry.getCompoundOrEmpty(ORDER_ITEM_KEY)).result();

				if (slot < 0) {
					continue;
				}

				if (item.isEmpty() || item.get().isEmpty()) {
					MarketplaceMod.LOGGER.warn("В ордере игрока {} повреждён предмет, запись пропущена", playerKey);
					continue;
				}

				parsed.put(slot, new MarketOrder(item.get(),
						entry.getDoubleOr(ORDER_MARKUP_KEY, 1.0), entry.getLongOr(ORDER_READY_KEY, 0L)));
			}

			state.orders.put(playerId.get(), parsed);
		}

		CompoundTag storedSlots = tag.getCompoundOrEmpty(BOUGHT_SLOTS_KEY);

		for (String key : storedSlots.keySet()) {
			Optional<UUID> playerId = parseUuid(key);
			playerId.ifPresent(id -> state.boughtSlots.put(id, Math.max(0, storedSlots.getIntOr(key, 0))));
		}

		CompoundTag storedPending = tag.getCompoundOrEmpty(PENDING_SALES_KEY);

		for (String key : storedPending.keySet()) {
			Optional<UUID> playerId = parseUuid(key);

			if (playerId.isEmpty()) {
				continue;
			}

			CompoundTag entry = storedPending.getCompoundOrEmpty(key);
			state.pendingSales.put(playerId.get(), new double[] {
					entry.getDoubleOr(PENDING_COUNT_KEY, 0.0), entry.getDoubleOr(PENDING_TOTAL_KEY, 0.0)});
		}

		return state;
	}

	private static int parseSlot(String slotKey, String playerKey) {
		try {
			return Integer.parseInt(slotKey);
		} catch (NumberFormatException exception) {
			MarketplaceMod.LOGGER.warn("В ордерах игрока {} некорректный номер ячейки, запись пропущена: {}",
					playerKey, slotKey);
			return -1;
		}
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

		for (Map.Entry<String, Double> entry : state.traderSold.entrySet()) {
			CompoundTag itemTag = new CompoundTag();
			itemTag.putDouble(SOLD_KEY, entry.getValue());
			itemTag.putLong(SOLD_AT_KEY, state.traderSoldAt.getOrDefault(entry.getKey(), 0L));
			storedMarket.put(entry.getKey(), itemTag);
		}

		tag.put(TRADER_SALES_KEY, storedMarket);

		CompoundTag storedOrders = new CompoundTag();

		state.orders.forEach((playerId, playerOrders) -> {
			CompoundTag playerTag = new CompoundTag();

			playerOrders.forEach((slot, order) -> {
				CompoundTag entry = new CompoundTag();

				ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, order.stack()).result()
						.ifPresent(encoded -> entry.put(ORDER_ITEM_KEY, encoded));

				entry.putDouble(ORDER_MARKUP_KEY, order.markup());
				entry.putLong(ORDER_READY_KEY, order.readyAt());
				playerTag.put(String.valueOf(slot), entry);
			});

			storedOrders.put(playerId.toString(), playerTag);
		});

		tag.put(ORDERS_KEY, storedOrders);

		CompoundTag storedSlots = new CompoundTag();
		state.boughtSlots.forEach((playerId, count) -> storedSlots.putInt(playerId.toString(), count));
		tag.put(BOUGHT_SLOTS_KEY, storedSlots);

		CompoundTag storedPending = new CompoundTag();

		state.pendingSales.forEach((playerId, pending) -> {
			CompoundTag entry = new CompoundTag();
			entry.putDouble(PENDING_COUNT_KEY, pending[0]);
			entry.putDouble(PENDING_TOTAL_KEY, pending[1]);
			storedPending.put(playerId.toString(), entry);
		});

		tag.put(PENDING_SALES_KEY, storedPending);
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
