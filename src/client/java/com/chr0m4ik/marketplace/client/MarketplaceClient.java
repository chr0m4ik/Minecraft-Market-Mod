package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.fabricmc.api.ClientModInitializer;

/**
 * Точка входа клиентской части. Выполняется только на клиенте.
 * Сюда попадёт всё, что связано с окнами (GUI), кнопками и подсказками предметов.
 */
public class MarketplaceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MarketplaceMod.LOGGER.info("Marketplace: клиентская часть загружена");
	}
}
