package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.TraderPrices;
import com.chr0m4ik.marketplace.event.EventService;
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

public final class MarketplaceStatusSync {

	private static final int PERIOD_TICKS = 20;

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

			long seed = server.overworld().getSeed();
			long gameTime = GameTime.now(server);

			MarketplaceStatusPayload.EventData event = eventData();
			long eventRevision = EventService.revision();

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {

				if (!ServerPlayNetworking.canSend(player, MarketplaceStatusPayload.TYPE)) {
					continue;
				}

				long balance = toCents(MarketplaceEconomy.getBalance(server, player.getUUID()));
				long inventory = toCents(inventoryValue(player.getInventory()));
				long[] last = LAST_SENT.get(player.getUUID());
				boolean salesWereSent = last != null && last[2] != 0L;
				boolean salesNeedSending = !entries.isEmpty() || salesWereSent;
				boolean eventChanged = last == null || last[3] != eventRevision;

				if (last != null && last[0] == balance && last[1] == inventory && !salesNeedSending
						&& !eventChanged) {
					continue;
				}

				LAST_SENT.put(player.getUUID(), new long[] {balance, inventory,
						entries.isEmpty() ? 0L : 1L, eventRevision});
				ServerPlayNetworking.send(player,
						new MarketplaceStatusPayload(balance, inventory, entries, seed, gameTime, event));
			}
		});

		// Игрок вышел — забываем его состояние.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_SENT.remove(handler.getPlayer().getUUID()));
	}

	private static MarketplaceStatusPayload.EventData eventData() {
		return EventService.current()
				.map(event -> new MarketplaceStatusPayload.EventData(event.name().id(), event.category(),
						event.itemIds(), event.multiplier()))
				.orElse(MarketplaceStatusPayload.EventData.NONE);
	}

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
