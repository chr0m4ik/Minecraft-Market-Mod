package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.event.MarketEvent;
import com.chr0m4ik.marketplace.market.CustomKit;
import com.chr0m4ik.marketplace.market.MarketOrder;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
	/** Цена лота (сколько за него платят) и признак «продаёт виртуальный рынок». */
	private static final String ORDER_PRICE_KEY = "price";
	private static final String ORDER_AUTO_KEY = "auto";
	private static final String ORDER_SELLER_KEY = "seller";
	/**
	 * Зафиксированная при выставлении выплата ордера. В старых сейвах ключа нет — тогда
	 * выплата считается по-старому от текущей цены, чтобы сейвы читались без миграции.
	 */
	private static final String ORDER_PAYOUT_KEY = "payout";
	private static final String BOUGHT_SLOTS_KEY = "bought_slots";
	private static final String PENDING_SALES_KEY = "pending_sales";
	private static final String PENDING_COUNT_KEY = "count";
	private static final String PENDING_TOTAL_KEY = "total";
	/** Ключи для неподтверждённых предметов и выбранной наценки. */
	private static final String STAGING_KEY = "staging";
	private static final String STAGING_ITEM_KEY = "item";
	private static final String MARKUP_KEY = "markup";
	/** Ключи заказов торговца: номер игрового дня и сами заказы (по три штуки на игрока). */
	private static final String QUESTS_KEY = "quests";
	private static final String QUEST_DAY_KEY = "day";
	private static final String QUEST_ITEM_KEY = "item";
	private static final String QUEST_COUNT_KEY = "count";
	private static final String QUEST_BONUS_KEY = "bonus";
	private static final String QUEST_DONE_KEY = "done";
	/** Ключи срочного заказа: текущий заказ и игровой день последнего запуска. */
	private static final String URGENT_KEY = "urgent";
	private static final String URGENT_ITEM_KEY = "item";
	private static final String URGENT_COUNT_KEY = "count";
	private static final String URGENT_REWARD_KEY = "reward";
	private static final String URGENT_LEFT_KEY = "left";
	private static final String URGENT_LAST_DAY_KEY = "last_day";
	/** Ключи своих сетов игроков: название (ключ записи), автор, способ выдачи и предметы. */
	private static final String CUSTOM_KITS_KEY = "custom_kits";
	private static final String CUSTOM_KIT_AUTHOR_KEY = "author";
	private static final String CUSTOM_KIT_AUTHOR_NAME_KEY = "author_name";
	private static final String CUSTOM_KIT_SHULKERS_KEY = "shulkers";
	private static final String CUSTOM_KIT_ITEMS_KEY = "items";
	private static final String CUSTOM_KIT_ITEM_KEY = "item";
	private static final String CUSTOM_KIT_SOURCE_KEY = "source";
	/** Ключ купленных виртуальных эндер-сундуков: UUID игрока → признак покупки. */
	private static final String ENDER_CHEST_KEY = "ender_chest";
	/** Ключи крупных ивентов: активное время мира, идущий ивент и запланированный. */
	private static final String EVENT_TIME_KEY = "event_active_time";
	private static final String EVENT_ACTIVE_KEY = "event_active";
	private static final String EVENT_REMAINING_KEY = "event_remaining";
	private static final String EVENT_PENDING_KEY = "event_pending";
	private static final String EVENT_PENDING_AT_KEY = "event_pending_at";
	private static final String EVENT_RUMOR_AT_KEY = "event_rumor_at";
	private static final String EVENT_RUMOR_DONE_KEY = "event_rumor_done";
	private static final String EVENT_ROLLED_DAY_KEY = "event_rolled_day";

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
	/** Игрок → (номер ячейки → предмет, который положили, но ещё не подтвердили). */
	private final Map<UUID, Map<Integer, ItemStack>> staging = new HashMap<>();
	/** Игрок → выбранная наценка для новых ордеров. */
	private final Map<UUID, Double> markup = new HashMap<>();
	/** Игрок → заказы торговца на текущий игровой день. */
	private final Map<UUID, List<TraderQuest>> quests = new HashMap<>();
	/** Игрок → игровой день, на который выданы заказы. */
	private final Map<UUID, Long> questDay = new HashMap<>();
	/** Идущий срочный заказ ({@code null} — заказа нет). */
	private UrgentQuest urgentQuest;
	/** Игровой день, в который последний раз запускался срочный заказ ({@link Long#MIN_VALUE} — не было). */
	private long urgentLastDay = Long.MIN_VALUE;
	/** Свои сеты игроков: название → сет. Порядок создания сохраняется для списка в магазине. */
	private final Map<String, CustomKit> customKits = new LinkedHashMap<>();
	/** Игроки, купившие виртуальный эндер-сундук (доступ к команде /ec). */
	private final Set<UUID> enderChestOwners = new HashSet<>();

	/**
	 * Активное время мира: тики, когда в игре был хотя бы один игрок. Ивенты считают время по нему,
	 * а не по игровому времени, поэтому без игроков они замирают (см. {@code event/EventService}).
	 * Значение копится в сейве, значит перезапуск сервера ивент не «съедает».
	 */
	private long activeTicks;

	/** Идущий крупный ивент (null — ивента нет) и сколько тиков активного времени ему осталось. */
	private MarketEvent activeEvent;
	private long eventRemaining;

	/** Ивент, выпавший на текущий игровой день: начнётся, когда активное время дойдёт до {@link #eventPendingAt}. */
	private MarketEvent pendingEvent;
	private long eventPendingAt;
	/** Когда рассказать слух (активное время) и рассказали ли его уже. −1 — слуха нет. */
	private long eventRumorAt = -1L;
	private boolean eventRumorDone;
	/** Номер игрового дня, за который бросок ивента уже сделан. Long.MIN_VALUE — ещё ни одного. */
	private long eventRolledDay = Long.MIN_VALUE;

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

	/**
	 * Реестры мира — нужны, чтобы читать и писать предметы в сейв. Зачарования лежат
	 * в динамическом реестре, поэтому с обычным {@link NbtOps} зачарованный предмет
	 * вообще не кодируется: ордер молча терял бы предмет. Значение выставляет
	 * {@link MarketplaceEconomy#state} до первого чтения сейва.
	 */
	private static HolderLookup.Provider registries;

	/** Отдаём реестры мира (вызывается перед работой с сейвом). */
	public static void setRegistries(HolderLookup.Provider value) {
		registries = value;
	}

	/** Операции для NBT: с доступом к реестрам, если они уже известны. */
	private static DynamicOps<Tag> ops() {
		return registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
	}

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

	// --- Рынок: ячейки, которые игрок заполнил, но ещё не подтвердил ---

	public Map<Integer, ItemStack> staging(UUID playerId) {
		return staging.getOrDefault(playerId, Map.of());
	}

	public void setStaging(UUID playerId, Map<Integer, ItemStack> items) {
		staging.put(playerId, new HashMap<>(items));
		setDirty();
	}

	/** Выбранная игроком наценка для новых ордеров. */
	public double markup(UUID playerId) {
		return markup.getOrDefault(playerId, 1.0);
	}

	public void setMarkup(UUID playerId, double value) {
		markup.put(playerId, value);
		setDirty();
	}

	// --- Заказы торговца ---

	/** Заказы игрока на текущий игровой день (пусто — заказов ещё не выдавали). */
	public List<TraderQuest> quests(UUID playerId) {
		return quests.getOrDefault(playerId, List.of());
	}

	public void setQuests(UUID playerId, List<TraderQuest> list) {
		quests.put(playerId, List.copyOf(list));
		setDirty();
	}

	/** Игровой день, на который выданы заказы ({@link Long#MIN_VALUE} — не выдавали ни разу). */
	public long questDay(UUID playerId) {
		return questDay.getOrDefault(playerId, Long.MIN_VALUE);
	}

	public void setQuestDay(UUID playerId, long day) {
		questDay.put(playerId, day);
		setDirty();
	}

	// --- Срочный заказ ---

	/** Идущий срочный заказ или {@code null}. Заказ один на мир: сдаёт его только первый игрок. */
	public UrgentQuest urgentQuest() {
		return this.urgentQuest;
	}

	public void setUrgentQuest(UrgentQuest quest) {
		this.urgentQuest = quest;
		setDirty();
	}

	public void clearUrgentQuest() {
		if (this.urgentQuest != null) {
			this.urgentQuest = null;
			setDirty();
		}
	}

	/** Игровой день последнего запуска срочного заказа. */
	public long urgentLastDay() {
		return this.urgentLastDay;
	}

	public void setUrgentLastDay(long day) {
		this.urgentLastDay = day;
		setDirty();
	}

	// --- Свои сеты игроков ---

	/** Свои сеты мира в порядке создания. */
	public Map<String, CustomKit> customKits() {
		return Collections.unmodifiableMap(this.customKits);
	}

	/** Добавляет или заменяет свой сет (ключ — название). */
	public void putCustomKit(CustomKit kit) {
		this.customKits.put(kit.name(), kit);
		setDirty();
	}

	/** Удаляет свой сет по названию. */
	public CustomKit removeCustomKit(String name) {
		CustomKit removed = this.customKits.remove(name);

		if (removed != null) {
			setDirty();
		}

		return removed;
	}

	// --- Виртуальный эндер-сундук ---

	/** Куплена ли система этому игроку. */
	public boolean ownsEnderChest(UUID playerId) {
		return this.enderChestOwners.contains(playerId);
	}

	/** Запоминает покупку виртуального эндер-сундука. Повторный вызов ничего не меняет. */
	public void grantEnderChest(UUID playerId) {
		if (this.enderChestOwners.add(playerId)) {
			setDirty();
		}
	}

	// --- Крупные ивенты ---

	/** Активное время мира в тиках: копится, только пока в игре есть игроки. */
	public long activeTicks() {
		return this.activeTicks;
	}

	/** Ещё один тик с игроками в мире. */
	public void addActiveTick() {
		this.activeTicks++;
		setDirty();
	}

	/** Идущий ивент или {@code null}, если ивента нет. */
	public MarketEvent activeEvent() {
		return this.activeEvent;
	}

	/** Сколько тиков активного времени осталось идущему ивенту. */
	public long eventRemaining() {
		return this.eventRemaining;
	}

	/** Начинает ивент: он становится идущим, запланированный и слух убираются. */
	public void startEvent(MarketEvent event, long remainingTicks) {
		this.activeEvent = event;
		this.eventRemaining = Math.max(0L, remainingTicks);
		clearPending();
		setDirty();
	}

	/** Сколько ивенту осталось (пересчитывается раз в тик активного времени). */
	public void setEventRemaining(long ticks) {
		this.eventRemaining = Math.max(0L, ticks);
		setDirty();
	}

	/** Ивент закончился. */
	public void clearEvent() {
		this.activeEvent = null;
		this.eventRemaining = 0L;
		setDirty();
	}

	/** Ивент, выпавший на текущий игровой день, но ещё не начавшийся (или {@code null}). */
	public MarketEvent pendingEvent() {
		return this.pendingEvent;
	}

	/** Активное время, когда начнётся запланированный ивент. */
	public long eventPendingAt() {
		return this.eventPendingAt;
	}

	/** Планирует ивент на будущее. {@code rumorAt < 0} — слуха не будет. */
	public void scheduleEvent(MarketEvent event, long at, long rumorAt) {
		this.pendingEvent = event;
		this.eventPendingAt = at;
		this.eventRumorAt = rumorAt;
		this.eventRumorDone = false;
		setDirty();
	}

	/** Убирает запланированный ивент и слух (ручной запуск, отмена). */
	public void clearPending() {
		this.pendingEvent = null;
		this.eventPendingAt = 0L;
		this.eventRumorAt = -1L;
		this.eventRumorDone = false;
		setDirty();
	}

	/** Когда рассказать слух, в активном времени. −1 — слуха нет. */
	public long eventRumorAt() {
		return this.eventRumorAt;
	}

	/** Рассказан ли уже слух про запланированный ивент. */
	public boolean eventRumorDone() {
		return this.eventRumorDone;
	}

	/** Помечает слух рассказанным — второй раз он не придёт. */
	public void setEventRumorDone() {
		this.eventRumorDone = true;
		setDirty();
	}

	/** Игровой день, за который бросок ивента уже сделан. */
	public long eventRolledDay() {
		return this.eventRolledDay;
	}

	/** Запоминает, что бросок ивента на этот игровой день уже был. */
	public void setEventRolledDay(long day) {
		this.eventRolledDay = day;
		setDirty();
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
						.parse(ops(), entry.getCompoundOrEmpty(ORDER_ITEM_KEY)).result();

				if (slot < 0) {
					continue;
				}

				if (item.isEmpty() || item.get().isEmpty()) {
					MarketplaceMod.LOGGER.warn("В ордере игрока {} повреждён предмет, запись пропущена", playerKey);
					continue;
				}

				double markup = entry.getDoubleOr(ORDER_MARKUP_KEY, 1.0);
				// Цена лота: в старых сейвах её нет — берём прежнюю выплату, иначе считаем от наценки.
				double price = entry.getDoubleOr(ORDER_PRICE_KEY, 0.0);

				if (price <= 0.0) {
					price = entry.getDoubleOr(ORDER_PAYOUT_KEY, 0.0);
				}

				if (price <= 0.0) {
					price = MoneyFormat.round(ItemPrices.priceOf(item.get()) * markup);
				}

				boolean auto = entry.getBooleanOr(ORDER_AUTO_KEY, true);
				long readyAt = entry.getLongOr(ORDER_READY_KEY, 0L);

				if (!auto) {
					readyAt = Long.MAX_VALUE;
				}

				parsed.put(slot, new MarketOrder(item.get(), price, markup, readyAt, auto,
						entry.getStringOr(ORDER_SELLER_KEY, "")));
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

		// Неподтверждённые предметы: ключ — номер ячейки.
		CompoundTag storedStaging = tag.getCompoundOrEmpty(STAGING_KEY);

		for (String playerKey : storedStaging.keySet()) {
			Optional<UUID> playerId = parseUuid(playerKey);

			if (playerId.isEmpty()) {
				continue;
			}

			CompoundTag playerStaging = storedStaging.getCompoundOrEmpty(playerKey);
			Map<Integer, ItemStack> items = new HashMap<>();

			for (String slotKey : playerStaging.keySet()) {
				int slot = parseSlot(slotKey, playerKey);
				Optional<ItemStack> item = ItemStack.CODEC
						.parse(ops(), playerStaging.getCompoundOrEmpty(slotKey).getCompoundOrEmpty(STAGING_ITEM_KEY))
						.result();

				if (slot < 0 || item.isEmpty() || item.get().isEmpty()) {
					continue;
				}

				items.put(slot, item.get());
			}

			state.staging.put(playerId.get(), items);
		}

		CompoundTag storedMarkup = tag.getCompoundOrEmpty(MARKUP_KEY);

		for (String key : storedMarkup.keySet()) {
			Optional<UUID> playerId = parseUuid(key);
			playerId.ifPresent(id -> state.markup.put(id, storedMarkup.getDoubleOr(key, 1.0)));
		}

		// Заказы торговца: у каждого игрока свой номер игрового дня и до трёх заказов.
		CompoundTag storedQuests = tag.getCompoundOrEmpty(QUESTS_KEY);

		for (String playerKey : storedQuests.keySet()) {
			Optional<UUID> playerId = parseUuid(playerKey);

			if (playerId.isEmpty()) {
				MarketplaceMod.LOGGER.warn("В сейве некорректный UUID игрока в заказах, запись пропущена: {}",
						playerKey);
				continue;
			}

			CompoundTag playerQuests = storedQuests.getCompoundOrEmpty(playerKey);
			state.questDay.put(playerId.get(), playerQuests.getLongOr(QUEST_DAY_KEY, Long.MIN_VALUE));
			List<TraderQuest> list = new ArrayList<>();

			for (int index = 0; index < TraderQuests.COUNT; index++) {
				String itemId = playerQuests.getStringOr(QUEST_ITEM_KEY + index, "");

				if (itemId.isEmpty()) {
					continue;
				}

				list.add(new TraderQuest(itemId,
						playerQuests.getIntOr(QUEST_COUNT_KEY + index, 0),
						playerQuests.getDoubleOr(QUEST_BONUS_KEY + index, 0.0),
						playerQuests.getBooleanOr(QUEST_DONE_KEY + index, false)));
			}

			state.quests.put(playerId.get(), list);
		}

		// Срочный заказ: описание (если он ещё идёт) и день последнего запуска.
		CompoundTag storedUrgent = tag.getCompoundOrEmpty(URGENT_KEY);
		state.urgentLastDay = storedUrgent.getLongOr(URGENT_LAST_DAY_KEY, Long.MIN_VALUE);
		String urgentItem = storedUrgent.getStringOr(URGENT_ITEM_KEY, "");
		long urgentLeft = storedUrgent.getLongOr(URGENT_LEFT_KEY, 0L);

		if (!urgentItem.isEmpty() && urgentLeft > 0L) {
			state.urgentQuest = new UrgentQuest(urgentItem,
					Math.max(1, storedUrgent.getIntOr(URGENT_COUNT_KEY, 1)),
					storedUrgent.getDoubleOr(URGENT_REWARD_KEY, 0.0),
					urgentLeft);
		}

		// Свои сеты игроков: название — ключ записи, предметы читаются через реестры мира
		// (иначе зачарования и зелья в составе сета теряются).
		CompoundTag storedKits = tag.getCompoundOrEmpty(CUSTOM_KITS_KEY);

		for (String name : storedKits.keySet()) {
			CompoundTag kitTag = storedKits.getCompoundOrEmpty(name);
			Optional<UUID> author = parseUuid(kitTag.getStringOr(CUSTOM_KIT_AUTHOR_KEY, ""));

			if (author.isEmpty()) {
				MarketplaceMod.LOGGER.warn("В сейве свой сет «{}» без корректного автора — запись пропущена", name);
				continue;
			}

			ListTag itemsTag = kitTag.getListOrEmpty(CUSTOM_KIT_ITEMS_KEY);
			List<CustomKit.Entry> items = new ArrayList<>();

			for (int index = 0; index < itemsTag.size(); index++) {
				CompoundTag itemTag = itemsTag.getCompoundOrEmpty(index);
				Optional<ItemStack> stack = ItemStack.CODEC
						.parse(ops(), itemTag.getCompoundOrEmpty(CUSTOM_KIT_ITEM_KEY)).result();

				if (stack.isEmpty() || stack.get().isEmpty()) {
					MarketplaceMod.LOGGER.warn("В сейве свой сет «{}» содержит повреждённый предмет — пропущен", name);
					continue;
				}

				items.add(new CustomKit.Entry(stack.get(),
						parseSource(itemTag.getStringOr(CUSTOM_KIT_SOURCE_KEY, ""), name)));
			}

			if (items.isEmpty()) {
				MarketplaceMod.LOGGER.warn("В сейве свой сет «{}» остался без предметов — запись пропущена", name);
				continue;
			}

			state.customKits.put(name, new CustomKit(name, author.get(),
					kitTag.getStringOr(CUSTOM_KIT_AUTHOR_NAME_KEY, "?"),
					kitTag.getBooleanOr(CUSTOM_KIT_SHULKERS_KEY, true), items));
		}

		// Купленные виртуальные эндер-сундуки: ключ — UUID игрока.
		CompoundTag storedEnderChests = tag.getCompoundOrEmpty(ENDER_CHEST_KEY);

		for (String key : storedEnderChests.keySet()) {
			parseUuid(key).ifPresentOrElse(state.enderChestOwners::add,
					() -> MarketplaceMod.LOGGER.warn(
							"В сейве некорректный UUID игрока в покупках эндер-сундука, запись пропущена: {}", key));
		}

		// Крупные ивенты. Старые сейвы этих ключей не знают — тогда ивентов просто нет,
		// а счётчик активного времени начинается с нуля: следующий ивент выпадет в свой день.
		state.activeTicks = tag.getLongOr(EVENT_TIME_KEY, 0L);
		state.eventRemaining = tag.getLongOr(EVENT_REMAINING_KEY, 0L);
		state.eventPendingAt = tag.getLongOr(EVENT_PENDING_AT_KEY, 0L);
		state.eventRumorAt = tag.getLongOr(EVENT_RUMOR_AT_KEY, -1L);
		state.eventRumorDone = tag.getBooleanOr(EVENT_RUMOR_DONE_KEY, false);
		state.eventRolledDay = tag.getLongOr(EVENT_ROLLED_DAY_KEY, Long.MIN_VALUE);
		state.activeEvent = readEvent(tag.getCompoundOrEmpty(EVENT_ACTIVE_KEY), "идущий");
		state.pendingEvent = readEvent(tag.getCompoundOrEmpty(EVENT_PENDING_KEY), "запланированный");

		if (state.pendingEvent == null) {
			// Запланированного ивента нет — значит, и слуха быть не может.
			state.eventRumorAt = -1L;
			state.eventRumorDone = false;
		}

		return state;
	}

	/**
	 * Читает описание ивента из NBT. Повреждённую запись не прячем: пишем в лог, что именно
	 * не так, и продолжаем без ивента — терять из-за неё весь сейв нельзя.
	 */
	private static MarketEvent readEvent(CompoundTag tag, String what) {
		if (tag.isEmpty()) {
			return null;
		}

		Optional<MarketEvent> event = MarketEvent.fromTag(tag);

		if (event.isEmpty()) {
			MarketplaceMod.LOGGER.warn("В сейве повреждено описание {} ивента — запись пропущена: {}",
					what, tag);
			return null;
		}

		return event.get();
	}

	/** Откуда взят предмет своего сета: значение из перечисления {@link CustomKit.Source}. */
	private static CustomKit.Source parseSource(String value, String kitName) {
		if (value == null || value.isEmpty()) {
			return CustomKit.Source.INVENTORY;
		}

		try {
			return CustomKit.Source.valueOf(value);
		} catch (IllegalArgumentException exception) {
			MarketplaceMod.LOGGER.warn("В своём сете «{}» неизвестный источник предмета «{}» — считаю инвентарём",
					kitName, value);
			return CustomKit.Source.INVENTORY;
		}
	}

	private static int parseSlot(String slotKey, String playerKey) {		try {
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

				// Ошибку кодирования не прячем: без неё предмет ордера просто исчезнет из сейва.
				ItemStack.CODEC.encodeStart(ops(), order.stack()).result().ifPresentOrElse(
						encoded -> entry.put(ORDER_ITEM_KEY, encoded),
						() -> MarketplaceMod.LOGGER.error(
								"Не удалось сохранить предмет ордера игрока {} (ячейка {})", playerId, slot));

				entry.putDouble(ORDER_PRICE_KEY, order.price());
				entry.putDouble(ORDER_MARKUP_KEY, order.markup());
				entry.putLong(ORDER_READY_KEY, order.readyAt());
				entry.putBoolean(ORDER_AUTO_KEY, order.auto());
				entry.putString(ORDER_SELLER_KEY, order.seller());

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

		CompoundTag storedStaging = new CompoundTag();

		state.staging.forEach((playerId, items) -> {
			CompoundTag playerTag = new CompoundTag();

			items.forEach((slot, item) -> {
				CompoundTag entry = new CompoundTag();

				// То же, что и с ордерами: ошибку кодирования показываем в логе.
				ItemStack.CODEC.encodeStart(ops(), item).result().ifPresentOrElse(
						encoded -> entry.put(STAGING_ITEM_KEY, encoded),
						() -> MarketplaceMod.LOGGER.error(
								"Не удалось сохранить неподтверждённый предмет игрока {} (ячейка {})", playerId, slot));

				playerTag.put(String.valueOf(slot), entry);
			});

			storedStaging.put(playerId.toString(), playerTag);
		});

		tag.put(STAGING_KEY, storedStaging);

		CompoundTag storedMarkup = new CompoundTag();
		state.markup.forEach((playerId, value) -> storedMarkup.putDouble(playerId.toString(), value));
		tag.put(MARKUP_KEY, storedMarkup);

		// Заказы торговца: номер игрового дня и сами заказы (ключи вида item0, count0 и так далее).
		CompoundTag storedQuests = new CompoundTag();

		state.quests.forEach((playerId, list) -> {
			CompoundTag playerTag = new CompoundTag();
			playerTag.putLong(QUEST_DAY_KEY, state.questDay.getOrDefault(playerId, Long.MIN_VALUE));

			for (int index = 0; index < list.size(); index++) {
				TraderQuest quest = list.get(index);
				playerTag.putString(QUEST_ITEM_KEY + index, quest.itemId());
				playerTag.putInt(QUEST_COUNT_KEY + index, quest.count());
				playerTag.putDouble(QUEST_BONUS_KEY + index, quest.bonus());
				playerTag.putBoolean(QUEST_DONE_KEY + index, quest.done());
			}

			storedQuests.put(playerId.toString(), playerTag);
		});

		tag.put(QUESTS_KEY, storedQuests);

		// Срочный заказ: описание и день последнего запуска.
		CompoundTag storedUrgent = new CompoundTag();
		storedUrgent.putLong(URGENT_LAST_DAY_KEY, state.urgentLastDay);

		if (state.urgentQuest != null) {
			storedUrgent.putString(URGENT_ITEM_KEY, state.urgentQuest.itemId());
			storedUrgent.putInt(URGENT_COUNT_KEY, state.urgentQuest.count());
			storedUrgent.putDouble(URGENT_REWARD_KEY, state.urgentQuest.reward());
			storedUrgent.putLong(URGENT_LEFT_KEY, state.urgentQuest.remaining());
		}

		tag.put(URGENT_KEY, storedUrgent);

		// Свои сеты игроков: название — ключ записи, внутри автор, способ выдачи и предметы.
		CompoundTag storedKits = new CompoundTag();

		state.customKits.forEach((name, kit) -> {
			CompoundTag kitTag = new CompoundTag();
			kitTag.putString(CUSTOM_KIT_AUTHOR_KEY, kit.author().toString());
			kitTag.putString(CUSTOM_KIT_AUTHOR_NAME_KEY, kit.authorName());
			kitTag.putBoolean(CUSTOM_KIT_SHULKERS_KEY, kit.shulkers());
			ListTag itemsTag = new ListTag();

			for (CustomKit.Entry entry : kit.items()) {
				CompoundTag itemTag = new CompoundTag();
				itemTag.putString(CUSTOM_KIT_SOURCE_KEY, entry.source().name());
				// Ошибку кодирования не прячем: без неё предмет молча исчезнет из сета в сейве.
				ItemStack.CODEC.encodeStart(ops(), entry.stack()).result().ifPresentOrElse(
						encoded -> itemTag.put(CUSTOM_KIT_ITEM_KEY, encoded),
						() -> MarketplaceMod.LOGGER.error(
								"Не удалось сохранить предмет своего сета «{}»", name));
				itemsTag.add(itemTag);
			}

			kitTag.put(CUSTOM_KIT_ITEMS_KEY, itemsTag);
			storedKits.put(name, kitTag);
		});

		tag.put(CUSTOM_KITS_KEY, storedKits);

		// Купленные виртуальные эндер-сундуки: UUID игрока → true.
		CompoundTag storedEnderChests = new CompoundTag();
		state.enderChestOwners.forEach(playerId -> storedEnderChests.putBoolean(playerId.toString(), true));
		tag.put(ENDER_CHEST_KEY, storedEnderChests);

		// Крупные ивенты: активное время, идущий ивент с остатком, запланированный со слухом.
		tag.putLong(EVENT_TIME_KEY, state.activeTicks);
		tag.putLong(EVENT_REMAINING_KEY, state.eventRemaining);
		tag.putLong(EVENT_PENDING_AT_KEY, state.eventPendingAt);
		tag.putLong(EVENT_RUMOR_AT_KEY, state.eventRumorAt);
		tag.putBoolean(EVENT_RUMOR_DONE_KEY, state.eventRumorDone);
		tag.putLong(EVENT_ROLLED_DAY_KEY, state.eventRolledDay);

		if (state.activeEvent != null) {
			tag.put(EVENT_ACTIVE_KEY, state.activeEvent.toTag());
		}

		if (state.pendingEvent != null) {
			tag.put(EVENT_PENDING_KEY, state.pendingEvent.toTag());
		}

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
