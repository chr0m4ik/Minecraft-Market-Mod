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

public class MarketplaceState extends SavedData {

	private static final String BALANCES_KEY = "balances";

	private static final String LIMITS_KEY = "trader_limits";
	private static final String DAY_KEY = "day";
	private static final String SOLD_KEY = "sold";

	private static final String TRADER_SALES_KEY = "trader_sales";
	private static final String SOLD_AT_KEY = "at";

	private static final String ORDERS_KEY = "orders";
	private static final String ORDER_ITEM_KEY = "item";
	private static final String ORDER_MARKUP_KEY = "markup";
	private static final String ORDER_READY_KEY = "ready";

	private static final String ORDER_PRICE_KEY = "price";
	private static final String ORDER_AUTO_KEY = "auto";
	private static final String ORDER_SELLER_KEY = "seller";

	private static final String ORDER_PAYOUT_KEY = "payout";
	private static final String BOUGHT_SLOTS_KEY = "bought_slots";
	private static final String PENDING_SALES_KEY = "pending_sales";
	private static final String PENDING_COUNT_KEY = "count";
	private static final String PENDING_TOTAL_KEY = "total";

	private static final String STAGING_KEY = "staging";
	private static final String STAGING_ITEM_KEY = "item";
	private static final String MARKUP_KEY = "markup";

	private static final String QUESTS_KEY = "quests";
	private static final String QUEST_DAY_KEY = "day";
	private static final String QUEST_ITEM_KEY = "item";
	private static final String QUEST_COUNT_KEY = "count";
	private static final String QUEST_BONUS_KEY = "bonus";
	private static final String QUEST_DONE_KEY = "done";

	private static final String QUEST_DELIVERED_KEY = "delivered";

	private static final String URGENT_KEY = "urgent";
	private static final String URGENT_ITEM_KEY = "item";
	private static final String URGENT_COUNT_KEY = "count";
	private static final String URGENT_REWARD_KEY = "reward";
	private static final String URGENT_LEFT_KEY = "left";
	private static final String URGENT_LAST_DAY_KEY = "last_day";

	private static final String CUSTOM_KITS_KEY = "custom_kits";
	private static final String CUSTOM_KIT_AUTHOR_KEY = "author";
	private static final String CUSTOM_KIT_AUTHOR_NAME_KEY = "author_name";
	private static final String CUSTOM_KIT_SHULKERS_KEY = "shulkers";
	private static final String CUSTOM_KIT_ITEMS_KEY = "items";
	private static final String CUSTOM_KIT_ITEM_KEY = "item";
	private static final String CUSTOM_KIT_SOURCE_KEY = "source";

	private static final String ENDER_CHEST_KEY = "ender_chest";

	private static final String EVENT_TIME_KEY = "event_active_time";
	private static final String EVENT_ACTIVE_KEY = "event_active";
	private static final String EVENT_REMAINING_KEY = "event_remaining";
	private static final String EVENT_PENDING_KEY = "event_pending";
	private static final String EVENT_PENDING_AT_KEY = "event_pending_at";
	private static final String EVENT_RUMOR_AT_KEY = "event_rumor_at";
	private static final String EVENT_RUMOR_DONE_KEY = "event_rumor_done";
	private static final String EVENT_ROLLED_DAY_KEY = "event_rolled_day";

	private final Map<UUID, Double> balances = new HashMap<>();

	private final Map<UUID, Map<String, Double>> soldToday = new HashMap<>();

	private final Map<UUID, Long> limitDay = new HashMap<>();

	private final Map<String, Double> traderSold = new HashMap<>();

	private final Map<String, Long> traderSoldAt = new HashMap<>();

	private final Map<UUID, Map<Integer, MarketOrder>> orders = new HashMap<>();

	private final Map<UUID, Integer> boughtSlots = new HashMap<>();

	private final Map<UUID, double[]> pendingSales = new HashMap<>();

	private final Map<UUID, Map<Integer, ItemStack>> staging = new HashMap<>();

	private final Map<UUID, Double> markup = new HashMap<>();

	private final Map<UUID, List<TraderQuest>> quests = new HashMap<>();

	private final Map<UUID, Long> questDay = new HashMap<>();

	private UrgentQuest urgentQuest;

	private long urgentLastDay = Long.MIN_VALUE;

	private final Map<String, CustomKit> customKits = new LinkedHashMap<>();

	private final Set<UUID> enderChestOwners = new HashSet<>();

	private long activeTicks;

	private MarketEvent activeEvent;
	private long eventRemaining;

	private MarketEvent pendingEvent;
	private long eventPendingAt;

	private long eventRumorAt = -1L;
	private boolean eventRumorDone;

	private long eventRolledDay = Long.MIN_VALUE;

	public MarketplaceState() {
	}

	private static final Codec<MarketplaceState> CODEC = CompoundTag.CODEC.xmap(
			MarketplaceState::fromTag,
			MarketplaceState::toTag
	);

	public static final SavedDataType<MarketplaceState> TYPE = new SavedDataType<>(
			MarketplaceMod.id("economy"),
			MarketplaceState::new,
			CODEC,
			null
	);

	private static HolderLookup.Provider registries;

	public static void setRegistries(HolderLookup.Provider value) {
		registries = value;
	}

	private static DynamicOps<Tag> ops() {
		return registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
	}

	public double getBalance(UUID playerId) {
		return balances.getOrDefault(playerId, 0.0);
	}

	public void setBalance(UUID playerId, double amount) {
		balances.put(playerId, MoneyFormat.round(amount));
		setDirty();
	}

	// --- Дневной лимит торговца ---

	public void refreshTraderDay(UUID playerId, long day) {
		Long known = limitDay.get(playerId);

		if (known != null && known == day) {
			return;
		}

		limitDay.put(playerId, day);
		soldToday.remove(playerId);
		setDirty();
	}

	public double soldStacks(UUID playerId, Item item) {
		Map<String, Double> sold = soldToday.get(playerId);

		if (sold == null) {
			return 0.0;
		}

		return sold.getOrDefault(itemId(item), 0.0);
	}

	public void addSoldStacks(UUID playerId, Item item, double stacks) {
		soldToday.computeIfAbsent(playerId, id -> new HashMap<>())
				.merge(itemId(item), stacks, Double::sum);
		setDirty();
	}

	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

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

	public void addTraderSale(Item item, double stacks, long now) {
		String id = itemId(item);
		traderSold.put(id, traderSold(id, now) + stacks);
		traderSoldAt.put(id, now);
		setDirty();
	}

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

			// MarketplaceMod.LOGGER.warn("В истории продаж некорректный id предмета, запись пропущена: {}", id);
			return Optional.empty();
		}
	}

	// --- Рынок: ордера на продажу ---

	public MarketOrder order(UUID playerId, int slot) {
		Map<Integer, MarketOrder> playerOrders = orders.get(playerId);
		return playerOrders == null ? null : playerOrders.get(slot);
	}

	public Map<Integer, MarketOrder> orders(UUID playerId) {
		return orders.getOrDefault(playerId, Map.of());
	}

	public Set<UUID> playersWithOrders() {
		return Set.copyOf(orders.keySet());
	}

	public void setOrder(UUID playerId, int slot, MarketOrder order) {
		orders.computeIfAbsent(playerId, id -> new HashMap<>()).put(slot, order);
		setDirty();
	}

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

	public int boughtSlots(UUID playerId) {
		return boughtSlots.getOrDefault(playerId, 0);
	}

	public void setBoughtSlots(UUID playerId, int count) {
		boughtSlots.put(playerId, count);
		setDirty();
	}

	public void addPendingSale(UUID playerId, double amount) {
		double[] pending = pendingSales.computeIfAbsent(playerId, id -> new double[2]);
		pending[0] += 1.0;
		pending[1] += amount;
		setDirty();
	}

	public double[] takePendingSales(UUID playerId) {
		double[] pending = pendingSales.remove(playerId);

		if (pending != null) {
			setDirty();
		}

		return pending;
	}

	public Map<Integer, ItemStack> staging(UUID playerId) {
		return staging.getOrDefault(playerId, Map.of());
	}

	public void setStaging(UUID playerId, Map<Integer, ItemStack> items) {
		staging.put(playerId, new HashMap<>(items));
		setDirty();
	}

	public double markup(UUID playerId) {
		return markup.getOrDefault(playerId, 1.0);
	}

	public void setMarkup(UUID playerId, double value) {
		markup.put(playerId, value);
		setDirty();
	}

	// --- Заказы торговца ---

	public List<TraderQuest> quests(UUID playerId) {
		return quests.getOrDefault(playerId, List.of());
	}

	public void setQuests(UUID playerId, List<TraderQuest> list) {
		quests.put(playerId, List.copyOf(list));
		setDirty();
	}

	public long questDay(UUID playerId) {
		return questDay.getOrDefault(playerId, Long.MIN_VALUE);
	}

	public void setQuestDay(UUID playerId, long day) {
		questDay.put(playerId, day);
		setDirty();
	}

	// --- Срочный заказ ---

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

	public long urgentLastDay() {
		return this.urgentLastDay;
	}

	public void setUrgentLastDay(long day) {
		this.urgentLastDay = day;
		setDirty();
	}

	// --- Свои сеты игроков ---

	public Map<String, CustomKit> customKits() {
		return Collections.unmodifiableMap(this.customKits);
	}

	public void putCustomKit(CustomKit kit) {
		this.customKits.put(kit.name(), kit);
		setDirty();
	}

	public CustomKit removeCustomKit(String name) {
		CustomKit removed = this.customKits.remove(name);

		if (removed != null) {
			setDirty();
		}

		return removed;
	}

	// --- Виртуальный эндер-сундук ---

	public boolean ownsEnderChest(UUID playerId) {
		return this.enderChestOwners.contains(playerId);
	}

	public void grantEnderChest(UUID playerId) {
		if (this.enderChestOwners.add(playerId)) {
			setDirty();
		}
	}

	// --- Крупные ивенты ---

	public long activeTicks() {
		return this.activeTicks;
	}

	public void addActiveTick() {
		this.activeTicks++;
		setDirty();
	}

	public MarketEvent activeEvent() {
		return this.activeEvent;
	}

	public long eventRemaining() {
		return this.eventRemaining;
	}

	public void startEvent(MarketEvent event, long remainingTicks) {
		this.activeEvent = event;
		this.eventRemaining = Math.max(0L, remainingTicks);
		clearPending();
		setDirty();
	}

	public void setEventRemaining(long ticks) {
		this.eventRemaining = Math.max(0L, ticks);
		setDirty();
	}

	public void clearEvent() {
		this.activeEvent = null;
		this.eventRemaining = 0L;
		setDirty();
	}

	public MarketEvent pendingEvent() {
		return this.pendingEvent;
	}

	public long eventPendingAt() {
		return this.eventPendingAt;
	}

	public void scheduleEvent(MarketEvent event, long at, long rumorAt) {
		this.pendingEvent = event;
		this.eventPendingAt = at;
		this.eventRumorAt = rumorAt;
		this.eventRumorDone = false;
		setDirty();
	}

	public void clearPending() {
		this.pendingEvent = null;
		this.eventPendingAt = 0L;
		this.eventRumorAt = -1L;
		this.eventRumorDone = false;
		setDirty();
	}

	public long eventRumorAt() {
		return this.eventRumorAt;
	}

	public boolean eventRumorDone() {
		return this.eventRumorDone;
	}

	public void setEventRumorDone() {
		this.eventRumorDone = true;
		setDirty();
	}

	public long eventRolledDay() {
		return this.eventRolledDay;
	}

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

				// MarketplaceMod.LOGGER.warn("В сейве найден некорректный UUID игрока, запись пропущена: {}", key);
				continue;
			}

			state.balances.put(playerId.get(), MoneyFormat.round(storedBalances.getDoubleOr(key, 0.0)));
		}

		CompoundTag storedLimits = tag.getCompoundOrEmpty(LIMITS_KEY);

		for (String key : storedLimits.keySet()) {
			Optional<UUID> playerId = parseUuid(key);

			if (playerId.isEmpty()) {
				// MarketplaceMod.LOGGER.warn("В сейве найден некорректный UUID игрока в лимитах, запись пропущена: {}", key);
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

		CompoundTag storedOrders = tag.getCompoundOrEmpty(ORDERS_KEY);

		for (String playerKey : storedOrders.keySet()) {
			Optional<UUID> playerId = parseUuid(playerKey);

			if (playerId.isEmpty()) {
				// MarketplaceMod.LOGGER.warn("В сейве некорректный UUID игрока в ордерах, запись пропущена: {}", playerKey);
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
					// MarketplaceMod.LOGGER.warn("В ордере игрока {} повреждён предмет, запись пропущена", playerKey);
					continue;
				}

				double markup = entry.getDoubleOr(ORDER_MARKUP_KEY, 1.0);

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

		CompoundTag storedQuests = tag.getCompoundOrEmpty(QUESTS_KEY);

		for (String playerKey : storedQuests.keySet()) {
			Optional<UUID> playerId = parseUuid(playerKey);

			if (playerId.isEmpty()) {
				// MarketplaceMod.LOGGER.warn("В сейве некорректный UUID игрока в заказах, запись пропущена: {}",
						// playerKey);
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
						playerQuests.getBooleanOr(QUEST_DONE_KEY + index, false),
						playerQuests.getIntOr(QUEST_DELIVERED_KEY + index, 0)));
			}

			state.quests.put(playerId.get(), list);
		}

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

		CompoundTag storedKits = tag.getCompoundOrEmpty(CUSTOM_KITS_KEY);

		for (String name : storedKits.keySet()) {
			CompoundTag kitTag = storedKits.getCompoundOrEmpty(name);
			Optional<UUID> author = parseUuid(kitTag.getStringOr(CUSTOM_KIT_AUTHOR_KEY, ""));

			if (author.isEmpty()) {
				// MarketplaceMod.LOGGER.warn("В сейве свой сет «{}» без корректного автора — запись пропущена", name);
				continue;
			}

			ListTag itemsTag = kitTag.getListOrEmpty(CUSTOM_KIT_ITEMS_KEY);
			List<CustomKit.Entry> items = new ArrayList<>();

			for (int index = 0; index < itemsTag.size(); index++) {
				CompoundTag itemTag = itemsTag.getCompoundOrEmpty(index);
				Optional<ItemStack> stack = ItemStack.CODEC
						.parse(ops(), itemTag.getCompoundOrEmpty(CUSTOM_KIT_ITEM_KEY)).result();

				if (stack.isEmpty() || stack.get().isEmpty()) {
					// MarketplaceMod.LOGGER.warn("В сейве свой сет «{}» содержит повреждённый предмет — пропущен", name);
					continue;
				}

				items.add(new CustomKit.Entry(stack.get(),
						parseSource(itemTag.getStringOr(CUSTOM_KIT_SOURCE_KEY, ""), name)));
			}

			if (items.isEmpty()) {
				// MarketplaceMod.LOGGER.warn("В сейве свой сет «{}» остался без предметов — запись пропущена", name);
				continue;
			}

			state.customKits.put(name, new CustomKit(name, author.get(),
					kitTag.getStringOr(CUSTOM_KIT_AUTHOR_NAME_KEY, "?"),
					kitTag.getBooleanOr(CUSTOM_KIT_SHULKERS_KEY, true), items));
		}

		CompoundTag storedEnderChests = tag.getCompoundOrEmpty(ENDER_CHEST_KEY);

		for (String key : storedEnderChests.keySet()) {
			parseUuid(key).ifPresent(state.enderChestOwners::add);
		}

		state.activeTicks = tag.getLongOr(EVENT_TIME_KEY, 0L);
		state.eventRemaining = tag.getLongOr(EVENT_REMAINING_KEY, 0L);
		state.eventPendingAt = tag.getLongOr(EVENT_PENDING_AT_KEY, 0L);
		state.eventRumorAt = tag.getLongOr(EVENT_RUMOR_AT_KEY, -1L);
		state.eventRumorDone = tag.getBooleanOr(EVENT_RUMOR_DONE_KEY, false);
		state.eventRolledDay = tag.getLongOr(EVENT_ROLLED_DAY_KEY, Long.MIN_VALUE);
		state.activeEvent = readEvent(tag.getCompoundOrEmpty(EVENT_ACTIVE_KEY), "идущий");
		state.pendingEvent = readEvent(tag.getCompoundOrEmpty(EVENT_PENDING_KEY), "запланированный");

		if (state.pendingEvent == null) {

			state.eventRumorAt = -1L;
			state.eventRumorDone = false;
		}

		return state;
	}

	private static MarketEvent readEvent(CompoundTag tag, String what) {
		if (tag.isEmpty()) {
			return null;
		}

		Optional<MarketEvent> event = MarketEvent.fromTag(tag);

		if (event.isEmpty()) {
			// MarketplaceMod.LOGGER.warn("В сейве повреждено описание {} ивента — запись пропущена: {}",
					// what, tag);
			return null;
		}

		return event.get();
	}

	private static CustomKit.Source parseSource(String value, String kitName) {
		if (value == null || value.isEmpty()) {
			return CustomKit.Source.INVENTORY;
		}

		try {
			return CustomKit.Source.valueOf(value);
		} catch (IllegalArgumentException exception) {
			// MarketplaceMod.LOGGER.warn("В своём сете «{}» неизвестный источник предмета «{}» — считаю инвентарём",
					// kitName, value);
			return CustomKit.Source.INVENTORY;
		}
	}

	private static int parseSlot(String slotKey, String playerKey) {		try {
			return Integer.parseInt(slotKey);
		} catch (NumberFormatException exception) {
			// MarketplaceMod.LOGGER.warn("В ордерах игрока {} некорректный номер ячейки, запись пропущена: {}",
					// playerKey, slotKey);
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

				var encoded = ItemStack.CODEC.encodeStart(ops(), order.stack()).result();

				if (encoded.isEmpty()) {
					MarketplaceMod.LOGGER.error(
							"Не удалось сохранить предмет ордера игрока {} (ячейка {})", playerId, slot);
					return;
				}

				entry.put(ORDER_ITEM_KEY, encoded.get());
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

				var encoded = ItemStack.CODEC.encodeStart(ops(), item).result();

				if (encoded.isEmpty()) {
					MarketplaceMod.LOGGER.error(
							"Не удалось сохранить неподтверждённый предмет игрока {} (ячейка {})", playerId, slot);
					return;
				}

				entry.put(STAGING_ITEM_KEY, encoded.get());
				playerTag.put(String.valueOf(slot), entry);
			});

			storedStaging.put(playerId.toString(), playerTag);
		});

		tag.put(STAGING_KEY, storedStaging);

		CompoundTag storedMarkup = new CompoundTag();
		state.markup.forEach((playerId, value) -> storedMarkup.putDouble(playerId.toString(), value));
		tag.put(MARKUP_KEY, storedMarkup);

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
				playerTag.putInt(QUEST_DELIVERED_KEY + index, quest.delivered());
			}

			storedQuests.put(playerId.toString(), playerTag);
		});

		tag.put(QUESTS_KEY, storedQuests);

		CompoundTag storedUrgent = new CompoundTag();
		storedUrgent.putLong(URGENT_LAST_DAY_KEY, state.urgentLastDay);

		if (state.urgentQuest != null) {
			storedUrgent.putString(URGENT_ITEM_KEY, state.urgentQuest.itemId());
			storedUrgent.putInt(URGENT_COUNT_KEY, state.urgentQuest.count());
			storedUrgent.putDouble(URGENT_REWARD_KEY, state.urgentQuest.reward());
			storedUrgent.putLong(URGENT_LEFT_KEY, state.urgentQuest.remaining());
		}

		tag.put(URGENT_KEY, storedUrgent);

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

		CompoundTag storedEnderChests = new CompoundTag();
		state.enderChestOwners.forEach(playerId -> storedEnderChests.putBoolean(playerId.toString(), true));
		tag.put(ENDER_CHEST_KEY, storedEnderChests);

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
