package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.economy.TraderPrices;
import com.chr0m4ik.marketplace.network.MarketplaceStatusPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Последнее состояние, полученное с сервера: баланс, стоимость инвентаря и закупки торговца.
 * Нужно плашке на HUD и подсказкам предметов в окне торговца (цены считаются теми же формулами).
 */
public final class ClientMarketplaceStatus {
	/** Пока данных с сервера нет, плашку не показываем. */
	private static boolean hasData;

	private static long balanceCents;
	private static long inventoryCents;
	private static TraderPrices traderPrices = TraderPrices.empty();

	private ClientMarketplaceStatus() {
		// Класс только со статическими методами.
	}

	public static void update(long balance, long inventory, List<MarketplaceStatusPayload.SaleEntry> entries) {
		balanceCents = balance;
		inventoryCents = inventory;
		traderPrices = toTraderPrices(entries);
		hasData = true;
	}

	/** Вызывается при выходе из мира, чтобы не показывать старые числа в меню. */
	public static void clear() {
		hasData = false;
		balanceCents = 0L;
		inventoryCents = 0L;
		traderPrices = TraderPrices.empty();
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

	/** Цены торговца: только он зависит от того, что у него покупали. */
	public static TraderPrices traderPrices() {
		return traderPrices;
	}
}
