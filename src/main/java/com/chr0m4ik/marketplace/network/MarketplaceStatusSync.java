package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Раз в секунду отправляет каждому игроку его состояние (баланс и стоимость инвентаря),
 * но только если оно изменилось — чтобы не гонять пакеты впустую.
 */
public final class MarketplaceStatusSync {
	/** Как часто проверяем состояние, в тиках. 20 тиков = 1 секунда. */
	private static final int PERIOD_TICKS = 20;

	/** Последнее отправленное состояние: UUID → [баланс, инвентарь] в копейках. */
	private static final Map<UUID, long[]> LAST_SENT = new HashMap<>();

	private MarketplaceStatusSync() {
		// Класс только со статическими методами.
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % PERIOD_TICKS != 0) {
				return;
			}

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				// Клиент без нашего мода пакет не поймёт — таким ничего не отправляем.
				if (!ServerPlayNetworking.canSend(player, MarketplaceStatusPayload.TYPE)) {
					continue;
				}

				long balance = toCents(MarketplaceEconomy.getBalance(server, player.getUUID()));
				long inventory = toCents(ItemPrices.priceOfInventory(player.getInventory()));
				long[] last = LAST_SENT.get(player.getUUID());

				if (last != null && last[0] == balance && last[1] == inventory) {
					continue;
				}

				LAST_SENT.put(player.getUUID(), new long[] {balance, inventory});
				ServerPlayNetworking.send(player, new MarketplaceStatusPayload(balance, inventory));
			}
		});

		// Игрок вышел — забываем его состояние.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_SENT.remove(handler.getPlayer().getUUID()));
	}

	private static long toCents(double money) {
		return Math.round(money * 100.0);
	}
}
