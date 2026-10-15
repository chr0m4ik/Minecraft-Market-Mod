package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
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
}
