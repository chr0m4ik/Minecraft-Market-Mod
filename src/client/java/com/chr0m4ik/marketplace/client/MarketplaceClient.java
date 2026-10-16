package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.client.hud.MoneyHudElement;
import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.chr0m4ik.marketplace.client.tooltip.MarketplaceTooltips;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.network.MarketplaceStatusPayload;
import com.chr0m4ik.marketplace.network.OpenMarketplacePayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * Точка входа клиентской части. Здесь всё, что связано с экранами, клавишами, HUD и рендером.
 */
public class MarketplaceClient implements ClientModInitializer {
	/** Своя группа в настройках управления, чтобы клавиша не терялась среди ванильных. */
	public static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(MarketplaceMod.id("main"));

	/** Клавиша открытия рынка (по умолчанию I). */
	private static KeyMapping openMenuKey;

	@Override
	public void onInitializeClient() {
		MarketplaceMod.LOGGER.info("Marketplace: клиентская часть загружена");

		openMenuKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.marketplace.open", InputConstants.KEY_I, KEY_CATEGORY));

		// Каждому виду окна — свой экран (класс один, размеры он берёт из вида окна).
		MenuScreens.register(MarketplaceMenus.MAIN, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.TRADER, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.SELL, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.CONFIRM, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY_SLOT, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY_LIST, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY_ITEM, MarketplaceScreen::new);

		// Плашка с деньгами поверх игры (правый верхний угол).
		HudElementRegistry.addLast(MarketplaceMod.id("money"), new MoneyHudElement());

		// Цены в подсказках предметов.
		MarketplaceTooltips.register();

		// Состояние с сервера: баланс, стоимость инвентаря, закупки торговца,
		// а также зерно мира и игровое время — по ним считается микро-дрейф цен —
		// и описание активного крупного ивента: его множитель клиент считает сам.
		ClientPlayNetworking.registerGlobalReceiver(MarketplaceStatusPayload.TYPE, (payload, context) ->
				ClientMarketplaceStatus.update(payload.balanceCents(), payload.inventoryCents(),
						payload.traderSales(), payload.worldSeed(), payload.gameTime(), payload.event()));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientMarketplaceStatus.clear());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenuKey.consumeClick()) {
				// Отправляем только если сервер умеет принимать наш пакет.
				if (client.player != null && ClientPlayNetworking.canSend(OpenMarketplacePayload.TYPE)) {
					// Окно создаёт сервер — клиент только просит его открыть.
					ClientPlayNetworking.send(new OpenMarketplacePayload());
				}
			}
		});
	}
}
