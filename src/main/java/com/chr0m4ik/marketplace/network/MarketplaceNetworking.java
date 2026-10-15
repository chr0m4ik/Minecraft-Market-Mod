package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.menu.WindowKind;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Сетевая часть мода: регистрация пакетов и обработчиков на сервере.
 *
 * <p>Сейчас пакет один — «клиент нажал клавишу открытия рынка».
 */
public final class MarketplaceNetworking {
	private MarketplaceNetworking() {
		// Класс только со статическими методами.
	}

	public static void registerServer() {
		// Регистрируем тип пакета: клиент → сервер.
		PayloadTypeRegistry.serverboundPlay().register(OpenMarketplacePayload.TYPE, OpenMarketplacePayload.CODEC);

		// И обработчик: открыть игроку главное меню рынка.
		ServerPlayNetworking.registerGlobalReceiver(OpenMarketplacePayload.TYPE, (payload, context) ->
				MarketplaceMenus.open(context.player(), WindowKind.MAIN));
	}
}
