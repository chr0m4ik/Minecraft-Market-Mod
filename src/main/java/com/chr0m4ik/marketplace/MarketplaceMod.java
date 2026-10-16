package com.chr0m4ik.marketplace;

import com.chr0m4ik.marketplace.command.MarketplaceCommands;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.event.EventService;
import com.chr0m4ik.marketplace.market.MarketTick;
import com.chr0m4ik.marketplace.market.SaleTimes;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.network.MarketplaceNetworking;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.price.PriceDrift;
import com.chr0m4ik.marketplace.price.TraderLimits;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

		// Читаем прайс, настройки экономики, лимиты и время продажи сразу при загрузке: если файлы
		// повреждены, это видно в логе, а не при первой сделке. Заодно проверяем, что настройки
		// не позволяют печатать деньги (купить предмет и продать его же дороже).
		EconomyConfig.ensureLoaded();
		ItemPrices.ensureLoaded();
		TraderLimits.ensureLoaded();
		SaleTimes.ensureLoaded();

		// Рынок: ордера продаются со временем, даже если игрок вышел.
		MarketTick.register();

		// Крупные экономические ивенты: броски раз в игровой день и отсчёт по активному времени
		// мира (без игроков ивенты замирают).
		EventService.register();

		// Микро-дрейф цен: раз в тик обновляем зерно и игровое время, от которых он считается.
		// Так цена в любой момент считается без обращения к миру — это дёшево и безопасно.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.overworld() == null) {
				return;
			}

			PriceDrift.update(server.overworld().getSeed(), GameTime.now(server));
		});

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
