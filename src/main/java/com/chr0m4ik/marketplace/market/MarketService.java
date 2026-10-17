package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.sound.MarketSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Рынок: игрок выставляет предметы ордерами, они продаются через некоторое время,
 * а слоты под ордера можно докупать.
 *
 * <p>Правила (заданы заказчиком):
 * <ul>
 *     <li>цена — 100% базовой, динамики цен на рынке нет (она только у торговца);</li>
 *     <li>наценка x1…x2: чем выше, тем дольше продаётся ({@link SaleTimes#markupSlowdown});</li>
 *     <li>время продажи зависит от предмета и считается случайно ({@link SaleTimes});</li>
 *     <li>ордера живут в сейве мира, поэтому продажа идёт и без игрока в игре;</li>
 *     <li>ордер можно снять — предметы возвращаются.</li>
 * </ul>
 */
public final class MarketService {
	/** Всего ячеек рынка: 9×5 (нижняя строка окна — системная). */
	public static final int SLOTS = 45;

	/** Сколько ячеек открыто сразу, без покупки. */
	public static final int FREE_SLOTS = 5;

	/**
	 * Наценки, между которыми переключается игрок (x1…x2). Лежат в
	 * {@code data/marketplace/economy.json}, поэтому заказчик может их менять без пересборки.
	 */
	public static double[] markups() {
		return EconomyConfig.marketMarkups();
	}

	/** Цена первого покупаемого слота. */
	private static final double SLOT_BASE_PRICE = 200.0;

	/** Во сколько раз дороже каждый следующий слот: 40-й выходит за сотни миллиардов. */
	private static final double SLOT_PRICE_STEP = 1.72;

	private MarketService() {
		// Класс только со статическими методами.
	}

	/** Сколько ячеек открыто у игрока. */
	public static int unlockedSlots(MarketplaceState state, UUID playerId) {
		return Math.min(SLOTS, FREE_SLOTS + state.boughtSlots(playerId));
	}

	/** Цена следующего слота. */
	public static double slotPrice(MarketplaceState state, UUID playerId) {
		return slotPriceFor(unlockedSlots(state, playerId));
	}

	/**
	 * Цена следующего слота по числу уже открытых ячеек. Клиент считает цену этой же формулой —
	 * так ему не нужно передавать её отдельно.
	 */
	public static double slotPriceFor(int unlockedSlots) {
		int bought = Math.max(0, unlockedSlots - FREE_SLOTS);
		return MoneyFormat.round(SLOT_BASE_PRICE * Math.pow(SLOT_PRICE_STEP, bought));
	}

	/** Покупка следующего слота. {@code false} — если слотов уже максимум или не хватило денег. */
	public static boolean buySlot(MinecraftServer server, UUID playerId) {
		MarketplaceState state = MarketplaceEconomy.state(server);

		if (unlockedSlots(state, playerId) >= SLOTS) {
			return false;
		}

		if (!MarketplaceEconomy.trySpend(server, playerId, slotPrice(state, playerId))) {
			return false;
		}

		state.setBoughtSlots(playerId, state.boughtSlots(playerId) + 1);
		return true;
	}

	// --- Торговая площадка: лоты с ценой игрока (/ah sell и категория «Торговая площадка») ---

	/** Минимальная и максимальная цена лота в {@code /ah sell}. */
	public static final double MIN_LIST_PRICE = 1.0;
	public static final double MAX_LIST_PRICE = 1_000_000_000_000.0;

	/** Метка лота в {@code custom_data}: «uuid продавца:ячейка». По ней покупка находит ордер. */
	private static final String AH_TAG = "marketplace:ah_lot";

	/**
	 * Потолок виртуального рынка: если цена лота не выше этой наценки от базовой, лот в конце
	 * концов купит рынок. Дороже — лот ждёт живого покупателя, иначе можно было бы выставить
	 * грязь за миллиард и получить деньги из воздуха.
	 */
	public static double maxAutoMarkup() {
		double max = 1.0;

		for (double markup : markups()) {
			max = Math.max(max, markup);
		}

		return max;
	}

	/** Чем закончилась попытка выставить лот командой. */
	public enum ListStatus {
		/** Лот выставлен в ячейку {@code slot}. */
		LISTED,
		/** Нет свободной открытой ячейки. */
		NO_SLOT,
		/** Цена вне допустимых границ. */
		BAD_PRICE,
		/** Количество больше, чем лежит в руке. */
		BAD_COUNT,
		/** В руке ничего нет. */
		NO_ITEM,
		/** Предмет из чёрного списка (бедрок, барьер и прочее). */
		FORBIDDEN
	}

	public record ListResult(ListStatus status, int slot, MarketOrder order) {
	}

	/** Чем закончилась попытка купить чужой лот. */
	public enum BuyStatus {
		/** Лот куплен: предметы у покупателя, деньги у продавца. */
		BOUGHT,
		/** Лота уже нет (купили или продал рынок). */
		MISSING,
		/** Нельзя покупать свой же лот. */
		OWN_LOT,
		/** Не хватило денег. */
		NO_MONEY
	}

	public record BuyResult(BuyStatus status, MarketOrder order, double paid) {
	}

	/** Лот, найденный по метке: продавец, ячейка и сам ордер. */
	public record Listing(UUID sellerId, int slot, MarketOrder order) {
	}

	/** Первая свободная открытая ячейка (без ордера и без неподтверждённого предмета); -1 — нет. */
	public static int freeSlot(MarketplaceState state, UUID playerId) {
		Map<Integer, ItemStack> staged = state.staging(playerId);
		int unlocked = unlockedSlots(state, playerId);

		for (int slot = 0; slot < unlocked; slot++) {
			if (state.order(playerId, slot) == null && staged.get(slot) == null) {
				return slot;
			}
		}

		return -1;
	}

	/**
	 * Строит лот с ценой, которую задал игрок. Сам ничего не забирает и не сохраняет —
	 * это делает вызывающий, чтобы при ошибке предметы остались у игрока.
	 */
	public static ListResult listForPrice(MarketplaceState state, UUID playerId, ItemStack hand, int count,
			double price, long now, RandomSource random, String seller) {
		if (hand.isEmpty()) {
			return new ListResult(ListStatus.NO_ITEM, -1, null);
		}

		if (count < 1 || count > hand.getCount()) {
			return new ListResult(ListStatus.BAD_COUNT, -1, null);
		}

		if (!Double.isFinite(price) || price < MIN_LIST_PRICE || price > MAX_LIST_PRICE) {
			return new ListResult(ListStatus.BAD_PRICE, -1, null);
		}

		if (ItemPrices.isBlacklisted(hand.getItem())) {
			return new ListResult(ListStatus.FORBIDDEN, -1, null);
		}

		int slot = freeSlot(state, playerId);

		if (slot < 0) {
			return new ListResult(ListStatus.NO_SLOT, -1, null);
		}

		MarketOrder order = orderForPrice(hand.copyWithCount(count), price, now, random, seller);
		state.setOrder(playerId, slot, order);
		return new ListResult(ListStatus.LISTED, slot, order);
	}

	/** Строит лот: цена игрока, срок продажи рынком зависит от отношения цены к базовой. */
	public static MarketOrder orderForPrice(ItemStack lot, double price, long now, RandomSource random,
			String seller) {
		double rounded = MoneyFormat.round(price);
		// basePriceOf — цена одной штуки, поэтому для лота умножаем на количество.
		double base = ItemPrices.basePriceOf(lot) * lot.getCount();
		double ratio = base > 0.0 ? rounded / base : Double.POSITIVE_INFINITY;
		boolean auto = base > 0.0 && ratio <= maxAutoMarkup() + 1.0E-9;
		long readyAt = auto ? now + SaleTimes.ticksForPrice(lot, rounded, random) : Long.MAX_VALUE;

		return new MarketOrder(lot, rounded, ratio, readyAt, auto, seller);
	}

	/** Иконка лота для категории «Торговая площадка»: предмет, метка и описание. */
	public static ItemStack listingIcon(MarketOrder order, UUID sellerId, int slot) {
		ItemStack icon = order.stack().copy();
		// Метку добавляем, не стирая другие custom_data предмета (например, уровень турбо печки).
		CustomData.update(DataComponents.CUSTOM_DATA, icon,
				tag -> tag.putString(AH_TAG, sellerId + ":" + slot));

		List<Component> lore = new ArrayList<>();
		lore.add(plain(Component.translatable("marketplace.tooltip.auction.price",
				Component.literal(MoneyFormat.format(order.payout()))).withStyle(ChatFormatting.GOLD)));
		lore.add(plain(Component.translatable("marketplace.tooltip.auction.seller",
				Component.literal(order.seller())).withStyle(ChatFormatting.GRAY)));
		lore.add(plain(Component.translatable(order.waitingForBuyer()
						? "marketplace.tooltip.auction.waiting" : "marketplace.tooltip.auction.auto")
				.withStyle(ChatFormatting.DARK_GRAY)));
		icon.set(DataComponents.LORE, new ItemLore(lore));
		return icon;
	}

	/** Метка лота на иконке (пусто — это не лот площадки). */
	public static String listingId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? "" : data.copyTag().getStringOr(AH_TAG, "");
	}

	/** Находит лот по иконке из магазина. */
	public static Optional<Listing> listing(MarketplaceState state, ItemStack icon) {
		String id = listingId(icon);
		int separator = id.lastIndexOf(':');

		if (separator <= 0 || separator == id.length() - 1) {
			return Optional.empty();
		}

		try {
			UUID sellerId = UUID.fromString(id.substring(0, separator));
			int slot = Integer.parseInt(id.substring(separator + 1));
			MarketOrder order = state.order(sellerId, slot);
			return order == null ? Optional.empty() : Optional.of(new Listing(sellerId, slot, order));
		} catch (IllegalArgumentException exception) {
			// Метку создаёт только мод: битая метка — ошибка в коде или сейве, а не ввод игрока.
			MarketplaceMod.LOGGER.warn("У лота торговой площадки некорректная метка: {}", id);
			return Optional.empty();
		}
	}

	/** Цена лота по иконке (0 — лот уже куплен или это не лот). */
	public static double listingPrice(MarketplaceState state, ItemStack icon) {
		return listing(state, icon).map(found -> found.order().payout()).orElse(0.0);
	}

	/** Все лоты мира, дешёвые вперёд — так их показывает категория «Торговая площадка». */
	public static List<Listing> allListings(MarketplaceState state) {
		List<Listing> result = new ArrayList<>();

		for (UUID sellerId : state.playersWithOrders()) {
			for (Map.Entry<Integer, MarketOrder> entry : state.orders(sellerId).entrySet()) {
				MarketOrder order = entry.getValue();

				if (order != null && !order.isEmpty()) {
					result.add(new Listing(sellerId, entry.getKey(), order));
				}
			}
		}

		result.sort(Comparator.comparingDouble(found -> found.order().payout()));
		return List.copyOf(result);
	}

	/**
	 * Покупка чужого лота: деньги продавцу, предметы покупателю, лот снимается.
	 *
	 * <p>Порядок важен: сначала списываем деньги у покупателя, потом снимаем лот, и только
	 * потом начисляем продавцу. Если лот успели продать рынку, деньги возвращаются покупателю —
	 * двойной выплаты быть не может.
	 */
	public static BuyResult buyListing(MinecraftServer server, ServerPlayer buyer, ItemStack icon, boolean drop) {
		MarketplaceState state = MarketplaceEconomy.state(server);
		Optional<Listing> found = listing(state, icon);

		if (found.isEmpty()) {
			return new BuyResult(BuyStatus.MISSING, null, 0.0);
		}

		Listing listing = found.get();
		MarketOrder order = listing.order();

		if (listing.sellerId().equals(buyer.getUUID())) {
			return new BuyResult(BuyStatus.OWN_LOT, order, 0.0);
		}

		double price = order.payout();

		if (price <= 0.0) {
			return new BuyResult(BuyStatus.MISSING, order, 0.0);
		}

		if (!buyer.isCreative() && !MarketplaceEconomy.trySpend(server, buyer.getUUID(), price)) {
			return new BuyResult(BuyStatus.NO_MONEY, order, 0.0);
		}

		if (state.removeOrder(listing.sellerId(), listing.slot()) == null) {
			MarketplaceEconomy.addBalance(server, buyer.getUUID(), price);
			return new BuyResult(BuyStatus.MISSING, order, 0.0);
		}

		MarketplaceEconomy.addBalance(server, listing.sellerId(), price);
		give(buyer, drop, order.stack().copy());
		notifySeller(server, state, listing.sellerId(), buyer, order, price);

		MarketplaceMod.LOGGER.info("Торговая площадка: {} купил лот «{}» × {} у {} за {}",
				buyer.getScoreboardName(), order.stack().getHoverName().getString(), order.stack().getCount(),
				order.seller(), MoneyFormat.format(price));

		return new BuyResult(BuyStatus.BOUGHT, order, price);
	}

	/** Снимает лот продавца (используется командой {@code /ah cancel}). */
	public static MarketOrder cancelListing(MarketplaceState state, UUID playerId, int slot) {
		return state.removeOrder(playerId, slot);
	}

	/** Сообщает продавцу о покупке: онлайн — строкой и звуком, офлайн — через «отложенную продажу». */
	private static void notifySeller(MinecraftServer server, MarketplaceState state, UUID sellerId,
			ServerPlayer buyer, MarketOrder order, double price) {
		ServerPlayer seller = server.getPlayerList().getPlayer(sellerId);

		if (seller == null) {
			state.addPendingSale(sellerId, price);
			return;
		}

		seller.sendSystemMessage(Component.translatable("marketplace.gui.market.boughtBy",
				Component.literal(buyer.getScoreboardName()),
				order.stack().getHoverName(),
				Component.literal(MoneyFormat.format(price))).withStyle(ChatFormatting.GREEN));
		MarketSounds.orderSold(seller);
	}

	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	/**
	 * Выставить ордер в ячейку.
	 *
	 * <p>Выплата считается один раз здесь и запоминается в ордере: микро-дрейф и крупные ивенты
	 * на уже выставленный ордер не влияют (решение заказчика).
	 *
	 * @return созданный ордер или {@code null}, если ячейка занята/закрыта или стак пустой
	 */
	public static MarketOrder placeOrder(MarketplaceState state, UUID playerId, int slot, ItemStack stack,
			double markup, long now, RandomSource random, String seller) {
		if (stack.isEmpty() || slot < 0 || slot >= SLOTS || slot >= unlockedSlots(state, playerId)) {
			return null;
		}

		if (state.order(playerId, slot) != null) {
			return null;
		}

		double price = payoutFor(stack, markup);
		// Лот с наценкой ×1…×2 всегда продаёт виртуальный рынок: так решил заказчик и так же
		// работает проверка «нельзя купить в магазине и продать дороже».
		MarketOrder order = new MarketOrder(stack.copy(), price, markup,
				now + SaleTimes.ticksFor(stack, markup, random), true, seller);
		state.setOrder(playerId, slot, order);
		return order;
	}

	/** Снять ордер из ячейки. Предметы возвращает вызывающий. */
	public static MarketOrder cancelOrder(MarketplaceState state, UUID playerId, int slot) {
		return state.removeOrder(playerId, slot);
	}

	/**
	 * Завершает готовые ордера игрока: начисляет деньги и освобождает ячейки.
	 * Работает и для игроков, которых нет в игре.
	 */
	public static List<MarketOrder> completeReady(MinecraftServer server, MarketplaceState state,
			UUID playerId, long now) {
		Map<Integer, MarketOrder> playerOrders = state.orders(playerId);

		if (playerOrders.isEmpty()) {
			return List.of();
		}

		List<MarketOrder> sold = new ArrayList<>();

		for (int slot : List.copyOf(playerOrders.keySet())) {
			MarketOrder order = playerOrders.get(slot);

			if (order == null || !order.isReady(now)) {
				continue;
			}

			state.removeOrder(playerId, slot);
			MarketplaceEconomy.addBalance(server, playerId, order.payout());
			sold.add(order);
		}

		return sold;
	}

	/**
	 * Сколько дадут за стак при такой наценке (без учёта случайного времени продажи).
	 * Этой же формулой фиксируется выплата ордера в момент выставления.
	 */
	public static double payoutFor(ItemStack stack, double markup) {
		return MoneyFormat.round(ItemPrices.priceOf(stack) * markup);
	}

	/** Следующая наценка по кругу: x1 → x1,25 → x1,5 → x1,75 → x2 → x1. */
	public static double nextMarkup(double current) {
		double[] markups = markups();

		for (double markup : markups) {
			if (markup > current + 0.001) {
				return markup;
			}
		}

		return markups.length == 0 ? 1.0 : markups[0];
	}

	// --- Магазин: выбор игрока и покупка ---

	/** Сколько предметов можно выбрать в окне покупки. */
	public static final int[] QUANTITIES = {1, 4, 8, 16, 32, 64, 128, 256, 512, 1024};

	/**
	 * Что игрок выбрал в магазине: раздел, страница, предмет, количество и способ выдачи.
	 * Хранится в памяти: выбор не нужно сохранять в сейв, после перезапуска он сбрасывается.
	 */
	public record ShopState(int category, int page, ItemStack selected, int quantityIndex, boolean drop) {
		public static ShopState initial() {
			return new ShopState(0, 0, ItemStack.EMPTY, 0, false);
		}

		public ShopState withCategory(int value) {
			return new ShopState(value, 0, ItemStack.EMPTY, 1, false);
		}

		public ShopState withPage(int value) {
			return new ShopState(this.category, value, this.selected, this.quantityIndex, this.drop);
		}

		/** Выбрали предмет — количество и способ выдачи возвращаются к стандартным. */
		public ShopState withSelected(ItemStack stack) {
			return new ShopState(this.category, this.page, stack, 0, false);
		}

		public ShopState withQuantity(int value) {
			return new ShopState(this.category, this.page, this.selected, value, this.drop);
		}

		public ShopState withDrop(boolean value) {
			return new ShopState(this.category, this.page, this.selected, this.quantityIndex, value);
		}

		/** Сколько предметов покупаем. */
		public int count() {
			return this.selected.isEmpty() ? 0 : quantity();
		}

		/** Выбранное количество предметов. */
		public int quantity() {
			return QUANTITIES[Math.max(0, Math.min(this.quantityIndex, QUANTITIES.length - 1))];
		}

		/** Надпись с количеством: «32 шт.» или «2 стака». */
		public String quantityText() {
			int count = quantity();
			int maxStack = this.selected.isEmpty() ? 64
					: Math.max(1, this.selected.getItem().getDefaultMaxStackSize());

			if (count >= maxStack && count % maxStack == 0) {
				int stacks = count / maxStack;
				return stacks + (stacks == 1 ? " стак" : (stacks < 5 ? " стака" : " стаков"));
			}

			return count + " шт.";
		}
	}

	/** Выбор игрока в магазине. */
	private static final Map<UUID, ShopState> SHOP = new HashMap<>();

	public static ShopState shop(UUID playerId) {
		return SHOP.getOrDefault(playerId, ShopState.initial());
	}

	public static void setShop(UUID playerId, ShopState state) {
		SHOP.put(playerId, state);
	}

	/** Следующее количество по кругу. */
	public static int nextQuantity(ShopState state) {
		return (state.quantityIndex() + 1) % QUANTITIES.length;
	}

	/**
	 * Сколько стоит выбранное в магазине, без списания денег. Свой сет игрока считается по своей
	 * формуле ({@link CustomKits#priceOf}), готовый набор — по своей ({@link KitCatalog#priceOf}),
	 * «особый предмет» — по фиксированной цене, обычный предмет — по цене стака с множителем.
	 */
	public static double costOf(MarketplaceState world, ShopState state) {
		if (state.selected().isEmpty()) {
			return 0.0;
		}

		// Лот торговой площадки: платим цену продавца, количество к лотам не применяется.
		java.util.Optional<Listing> listing = listing(world, state.selected());

		if (listing.isPresent()) {
			return listing.get().order().payout();
		}

		java.util.Optional<CustomKit> custom = CustomKits.kitOf(world, state.selected());

		if (custom.isPresent()) {
			return MoneyFormat.round(CustomKits.priceOf(custom.get()));
		}

		java.util.Optional<KitCatalog.Kit> kit = KitCatalog.kitOf(state.selected());

		if (kit.isPresent()) {
			return MoneyFormat.round(KitCatalog.priceOf(kit.get()));
		}

		java.util.OptionalDouble special = SpecialCatalog.priceOf(state.selected());

		if (special.isPresent()) {
			// Цена «особого предмета» задана явно (ТЗ заказчика), поэтому множитель покупки
			// к ней не применяется — иначе книга стоила бы в 2,2 раза дороже заявленной.
			return MoneyFormat.round(special.getAsDouble() * state.count());
		}

		return MoneyFormat.round(ItemPrices.priceOf(state.selected())
				* EconomyConfig.buyMultiplier() * state.count());
	}

	/**
	 * Покупка: списывает деньги и выдаёт предметы. В креативе покупка бесплатная.
	 *
	 * <p>Набор («кит») выдаётся ровно один: цена набора считается за один набор и не умножается
	 * на выбранное количество, поэтому «пачку» наборов купить нельзя. «Особые предметы» (книги
	 * своих зачарований) идут обычным путём: цена у них своя, за штуку, а выдаётся столько книг,
	 * сколько выбрано.
	 *
	 * @return {@code false}, если денег не хватило
	 */
	public static boolean buy(MinecraftServer server, ServerPlayer player, ShopState state) {
		if (state.selected().isEmpty()) {
			return false;
		}

		MarketplaceState world = MarketplaceEconomy.state(server);

		// Лот торговой площадки: покупаем целиком по цене продавца.
		if (listing(world, state.selected()).isPresent()) {
			return buyListing(server, player, state.selected(), state.drop()).status() == BuyStatus.BOUGHT;
		}

		java.util.Optional<CustomKit> custom = CustomKits.kitOf(world, state.selected());
		int count = state.count();
		double cost = costOf(world, state);

		if (!player.isCreative() && !MarketplaceEconomy.trySpend(server, player.getUUID(), cost)) {
			return false;
		}

		if (custom.isPresent()) {
			// Свой сет игрока выдаётся ровно один: количество к наборам не применяется.
			CustomKits.deliver(player, custom.get());
			return true;
		}

		java.util.Optional<KitCatalog.Kit> kit = KitCatalog.kitOf(state.selected());

		if (kit.isPresent()) {
			// Шалкеры набора приходят с содержимым (раскладка — в kits.json), а те наборы,
			// у которых шлякеров нет, выдаются предметами в инвентарь.
			for (ItemStack piece : KitCatalog.deliver(kit.get())) {
				give(player, state, piece);
			}

			return true;
		}

		int maxStack = Math.max(1, state.selected().getItem().getDefaultMaxStackSize());
		int left = count;

		while (left > 0) {
			int part = Math.min(left, maxStack);
			ItemStack stack = state.selected().copyWithCount(part);
			left -= part;
			give(player, state, stack);
		}

		return true;
	}

	/** Выдаёт предмет так, как выбрал игрок: в инвентарь или на землю под ногами. */
	private static void give(ServerPlayer player, ShopState state, ItemStack stack) {
		give(player, state.drop(), stack);
	}

	/** Выдаёт предмет: в инвентарь или на землю (что не влезет в инвентарь — упадёт под игроком). */
	private static void give(ServerPlayer player, boolean drop, ItemStack stack) {
		if (drop) {
			player.drop(stack, false, Prediction.SERVER_ONLY);
		} else {
			player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
		}
	}

	/**
	 * Идёт ли переход между окнами рынка (например, из рынка в подтверждение).
	 * В этот момент нельзя возвращать неподтверждённые предметы — игрок ещё не вышел.
	 */
	private static boolean windowTransition;

	public static void setWindowTransition(boolean value) {
		windowTransition = value;
	}

	public static boolean isWindowTransition() {
		return windowTransition;
	}}
