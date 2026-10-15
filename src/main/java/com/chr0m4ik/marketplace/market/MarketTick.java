package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Раз в секунду проверяет ордера на рынке: готовые продаёт, начисляет деньги и пишет в чат.
 * Игрок при этом может быть не в игре — деньги всё равно начислятся, а сообщение он увидит
 * при следующем входе (одной строкой-итогом).
 */
public final class MarketTick {
	/** Как часто проверяем ордера, в тиках. 20 тиков = 1 секунда. */
	private static final int PERIOD_TICKS = 20;

	private MarketTick() {
		// Класс только со статическими методами.
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % PERIOD_TICKS != 0) {
				return;
			}

			MarketplaceState state = MarketplaceEconomy.state(server);
			long now = GameTime.now(server);

			for (UUID playerId : state.playersWithOrders()) {
				List<MarketOrder> sold = MarketService.completeReady(server, state, playerId, now);

				if (sold.isEmpty()) {
					continue;
				}

				ServerPlayer online = server.getPlayerList().getPlayer(playerId);
				double total = 0.0;

				for (MarketOrder order : sold) {
					total += order.payout();

					if (online != null) {
						online.sendSystemMessage(Component.translatable("marketplace.gui.market.sold",
								Component.literal(String.valueOf(order.stack().getCount())),
								order.stack().getHoverName(),
								Component.literal(MoneyFormat.format(order.payout())))
								.withStyle(ChatFormatting.GREEN));
					}
				}

				if (online == null) {
					state.addPendingSale(playerId, total);
				}
			}
		});

		// Игрок зашёл — рассказываем, что продалось, пока его не было.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			MarketplaceState state = MarketplaceEconomy.state(server);
			double[] pending = state.takePendingSales(handler.getPlayer().getUUID());

			if (pending == null || pending[0] <= 0.0) {
				return;
			}

			handler.getPlayer().sendSystemMessage(Component.translatable("marketplace.gui.market.offline",
					Component.literal(String.valueOf((int) pending[0])),
					Component.literal(MoneyFormat.format(pending[1]))).withStyle(ChatFormatting.GOLD));
		});
	}
}
