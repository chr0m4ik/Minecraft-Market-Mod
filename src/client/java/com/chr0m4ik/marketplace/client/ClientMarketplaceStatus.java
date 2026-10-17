package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.economy.TraderPrices;
import com.chr0m4ik.marketplace.event.EventService;
import com.chr0m4ik.marketplace.event.MarketEvent;
import com.chr0m4ik.marketplace.network.MarketplaceStatusPayload;
import com.chr0m4ik.marketplace.price.PriceDrift;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ClientMarketplaceStatus {

	private static boolean hasData;

	private static long balanceCents;
	private static long inventoryCents;
	private static TraderPrices traderPrices = TraderPrices.empty();

	private static long worldSeed;
	private static long gameTime;

	private ClientMarketplaceStatus() {
		// Класс только со статическими методами.
	}

	public static void update(long balance, long inventory, List<MarketplaceStatusPayload.SaleEntry> entries,
			long seed, long time, MarketplaceStatusPayload.EventData event) {
		balanceCents = balance;
		inventoryCents = inventory;
		traderPrices = toTraderPrices(entries);
		worldSeed = seed;
		gameTime = time;
		hasData = true;

		PriceDrift.update(worldSeed, gameTime);

		applyEvent(event);
	}

	public static void clear() {
		hasData = false;
		balanceCents = 0L;
		inventoryCents = 0L;
		traderPrices = TraderPrices.empty();
		worldSeed = 0L;
		gameTime = 0L;
		PriceDrift.clear();
		EventService.clearCurrent();
	}

	private static void applyEvent(MarketplaceStatusPayload.EventData data) {
		if (!data.active()) {
			EventService.clearCurrent();
			return;
		}

		Optional<MarketEvent> event = MarketEvent.fromNetwork(data.name(), data.category(), data.items(),
				data.multiplier());

		if (event.isPresent()) {
			EventService.applyFromServer(event.get());
		} else {
			// MarketplaceMod.LOGGER.warn("С сервера пришёл непонятный ивент «{}» — считаю, что ивента нет",
					// data.name());
			EventService.clearCurrent();
		}
	}

	private static TraderPrices toTraderPrices(List<MarketplaceStatusPayload.SaleEntry> entries) {
		if (entries.isEmpty()) {
			return TraderPrices.empty();
		}

		Map<Item, Double> sold = new HashMap<>();

		for (MarketplaceStatusPayload.SaleEntry entry : entries) {
			BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.itemId()))
					.ifPresent(item -> sold.put(item, entry.stacks()));
		}

		return TraderPrices.of(sold);
	}

	public static boolean hasData() {
		return hasData;
	}

	public static double balance() {
		return balanceCents / 100.0;
	}

	public static double inventoryValue() {
		return inventoryCents / 100.0;
	}

	public static TraderPrices traderPrices() {
		return traderPrices;
	}
}
