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

/**
 * Последнее состояние, полученное с сервера: баланс, стоимость инвентаря и закупки торговца.
 * Нужно плашке на HUD и подсказкам предметов в окне торговца (цены считаются теми же формулами).
 *
 * <p>Здесь же храним зерно мира и игровое время с сервера: по ним считается микро-дрейф цен,
 * поэтому подсказки на клиенте совпадают с тем, что заплатит сервер.
 */
public final class ClientMarketplaceStatus {
	/** Пока данных с сервера нет, плашку не показываем. */
	private static boolean hasData;

	private static long balanceCents;
	private static long inventoryCents;
	private static TraderPrices traderPrices = TraderPrices.empty();

	/** Зерно мира и игровое время сервера — для микро-дрейфа цен. */
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

		// Дрейф считаем по серверным данным: тогда клиент и сервер дают одно и то же число.
		PriceDrift.update(worldSeed, gameTime);

		// Ивент тоже берём с сервера и считаем его множитель тем же кодом, что и сервер.
		applyEvent(event);
	}

	/** Вызывается при выходе из мира, чтобы не показывать старые числа в меню. */
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

	/**
	 * Кладёт описание ивента с сервера в {@link EventService} — оттуда его берут и подсказки,
	 * и любая другая цена на клиенте. Незнакомое название (чужой мод или старый клиент) —
	 * считаем, что ивента нет, и не ломаем цены.
	 */
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
			MarketplaceMod.LOGGER.warn("С сервера пришёл непонятный ивент «{}» — считаю, что ивента нет",
					data.name());
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

	/** Цены торговца: только он зависит от того, что у него покупали. */
	public static TraderPrices traderPrices() {
		return traderPrices;
	}
}
