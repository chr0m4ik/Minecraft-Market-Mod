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
 * <p>Торговец платит {@code цена × }{@link ItemPrices#TRADER_MULTIPLIER} — то есть 75% от базовой
 * цены — и за один игровой день берёт ограниченное количество каждого предмета
 * (см. {@link TraderLimits}). Здесь только расчёт и выдача товара, никакого интерфейса.
 */
public final class TraderService {
	/** Длительность игрового дня в тиках. */
	private static final long TICKS_PER_DAY = 24_000L;

	/** Что выставлено на продажу. */
	public record Offer(int itemCount, double fullPrice) {
		/** Сколько заплатит торговец (с уценкой). */
		public double payout() {
			return MoneyFormat.round(this.fullPrice * ItemPrices.TRADER_MULTIPLIER);
		}

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

	/** Считает выставленный товар, ничего не забирая. */
	public static Offer evaluate(Container container, int slots) {
		int count = 0;
		double price = 0.0;

		for (int i = 0; i < slots; i++) {
			ItemStack stack = container.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			count += stack.getCount();
			price += ItemPrices.priceOf(stack);
		}

		return new Offer(count, price);
	}

	/**
	 * Продаёт торговцу всё, что лежит в контейнере, соблюдая дневной лимит.
	 * То, что в лимит не влезло, остаётся в контейнере — предметы не пропадают.
	 */
	public static Sale sell(MinecraftServer server, ServerPlayer player, Container container, int slots) {
		MarketplaceState state = MarketplaceEconomy.state(server);
		UUID playerId = player.getUUID();
		state.refreshTraderDay(playerId, currentDay(server));

		int soldItems = 0;
		double fullPrice = 0.0;
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

			fullPrice += ItemPrices.priceOf(stack.copyWithCount(sellCount));
			soldItems += sellCount;
			state.addSoldStacks(playerId, item, (double) sellCount / maxStack);

			int rest = stack.getCount() - sellCount;
			container.setItem(i, rest > 0 ? stack.copyWithCount(rest) : ItemStack.EMPTY);
		}

		double payout = MoneyFormat.round(fullPrice * ItemPrices.TRADER_MULTIPLIER);

		if (payout > 0.0) {
			MarketplaceEconomy.addBalance(server, playerId, payout);
		}

		return new Sale(soldItems, payout, limitReached);
	}

	/** Номер текущего игрового дня. Считается по времени мира, поэтому переживает перезапуск. */
	public static long currentDay(MinecraftServer server) {
		return server.overworld().getLevelData().getGameTime() / TICKS_PER_DAY;
	}
}
