package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.menu.WindowKind;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class MarketplaceNetworking {
	private MarketplaceNetworking() {
		// Класс только со статическими методами.
	}

	public static void registerServer() {
		// Клиент → сервер: «нажал клавишу, открой рынок».
		PayloadTypeRegistry.serverboundPlay().register(OpenMarketplacePayload.TYPE, OpenMarketplacePayload.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(MarketplaceStatusPayload.TYPE, MarketplaceStatusPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(OpenMarketplacePayload.TYPE, (payload, context) ->
				MarketplaceMenus.open(context.player(), WindowKind.MAIN));

		MarketplaceStatusSync.register();
	}
}
