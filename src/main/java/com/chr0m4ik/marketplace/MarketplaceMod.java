package com.chr0m4ik.marketplace;

import com.chr0m4ik.marketplace.command.MarketplaceCommands;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.network.MarketplaceNetworking;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.price.TraderLimits;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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

		// Читаем прайс и лимиты сразу при загрузке: если файлы повреждены, это видно в логе,
		// а не при первой сделке.
		ItemPrices.ensureLoaded();
		TraderLimits.ensureLoaded();

		// Смерть игрока: теряется часть денег, а не весь кошелёк.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (!(entity instanceof ServerPlayer player)) {
				return;
			}

			double lost = MarketplaceEconomy.applyDeathPenalty(player);

			if (lost > 0.0) {
				player.sendSystemMessage(Component.translatable("marketplace.death.lost",
						Component.literal(MoneyFormat.format(lost))).withStyle(ChatFormatting.RED));
			}
		});

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
