package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.price.TraderLimits;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сделка с торговцем.
 *
 * <p>Торговец платит по своим ценам ({@link TraderPrices}: 75% от базовой плюс его надбавки
 * и уценки) и за один игровой день берёт ограниченное количество каждого предмета
 * (см. {@link TraderLimits}).
 *
 * <p>Содержимое контейнеров считается полностью: если сдать шалкер с 10 стаками земли,
 * то и шалкер, и земля получат свою цену, а дневной лимит уменьшится и на 1 шалкер, и на 10 стаков
 * земли. Поэтому такой шалкер продаётся целиком или не продаётся вовсе — вынуть часть нельзя.
 */
public final class TraderService {
	/** Насколько глубоко разворачиваем содержимое контейнеров (защита от зацикливания). */
	private static final int MAX_CONTENT_DEPTH = 8;

	/** Что выставлено на продажу. */
	public record Offer(int itemCount, double normalValue, double payout) {
		public boolean isEmpty() {
			return this.itemCount == 0;
		}
	}

	/** Чем закончилась сделка. */
	public record Sale(int itemCount, double payout, boolean limitReached) {
		public boolean isEmpty() {
			return this.itemCount == 0;
		}
	}

	private TraderService() {
		// Класс только со статическими методами.
	}

	/** Считает выставленный товар по текущим ценам торговца, ничего не забирая. */
	public static Offer evaluate(TraderPrices trader, Container container, int slots) {
		int count = 0;
		double normalValue = 0.0;
		double payout = 0.0;

		for (int i = 0; i < slots; i++) {
			ItemStack stack = container.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			count += stack.getCount();
			normalValue += trader.price(stack);
			payout += trader.payout(stack);
		}

		return new Offer(count, normalValue, payout);
	}

	/**
	 * Продаёт торговцу всё, что лежит в контейнере, соблюдая дневной лимит.
	 * То, что в лимит не влезло, остаётся в контейнере — предметы не пропадают.
	 */
	public static Sale sell(MinecraftServer server, ServerPlayer player, Container container, int slots) {
		MarketplaceState state = MarketplaceEconomy.state(server);
		UUID playerId = player.getUUID();
		long now = GameTime.now(server);
		state.refreshTraderDay(playerId, GameTime.day(server));

		// Цены берём один раз до сделки: внутри одной сделки они не меняются.
		TraderPrices trader = state.traderSnapshot(now);

		int soldItems = 0;
		double payout = 0.0;
		boolean limitReached = false;

		for (int slot = 0; slot < slots; slot++) {
			ItemStack stack = container.getItem(slot);

			if (stack.isEmpty()) {
				continue;
			}

			Map<Item, Double> cost = limitCost(stack);
			boolean hasContents = cost.size() > 1;
			int sellCount = allowedCount(state, playerId, stack, cost, hasContents);

			if (sellCount <= 0) {
				limitReached = true;
				continue;
			}

			if (sellCount < stack.getCount()) {
				limitReached = true;

				// Контейнер с содержимым целиком не влез в лимит — не продаём его частями.
				if (hasContents) {
					continue;
				}
			}

			ItemStack selling = stack.copyWithCount(sellCount);
			payout += trader.payout(selling);
			soldItems += sellCount;

			for (Map.Entry<Item, Double> entry : limitCost(selling).entrySet()) {
				state.addSoldStacks(playerId, entry.getKey(), entry.getValue());
				state.addTraderSale(entry.getKey(), entry.getValue(), now);
			}

			int rest = stack.getCount() - sellCount;
			container.setItem(slot, rest > 0 ? stack.copyWithCount(rest) : ItemStack.EMPTY);
		}

		payout = MoneyFormat.round(payout);

		if (payout > 0.0) {
			MarketplaceEconomy.addBalance(server, playerId, payout);
		}

		return new Sale(soldItems, payout, limitReached);
	}

	/**
	 * Сколько предметов из стака торговец готов взять с учётом дневного лимита.
	 * Контейнер с содержимым берётся только целиком: вынуть из него часть нельзя.
	 */
	private static int allowedCount(MarketplaceState state, UUID playerId, ItemStack stack,
			Map<Item, Double> cost, boolean hasContents) {
		if (hasContents) {
			for (Map.Entry<Item, Double> entry : cost.entrySet()) {
				if (remainingStacks(state, playerId, entry.getKey()) + 1.0E-9 < entry.getValue()) {
					return 0;
				}
			}

			return stack.getCount();
		}

		double maxStack = Math.max(1, stack.getItem().getDefaultMaxStackSize());
		int allowed = (int) Math.floor(remainingStacks(state, playerId, stack.getItem()) * maxStack);
		return Math.min(stack.getCount(), allowed);
	}

	private static double remainingStacks(MarketplaceState state, UUID playerId, Item item) {
		return TraderLimits.limitOf(item) - state.soldStacks(playerId, item);
	}

	/**
	 * Сколько стаков каждого предмета «стоит» продажа этого стака: сам предмет плюс всё, что лежит
	 * внутри контейнеров (шалкеров, бочек, мешков). Нестакающиеся предметы считаются как один стак
	 * за штуку, поэтому шалкер — это 1 стак, а 10 стаков земли внутри него — ещё 10.
	 */
	public static Map<Item, Double> limitCost(ItemStack stack) {
		Map<Item, Double> cost = new HashMap<>();
		collectLimitCost(stack, cost, 0);
		return cost;
	}

	private static void collectLimitCost(ItemStack stack, Map<Item, Double> cost, int depth) {
		if (stack.isEmpty() || depth > MAX_CONTENT_DEPTH) {
			return;
		}

		double maxStack = Math.max(1, stack.getItem().getDefaultMaxStackSize());
		cost.merge(stack.getItem(), stack.getCount() / maxStack, Double::sum);

		ItemContainerContents container = stack.get(DataComponents.CONTAINER);

		if (container != null) {
			for (ItemStack inside : container.itemCopies().toList()) {
				collectLimitCost(inside, cost, depth + 1);
			}
		}

		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);

		if (bundle != null) {
			for (ItemStack inside : bundle.itemCopies().toList()) {
				collectLimitCost(inside, cost, depth + 1);
			}
		}
	}
}
