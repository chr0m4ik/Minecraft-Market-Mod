package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.price.TraderLimits;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Сделка с торговцем.
 *
 * <p>Торговец платит {@code цена × }{@link ItemPrices#TRADER_MULTIPLIER} (75% от базовой),
 * с поправкой на состояние рынка ({@link MarketPrices}): то, что игрок уже продавал, дешевеет,
 * а остальное дорожает. За один игровой день торговец берёт ограниченное количество каждого
 * предмета (см. {@link TraderLimits}).
 */
public final class TraderService {
	/** Что выставлено на продажу. */
	public record Offer(int itemCount, double marketValue, double proceeds) {
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

	/** Считает выставленный товар по текущему рынку, ничего не забирая. */
	public static Offer evaluate(MarketPrices market, Container container, int slots) {
		int count = 0;
		double marketValue = 0.0;
		double proceeds = 0.0;

		for (int i = 0; i < slots; i++) {
			ItemStack stack = container.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			count += stack.getCount();
			marketValue += market.marketPrice(stack);
			proceeds += market.saleProceeds(stack, ItemPrices.TRADER_MULTIPLIER);
		}

		return new Offer(count, marketValue, proceeds);
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
		MarketPrices market = state.marketSnapshot(now);

		int soldItems = 0;
		double payout = 0.0;
		boolean limitReached = false;

		for (int i = 0; i < slots; i++) {
			ItemStack stack = container.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			Item item = stack.getItem();
			double maxStack = Math.max(1, item.getDefaultMaxStackSize());
			double remainingStacks = TraderLimits.limitOf(item) - state.soldStacks(playerId, item);
			int allowed = (int) Math.floor(remainingStacks * maxStack);
			int sellCount = Math.min(stack.getCount(), allowed);

			if (sellCount <= 0) {
				limitReached = true;
				continue;
			}

			if (sellCount < stack.getCount()) {
				limitReached = true;
			}

			payout += market.saleProceeds(stack.copyWithCount(sellCount), ItemPrices.TRADER_MULTIPLIER);
			soldItems += sellCount;

			double soldStacks = (double) sellCount / maxStack;
			state.addSoldStacks(playerId, item, soldStacks);
			state.addMarketSale(item, soldStacks, now);

			int rest = stack.getCount() - sellCount;
			container.setItem(i, rest > 0 ? stack.copyWithCount(rest) : ItemStack.EMPTY);
		}

		payout = MoneyFormat.round(payout);

		if (payout > 0.0) {
			MarketplaceEconomy.addBalance(server, playerId, payout);
		}

		return new Sale(soldItems, payout, limitReached);
	}
}
