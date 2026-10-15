package com.chr0m4ik.marketplace;

import com.chr0m4ik.marketplace.command.MarketplaceCommands;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.network.MarketplaceNetworking;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа мода. Выполняется один раз при загрузке игры (и на клиенте, и на сервере).
 * Здесь регистрируем общие вещи: команды, обработчики событий, загрузку цен и китов.
 */
public class MarketplaceMod implements ModInitializer {
	/** Внутренний id мода. Совпадает с id в fabric.mod.json. */
	public static final String MOD_ID = "marketplace";

	/** Логгер. Все сообщения мода пишутся в консоль и в run/logs/latest.log. */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Loading Minecraft Marketplace Mod");

		MarketplaceCommands.register();
		MarketplaceMenus.initialize();
		MarketplaceNetworking.registerServer();

		// Прайс-лист читается из ресурсов мода при старте сервера.
		ServerLifecycleEvents.SERVER_STARTED.register(ItemPrices::load);

		// Дальше здесь появятся: подписка на события, слоты продажи, киты.
	}

	/**
	 * Короткий помощник для создания игровых идентификаторов вида "marketplace:что-то".
	 * Нужен, чтобы не писать длинную строку каждый раз.
	 */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
