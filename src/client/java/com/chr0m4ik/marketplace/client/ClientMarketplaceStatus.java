package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.economy.MarketPrices;
import com.chr0m4ik.marketplace.network.MarketplaceStatusPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Последнее состояние, полученное с сервера: баланс, стоимость инвентаря и рынок.
 * Нужно плашке на HUD и подсказкам предметов (клиент считает цены теми же формулами).
 */
public final class ClientMarketplaceStatus {
	/** Пока данных с сервера нет, плашку не показываем. */
	private static boolean hasData;

	private static long balanceCents;
	private static long inventoryCents;
	private static MarketPrices market = MarketPrices.empty();

	private ClientMarketplaceStatus() {
		// Класс только со статическими методами.
	}

	public static void update(long balance, long inventory, List<MarketplaceStatusPayload.MarketEntry> entries) {
		balanceCents = balance;
		inventoryCents = inventory;
		market = toMarket(entries);
		hasData = true;
	}

	/** Вызывается при выходе из мира, чтобы не показывать старые числа в меню. */
	public static void clear() {
		hasData = false;
		balanceCents = 0L;
		inventoryCents = 0L;
		market = MarketPrices.empty();
	}

	private static MarketPrices toMarket(List<MarketplaceStatusPayload.MarketEntry> entries) {
		if (entries.isEmpty()) {
			return MarketPrices.empty();
		}

		Map<Item, Double> sold = new HashMap<>();

		for (MarketplaceStatusPayload.MarketEntry entry : entries) {
			BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.itemId()))
					.ifPresent(item -> sold.put(item, entry.stacks()));
		}

		return MarketPrices.of(sold);
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

	public static MarketPrices market() {
		return market;
	}
}
