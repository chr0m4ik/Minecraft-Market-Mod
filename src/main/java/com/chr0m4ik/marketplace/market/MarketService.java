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

	/** Сколько стаков можно выбрать в окне покупки (0 = половина стака). */
	public static final int[] QUANTITIES = {0, 1, 2, 5, 10, 25};

	/**
	 * Что игрок выбрал в магазине: раздел, страница, предмет, количество и способ выдачи.
	 * Хранится в памяти: выбор не нужно сохранять в сейв, после перезапуска он сбрасывается.
	 */
	public record ShopState(int category, int page, ItemStack selected, int quantityIndex, boolean drop) {
		public static ShopState initial() {
			return new ShopState(0, 0, ItemStack.EMPTY, 1, false);
		}

		public ShopState withCategory(int value) {
			return new ShopState(value, 0, ItemStack.EMPTY, 1, false);
		}

		public ShopState withPage(int value) {
			return new ShopState(this.category, value, this.selected, this.quantityIndex, this.drop);
		}

		/** Выбрали предмет — количество и способ выдачи возвращаются к стандартным. */
		public ShopState withSelected(ItemStack stack) {
			return new ShopState(this.category, this.page, stack, 1, false);
		}

		public ShopState withQuantity(int value) {
			return new ShopState(this.category, this.page, this.selected, value, this.drop);
		}

		public ShopState withDrop(boolean value) {
			return new ShopState(this.category, this.page, this.selected, this.quantityIndex, value);
		}

		/** Сколько предметов покупаем: количество стаков × размер стака. */
		public int count() {
			if (this.selected.isEmpty()) {
				return 0;
			}

			int maxStack = Math.max(1, this.selected.getItem().getDefaultMaxStackSize());
			int stacks = QUANTITIES[Math.max(0, Math.min(this.quantityIndex, QUANTITIES.length - 1))];
			return stacks == 0 ? Math.max(1, maxStack / 2) : stacks * maxStack;
		}

		/** Доступно ли «половина стака»: для нестакающихся предметов — нет. */
		public boolean halfStackAllowed() {
			return !this.selected.isEmpty() && this.selected.getItem().getDefaultMaxStackSize() > 1;
		}

		/** Надпись с количеством: «0.5», «1», «25». */
		public String quantityText() {
			int stacks = QUANTITIES[Math.max(0, Math.min(this.quantityIndex, QUANTITIES.length - 1))];
			return stacks == 0 ? "0.5" : String.valueOf(stacks);
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

	/** Следующее количество по кругу; «половина стака» пропускается для нестакающихся. */
	public static int nextQuantity(ShopState state) {
		int next = state.quantityIndex();

		for (int i = 0; i < QUANTITIES.length; i++) {
			next = (next + 1) % QUANTITIES.length;

			if (next != 0 || state.halfStackAllowed()) {
				break;
			}
		}

		return next;
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
		double cost = MoneyFormat.round(ItemPrices.priceOf(state.selected()) * count * ItemPrices.BUY_MULTIPLIER);

		if (!player.isCreative() && !MarketplaceEconomy.trySpend(server, player.getUUID(), cost)) {
			return false;
		}

		int maxStack = Math.max(1, state.selected().getItem().getDefaultMaxStackSize());
		int left = count;

		while (left > 0) {
			int part = Math.min(left, maxStack);
			ItemStack stack = state.selected().copyWithCount(part);
			left -= part;

			if (state.drop()) {
				player.drop(stack, false, Prediction.SERVER_ONLY);
			} else {
				player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
			}
		}

		return true;
	}
}
