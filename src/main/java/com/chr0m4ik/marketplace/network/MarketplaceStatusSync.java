package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MarketPrices;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Раз в секунду отправляет каждому игроку его состояние: баланс, стоимость инвентаря
 * и текущее состояние рынка (что и сколько продано). Отправляем только если что-то изменилось —
 * но пока на рынке есть продажи, цены восстанавливаются, поэтому пакет идёт каждый раз.
 */
public final class MarketplaceStatusSync {
	/** Как часто проверяем состояние, в тиках. 20 тиков = 1 секунда. */
	private static final int PERIOD_TICKS = 20;

	/** Последнее отправленное состояние: UUID → [баланс, инвентарь, был ли рынок непустым]. */
	private static final Map<UUID, long[]> LAST_SENT = new HashMap<>();

	private MarketplaceStatusSync() {
		// Класс только со статическими методами.
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % PERIOD_TICKS != 0) {
				return;
			}

			MarketplaceState state = MarketplaceEconomy.state(server);
			MarketPrices market = state.marketSnapshot(GameTime.now(server));
			List<MarketplaceStatusPayload.MarketEntry> entries = marketEntries(market);

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				// Клиент без нашего мода пакет не поймёт — таким ничего не отправляем.
				if (!ServerPlayNetworking.canSend(player, MarketplaceStatusPayload.TYPE)) {
					continue;
				}

				long balance = toCents(MarketplaceEconomy.getBalance(server, player.getUUID()));
				long inventory = toCents(inventoryValue(market, player.getInventory()));
				long[] last = LAST_SENT.get(player.getUUID());
				boolean marketWasSent = last != null && last[2] != 0L;
				boolean marketNeedsSending = !entries.isEmpty() || marketWasSent;

				if (last != null && last[0] == balance && last[1] == inventory && !marketNeedsSending) {
					continue;
				}

				LAST_SENT.put(player.getUUID(), new long[] {balance, inventory, entries.isEmpty() ? 0L : 1L});
				ServerPlayNetworking.send(player,
						new MarketplaceStatusPayload(balance, inventory, entries));
			}
		});

		// Игрок вышел — забываем его состояние.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_SENT.remove(handler.getPlayer().getUUID()));
	}

	/** Сколько дадут за всё, что лежит в инвентаре, по цене продажи за 1x. */
	private static double inventoryValue(MarketPrices market, Inventory inventory) {
		double total = 0.0;

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			total += market.saleProceeds(inventory.getItem(slot), 1.0);
		}

		return total;
	}

	private static List<MarketplaceStatusPayload.MarketEntry> marketEntries(MarketPrices market) {
		if (market.isEmpty()) {
			return List.of();
		}

		return market.soldMap().entrySet().stream()
				.map(entry -> new MarketplaceStatusPayload.MarketEntry(
						BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), entry.getValue()))
				.toList();
	}

	private static long toCents(double money) {
		return Math.round(money * 100.0);
	}
}
