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

public class MarketplaceClient implements ClientModInitializer {

	public static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(MarketplaceMod.id("main"));

	private static KeyMapping openMenuKey;

	@Override
	public void onInitializeClient() {
		// MarketplaceMod.LOGGER.info("Marketplace: клиентская часть загружена");

		openMenuKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.marketplace.open", InputConstants.KEY_I, KEY_CATEGORY));

		MenuScreens.register(MarketplaceMenus.MAIN, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.TRADER, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.SELL, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.CONFIRM, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY_SLOT, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY_LIST, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.BUY_ITEM, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.QUESTS, MarketplaceScreen::new);
		MenuScreens.register(MarketplaceMenus.ENDER_CHEST, MarketplaceScreen::new);

		HudElementRegistry.addLast(MarketplaceMod.id("money"), new MoneyHudElement());

		// Цены в подсказках предметов.
		MarketplaceTooltips.register();

		ClientPlayNetworking.registerGlobalReceiver(MarketplaceStatusPayload.TYPE, (payload, context) ->
				ClientMarketplaceStatus.update(payload.balanceCents(), payload.inventoryCents(),
						payload.traderSales(), payload.worldSeed(), payload.gameTime(), payload.event()));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientMarketplaceStatus.clear());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenuKey.consumeClick()) {

				if (client.player != null && ClientPlayNetworking.canSend(OpenMarketplacePayload.TYPE)) {

					ClientPlayNetworking.send(new OpenMarketplacePayload());
				}
			}
		});
	}
}
