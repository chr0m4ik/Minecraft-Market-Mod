package com.chr0m4ik.marketplace;

import com.chr0m4ik.marketplace.command.MarketplaceCommands;
import com.chr0m4ik.marketplace.collector.CollectorHandler;
import com.chr0m4ik.marketplace.collector.CollectorItem;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.QuestGroups;
import com.chr0m4ik.marketplace.economy.UrgentQuests;
import com.chr0m4ik.marketplace.event.EventService;
import com.chr0m4ik.marketplace.market.MarketTick;
import com.chr0m4ik.marketplace.market.SaleTimes;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.mining.DrillHandler;
import com.chr0m4ik.marketplace.mining.MagnetHandler;
import com.chr0m4ik.marketplace.mining.SmelterHandler;
import com.chr0m4ik.marketplace.mining.VeinMinerHandler;
import com.chr0m4ik.marketplace.network.MarketplaceNetworking;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.price.PriceDrift;
import com.chr0m4ik.marketplace.price.TraderLimits;
import com.chr0m4ik.marketplace.turbo.TurboFurnace;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MarketplaceMod implements ModInitializer {

	public static final String MOD_ID = "marketplace";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// LOGGER.info("Loading Minecraft Marketplace Mod");

		MarketplaceCommands.register();
		MarketplaceMenus.initialize();
		MarketplaceNetworking.registerServer();

		TurboFurnace.register();
		CollectorItem.register();
		CollectorHandler.register();

		EconomyConfig.ensureLoaded();
		ItemPrices.ensureLoaded();
		TraderLimits.ensureLoaded();
		SaleTimes.ensureLoaded();

		QuestGroups.ensureLoaded();

		MarketTick.register();

		VeinMinerHandler.register();

		DrillHandler.register();

		MagnetHandler.register();

		SmelterHandler.register();

		// мира (без игроков ивенты замирают).
		EventService.register();

		UrgentQuests.register();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.overworld() == null) {
				return;
			}

			PriceDrift.update(server.overworld().getSeed(), GameTime.now(server));
		});

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

	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
