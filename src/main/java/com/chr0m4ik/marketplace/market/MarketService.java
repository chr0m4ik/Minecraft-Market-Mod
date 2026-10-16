package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	/** Наценки, между которыми переключается игрок. */
	public static final double[] MARKUPS = {1.0, 1.25, 1.5, 1.75, 2.0};

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

	/**
	 * Выставить ордер в ячейку.
	 *
	 * @return созданный ордер или {@code null}, если ячейка занята/закрыта или стак пустой
	 */
	public static MarketOrder placeOrder(MarketplaceState state, UUID playerId, int slot, ItemStack stack,
			double markup, long now, RandomSource random) {
		if (stack.isEmpty() || slot < 0 || slot >= SLOTS || slot >= unlockedSlots(state, playerId)) {
			return null;
		}

		if (state.order(playerId, slot) != null) {
			return null;
		}

		MarketOrder order = new MarketOrder(stack.copy(), markup,
				now + SaleTimes.ticksFor(stack, markup, random));
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

	/** Сколько дадут за стак при такой наценке (без учёта случайного времени продажи). */
	public static double payoutFor(ItemStack stack, double markup) {
		return MoneyFormat.round(ItemPrices.priceOf(stack) * markup);
	}

	/** Следующая наценка по кругу: x1 → x1,25 → x1,5 → x1,75 → x2 → x1. */
	public static double nextMarkup(double current) {
		for (int i = 0; i < MARKUPS.length; i++) {
			if (MARKUPS[i] > current + 0.001) {
				return MARKUPS[i];
			}
		}

		return MARKUPS[0];
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
	 * Покупка: списывает деньги и выдаёт предметы. В креативе покупка бесплатная.
	 *
	 * @return {@code false}, если денег не хватило
	 */
	public static boolean buy(MinecraftServer server, ServerPlayer player, ShopState state) {
		if (state.selected().isEmpty()) {
			return false;
		}

		int count = state.count();
		java.util.Optional<KitCatalog.Kit> kit = KitCatalog.kitOf(state.selected());
		int amount = kit.isPresent() ? 1 : count;
		double cost = MoneyFormat.round((kit.isPresent()
				? KitCatalog.priceOf(kit.get())
				: ItemPrices.priceOf(state.selected()) * ItemPrices.BUY_MULTIPLIER) * amount);

		if (!player.isCreative() && !MarketplaceEconomy.trySpend(server, player.getUUID(), cost)) {
			return false;
		}

		int maxStack = Math.max(1, state.selected().getItem().getDefaultMaxStackSize());
		int left = count;

		while (left > 0) {
			int part = Math.min(left, maxStack);
			ItemStack stack = state.selected().copyWithCount(part);
			left -= part;

			for (ItemStack piece : kit.map(KitCatalog::deliver).orElse(java.util.List.of(stack))) {
				if (state.drop()) {
					player.drop(piece, false, Prediction.SERVER_ONLY);
				} else {
					player.getInventory().placeItemBackInInventory(piece, Prediction.SERVER_ONLY);
				}
			}
		}

		return true;
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
