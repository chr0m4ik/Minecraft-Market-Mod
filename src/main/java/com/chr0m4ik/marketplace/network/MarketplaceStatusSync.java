package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.TraderPrices;
import com.chr0m4ik.marketplace.price.ItemPrices;
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
 * и закупки торговца (от них зависят его цены). Отправляем только если что-то изменилось —
 * но пока торговец что-то покупал, его цены восстанавливаются, поэтому пакет идёт каждый раз.
 */
public final class MarketplaceStatusSync {
	/** Как часто проверяем состояние, в тиках. 20 тиков = 1 секунда. */
	private static final int PERIOD_TICKS = 20;

	/** Последнее отправленное состояние: UUID → [баланс, инвентарь, были ли закупки]. */
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
			TraderPrices trader = state.traderSnapshot(GameTime.now(server));
			List<MarketplaceStatusPayload.SaleEntry> entries = saleEntries(trader);

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				// Клиент без нашего мода пакет не поймёт — таким ничего не отправляем.
				if (!ServerPlayNetworking.canSend(player, MarketplaceStatusPayload.TYPE)) {
					continue;
				}

				long balance = toCents(MarketplaceEconomy.getBalance(server, player.getUUID()));
				long inventory = toCents(inventoryValue(player.getInventory()));
				long[] last = LAST_SENT.get(player.getUUID());
				boolean salesWereSent = last != null && last[2] != 0L;
				boolean salesNeedSending = !entries.isEmpty() || salesWereSent;

				if (last != null && last[0] == balance && last[1] == inventory && !salesNeedSending) {
					continue;
				}

				LAST_SENT.put(player.getUUID(), new long[] {balance, inventory, entries.isEmpty() ? 0L : 1L});
				ServerPlayNetworking.send(player, new MarketplaceStatusPayload(balance, inventory, entries));
			}
		});

		// Игрок вышел — забываем его состояние.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_SENT.remove(handler.getPlayer().getUUID()));
	}

	/**
	 * Сколько дадут за всё, что лежит в инвентаре. Считаем по обычной цене: динамика торговца
	 * на рынок и покупку не влияет.
	 */
	private static double inventoryValue(Inventory inventory) {
		double total = 0.0;

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			total += ItemPrices.priceOf(inventory.getItem(slot));
		}

		return total;
	}

	private static List<MarketplaceStatusPayload.SaleEntry> saleEntries(TraderPrices trader) {
		if (trader.isEmpty()) {
			return List.of();
		}

		return trader.soldMap().entrySet().stream()
				.map(entry -> new MarketplaceStatusPayload.SaleEntry(
						BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), entry.getValue()))
				.toList();
	}

	private static long toCents(double money) {
		return Math.round(money * 100.0);
	}
}
