package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.Diag;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * ВРЕМЕННО (диагностика проблемы «заказчик не видит наши книги в магазине»).
 *
 * <p>Пишет в лог клиента понятную строку при каждом открытии раздела магазина: что за сборка
 * играет и сколько предметов реально доехало до окна, включая число наших книг. Заказчику
 * достаточно открыть раздел и прислать эту строку — по ней сразу видно, пусто ли в разделе
 * (тогда рядом будет {@code ERROR}) или он смотрит в другой раздел.
 *
 * <p>Строка пишется не в момент открытия окна, а через {@link #WAIT_TICKS} тиков: содержимое
 * слотов и данные окна приходят от сервера отдельными пакетами, и до них считать нечего.
 * Повторно по тому же разделу и той же странице строка не пишется.
 */
public final class ShopSectionLog {
	/** Сколько тиков подождать после открытия окна, прежде чем считать содержимое слотов. */
	private static final int WAIT_TICKS = 10;

	private static int containerId = -1;
	private static int category = -1;
	private static int page = -1;
	private static int ticks;

	private ShopSectionLog() {
		// Класс только со статическими методами.
	}

	/** Подключает наблюдение за окном магазина. */
	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ShopSectionLog::tick);
	}

	private static void tick(Minecraft client) {
		if (client.player == null || !(client.player.containerMenu instanceof MarketplaceMenu menu)
				|| menu.getKind() != WindowKind.BUY_LIST) {
			reset();
			return;
		}

		ticks++;

		if (ticks < WAIT_TICKS) {
			return;
		}

		if (menu.containerId == containerId && menu.getShopCategory() == category
				&& menu.getShopPage() == page) {
			return;
		}

		containerId = menu.containerId;
		category = menu.getShopCategory();
		page = menu.getShopPage();
		Diag.logShopWindow("КЛИЕНТ", menu);
	}

	private static void reset() {
		containerId = -1;
		category = -1;
		page = -1;
		ticks = 0;
	}
}
