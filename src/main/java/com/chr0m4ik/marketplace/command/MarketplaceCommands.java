package com.chr0m4ik.marketplace.command;

import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.HomePoint;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.UrgentQuest;
import com.chr0m4ik.marketplace.economy.UrgentQuests;
import com.chr0m4ik.marketplace.event.EventMessages;
import com.chr0m4ik.marketplace.event.EventService;
import com.chr0m4ik.marketplace.event.MarketEvent;
import com.chr0m4ik.marketplace.event.MarketEvents;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.market.CustomKit;
import com.chr0m4ik.marketplace.market.CustomKits;
import com.chr0m4ik.marketplace.market.KitCatalog;
import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.market.MarketOrder;
import com.chr0m4ik.marketplace.market.MarketService;
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.market.VirtualEnderChest;
import com.chr0m4ik.marketplace.market.SpecialCatalog;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.price.PriceDrift;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Prediction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;


public final class MarketplaceCommands {
	private MarketplaceCommands() {
		// РљР»Р°СЃСЃ С‚РѕР»СЊРєРѕ СЃРѕ СЃС‚Р°С‚РёС‡РµСЃРєРёРјРё РјРµС‚РѕРґР°РјРё.
	}

	private static final String ARG_PLAYER = "player";
	private static final String ARG_AMOUNT = "amount";
	private static final String ARG_ITEM = "item";
	private static final String ARG_COUNT = "count";
	private static final String ARG_EVENT = "id";
	private static final String ARG_TICK = "tick";
	private static final String ARG_SECTION = "section";
	private static final String ARG_NAME = "name";
	private static final String ARG_PRICE = "price";
	private static final String ARG_SLOT = "slot";
	private static final String ARG_ENABLED = "enabled";

	
	private static final int SHOP_SHOWN = 20;
	
	private static final String EVENT_RANDOM = "random";

	
	private static final SimpleCommandExceptionType NO_PRICE =
			new SimpleCommandExceptionType(Component.translatable("marketplace.command.drift.noPrice"));

	
	private static final SimpleCommandExceptionType NO_SEED =
			new SimpleCommandExceptionType(Component.translatable("marketplace.command.drift.noSeed"));

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<CommandSourceStack> moneyGet = Commands.literal("get")
					.executes(MarketplaceCommands::showOwnBalance)
					.then(Commands.argument(ARG_PLAYER, EntityArgument.player())
							.executes(MarketplaceCommands::showBalanceOf));

			LiteralArgumentBuilder<CommandSourceStack> moneySet = Commands.literal("set")
					.then(Commands.argument(ARG_PLAYER, EntityArgument.player())
							.then(Commands.argument(ARG_AMOUNT, DoubleArgumentType.doubleArg(0.0))
									.executes(MarketplaceCommands::setBalance)));

			LiteralArgumentBuilder<CommandSourceStack> moneyAdd = Commands.literal("add")
					.then(Commands.argument(ARG_PLAYER, EntityArgument.player())
							.then(Commands.argument(ARG_AMOUNT, DoubleArgumentType.doubleArg())
									.executes(MarketplaceCommands::addBalance)));

			LiteralArgumentBuilder<CommandSourceStack> money = Commands.literal("money")

					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.executes(MarketplaceCommands::showOwnBalance)
					.then(moneyGet)
					.then(moneySet)
					.then(moneyAdd);

			LiteralArgumentBuilder<CommandSourceStack> price = Commands.literal("price")
					.then(Commands.literal("hand")
							.executes(MarketplaceCommands::showHandPrice))
					.then(Commands.argument(ARG_ITEM, ItemArgument.item(registryAccess))
							.executes(MarketplaceCommands::showPrice)
							.then(Commands.argument(ARG_COUNT, IntegerArgumentType.integer(1, 9999))
									.executes(MarketplaceCommands::showPriceForCount)));


			LiteralArgumentBuilder<CommandSourceStack> drift = Commands.literal("drift")
					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.then(Commands.argument(ARG_ITEM, ItemArgument.item(registryAccess))
							.executes(context -> showDrift(context, Long.MIN_VALUE))
							.then(Commands.argument(ARG_TICK, LongArgumentType.longArg(0L))
									.executes(context -> showDrift(context, LongArgumentType.getLong(context, ARG_TICK)))));

			LiteralArgumentBuilder<CommandSourceStack> event = Commands.literal("event")
					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.executes(MarketplaceCommands::listEvents)
					.then(Commands.literal("list")
							.executes(MarketplaceCommands::listEvents))
					.then(Commands.literal("start")
							.then(Commands.argument(ARG_EVENT, StringArgumentType.word())
									.executes(MarketplaceCommands::startEvent)))
					.then(Commands.literal("stop")
							.executes(MarketplaceCommands::stopEvent));


			LiteralArgumentBuilder<CommandSourceStack> shop = Commands.literal("shop")
					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.then(Commands.argument(ARG_SECTION, StringArgumentType.word())
							.executes(MarketplaceCommands::showShopSection));


			LiteralArgumentBuilder<CommandSourceStack> urgent = Commands.literal("urgent")
					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.executes(MarketplaceCommands::urgentStatus)
					.then(Commands.literal("status")
							.executes(MarketplaceCommands::urgentStatus))
					.then(Commands.literal("start")
							.executes(MarketplaceCommands::urgentStart))
					.then(Commands.literal("stop")
							.executes(MarketplaceCommands::urgentStop));


			LiteralArgumentBuilder<CommandSourceStack> kit = Commands.literal("kit")
					.executes(MarketplaceCommands::listKits)
					.then(Commands.literal("new")
							.then(Commands.argument(ARG_NAME, StringArgumentType.greedyString())
									.executes(MarketplaceCommands::createKit)))
					.then(Commands.literal("delete")
							.then(Commands.argument(ARG_NAME, StringArgumentType.greedyString())
									.executes(MarketplaceCommands::deleteKit)))
					.then(Commands.literal("list")
							.executes(MarketplaceCommands::listKits));


			dispatcher.register(Commands.literal("ec")
					.executes(MarketplaceCommands::openVirtualEnderChest));
			dispatcher.register(Commands.literal("enderchest")
					.executes(MarketplaceCommands::openVirtualEnderChest));


			LiteralArgumentBuilder<CommandSourceStack> auction = Commands.literal("ah")
					.executes(MarketplaceCommands::openAuctionMenu)
					.then(Commands.literal("buy")
							.executes(MarketplaceCommands::openShop))
					.then(Commands.literal("sell")
							.executes(MarketplaceCommands::openMarketSell)
							.then(Commands.argument(ARG_PRICE, DoubleArgumentType.doubleArg(
											MarketService.MIN_LIST_PRICE, MarketService.MAX_LIST_PRICE))
									.executes(MarketplaceCommands::ahSell)
									.then(Commands.argument(ARG_COUNT, IntegerArgumentType.integer(1, 64))
											.executes(MarketplaceCommands::ahSellCount))))
					.then(Commands.literal("list")
							.executes(MarketplaceCommands::ahList))
					.then(Commands.literal("cancel")
							.then(Commands.literal("all")
									.executes(MarketplaceCommands::ahCancelAll))
							.then(Commands.argument(ARG_SLOT, IntegerArgumentType.integer(1, MarketService.SLOTS))
									.executes(MarketplaceCommands::ahCancelSlot)));

			dispatcher.register(auction);

			// РџСЂСЏРјС‹Рµ РѕРєРЅР°: С‚РѕСЂРіРѕРІРµС† Рё СЃСЂР°Р·Сѓ РµРіРѕ Р·Р°РґР°РЅРёСЏ.
			dispatcher.register(Commands.literal("trader")
					.executes(MarketplaceCommands::openTrader));
			dispatcher.register(Commands.literal("quest")
					.executes(MarketplaceCommands::openQuests));

			dispatcher.register(Commands.literal("market")
					.executes(MarketplaceCommands::openMainMenu)
					.then(Commands.literal("help")
							.executes(MarketplaceCommands::showHelp))
					.then(price)
					.then(money)
					.then(drift)
					.then(shop)
					.then(event)
					.then(urgent)
					.then(kit));

			dispatcher.register(Commands.literal("pay")
					.then(Commands.argument(ARG_AMOUNT, DoubleArgumentType.doubleArg(0.01))
							.then(Commands.argument(ARG_PLAYER, EntityArgument.player())
									.executes(MarketplaceCommands::pay))));

			dispatcher.register(Commands.literal("rtp")
					.executes(MarketplaceCommands::randomTeleport));

			dispatcher.register(Commands.literal("sethome")
					.executes(MarketplaceCommands::setHome));

			dispatcher.register(Commands.literal("home")
					.executes(MarketplaceCommands::goHome));

			dispatcher.register(Commands.literal("pull")
					.then(Commands.literal("requests")
							.then(Commands.argument(ARG_ENABLED, BoolArgumentType.bool())
									.executes(MarketplaceCommands::pullRequests)))
					.then(Commands.argument(ARG_PLAYER, EntityArgument.player())
							.executes(MarketplaceCommands::pullTo)));
		});
	}

	private static int pay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer sender = source.getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);
		MinecraftServer server = source.getServer();
		double amount = MoneyFormat.round(DoubleArgumentType.getDouble(context, ARG_AMOUNT));

		if (target == sender) {
			source.sendFailure(Component.translatable("marketplace.pay.self"));
			return 0;
		}

		if (amount <= 0.0) {
			source.sendFailure(Component.translatable("marketplace.pay.amount"));
			return 0;
		}

		if (!MarketplaceEconomy.trySpend(server, sender.getUUID(), amount)) {
			source.sendFailure(Component.translatable("marketplace.pay.notEnough",
					Component.literal(MoneyFormat.format(MarketplaceEconomy.getBalance(server, sender.getUUID())))));
			return 0;
		}

		MarketplaceEconomy.addBalance(server, target.getUUID(), amount);
		sender.sendSystemMessage(Component.translatable("marketplace.pay.sent", target.getDisplayName(),
				Component.literal(MoneyFormat.format(amount))).withStyle(ChatFormatting.GREEN));
		target.sendSystemMessage(Component.translatable("marketplace.pay.received", sender.getDisplayName(),
				Component.literal(MoneyFormat.format(amount))).withStyle(ChatFormatting.GREEN));

		return 1;
	}

	private static int randomTeleport(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return RandomTeleport.teleport(context.getSource().getPlayerOrException());
	}

	private static int setHome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();

		if (player.level().dimension() != Level.OVERWORLD) {
			source.sendFailure(Component.translatable("marketplace.home.wrongDimension"));
			return 0;
		}

		MarketplaceEconomy.state(source.getServer()).setHome(player.getUUID(),
				new HomePoint(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
		player.sendSystemMessage(Component.translatable("marketplace.home.set", Component.literal(
				Math.round(player.getX()) + " " + Math.round(player.getY()) + " " + Math.round(player.getZ())))
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int goHome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();

		if (player.level().dimension() != Level.OVERWORLD) {
			source.sendFailure(Component.translatable("marketplace.home.wrongDimension"));
			return 0;
		}

		HomePoint home = MarketplaceEconomy.state(source.getServer()).home(player.getUUID());

		if (home == null) {
			source.sendFailure(Component.translatable("marketplace.home.none"));
			return 0;
		}

		if (TeleportGuard.blocked(player)) {
			return 0;
		}

		player.stopRiding();
		player.closeContainer();
		player.teleportTo(player.level(), home.x(), home.y(), home.z(), Set.of(), home.yaw(), home.pitch(), true);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		TeleportGuard.mark(player);
		player.sendSystemMessage(Component.translatable("marketplace.home.teleported")
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int pullTo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer sender = source.getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);

		if (target == sender) {
			source.sendFailure(Component.translatable("marketplace.pull.self"));
			return 0;
		}

		if (!MarketplaceEconomy.state(source.getServer()).pullEnabled(target.getUUID())) {
			source.sendFailure(Component.translatable("marketplace.pull.disabled", target.getDisplayName()));
			return 0;
		}

		if (TeleportGuard.blocked(sender)) {
			return 0;
		}

		sender.stopRiding();
		sender.closeContainer();
		sender.teleportTo(target.level(), target.getX(), target.getY(), target.getZ(), Set.of(),
				sender.getYRot(), sender.getXRot(), true);
		sender.setDeltaMovement(Vec3.ZERO);
		sender.resetFallDistance();
		TeleportGuard.mark(sender);
		sender.sendSystemMessage(Component.translatable("marketplace.pull.done", target.getDisplayName())
				.withStyle(ChatFormatting.GREEN));
		target.sendSystemMessage(Component.translatable("marketplace.pull.arrived", sender.getDisplayName())
				.withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int pullRequests(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		boolean enabled = BoolArgumentType.getBool(context, ARG_ENABLED);
		MarketplaceEconomy.state(context.getSource().getServer()).setPullEnabled(player.getUUID(), enabled);
		player.sendSystemMessage(Component.translatable(enabled ? "marketplace.pull.on" : "marketplace.pull.off")
				.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		return 1;
	}

	
	private static int openMainMenu(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return openWindow(context, WindowKind.MAIN);
	}

	
	private static int openAuctionMenu(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return openWindow(context, WindowKind.MAIN);
	}

	
	private static int openMarketSell(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return openWindow(context, WindowKind.SELL);
	}

	
	private static int openShop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return openWindow(context, WindowKind.BUY);
	}

	
	private static int openTrader(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return openWindow(context, WindowKind.TRADER);
	}

	
	private static int openQuests(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return openWindow(context, WindowKind.QUESTS);
	}

	
	private static int openWindow(CommandContext<CommandSourceStack> context, WindowKind kind)
			throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		MarketplaceMenus.open(player, kind);
		return 1;
	}

	private static int showHelp(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		source.sendSuccess(() -> Component.translatable("marketplace.command.help.header")
				.withStyle(ChatFormatting.GOLD), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.open")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.price")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.price.hand")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.get")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.set")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.add")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.drift",
				amplitude()).withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.event")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.shop")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.urgent")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.kit")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.ah")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.trader")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.quest")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.ec")
				.withStyle(ChatFormatting.GRAY), false);

		return 1;
	}

	
	private static int showOwnBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		double balance = MarketplaceEconomy.getBalance(source.getServer(), player.getUUID());

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.balance.self", money(balance)), false);

		return 1;
	}

	
	private static int showBalanceOf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);
		double balance = MarketplaceEconomy.getBalance(source.getServer(), target.getUUID());

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.balance.other",
				target.getScoreboardName(), money(balance)), false);

		return 1;
	}

	
	private static int setBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);
		double amount = DoubleArgumentType.getDouble(context, ARG_AMOUNT);
		double now = MarketplaceEconomy.setBalance(source.getServer(), target.getUUID(), amount);

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.set",
				target.getScoreboardName(), money(now)), true);

		return 1;
	}

	
	private static int addBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);
		double amount = DoubleArgumentType.getDouble(context, ARG_AMOUNT);
		double now = MarketplaceEconomy.addBalance(source.getServer(), target.getUUID(), amount);

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.changed",
				target.getScoreboardName(), money(amount), money(now)), true);

		return 1;
	}

	
	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount)).withStyle(ChatFormatting.GREEN);
	}

	
	private static int showShopSection(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, ARG_SECTION);
		Optional<ShopCatalog.Category> found = ShopCatalog.byName(name);

		if (found.isEmpty()) {
			source.sendFailure(Component.translatable("marketplace.command.shop.unknown",
					Component.literal(name), Component.literal(ShopCatalog.names())));
			return 0;
		}

		ShopCatalog.Category category = found.get();

		MarketplaceState world = MarketplaceEconomy.state(source.getServer());
		ShopCatalog.setCustomKits(CustomKits.all(world));

		List<ItemStack> lots = new ArrayList<>();

		for (MarketService.Listing listing : MarketService.allListings(world)) {
			lots.add(MarketService.listingIcon(listing.order(), listing.sellerId(), listing.slot()));
		}

		ShopCatalog.setAuctionIcons(lots);
		ShopCatalog.refresh(source.registryAccess());
		List<ItemStack> items = ShopCatalog.items(category);

		source.sendSuccess(() -> Component.translatable("marketplace.command.shop.header",
				Component.translatable(category.titleKey()),
				Component.literal(String.valueOf(items.size()))).withStyle(ChatFormatting.GOLD), false);

		for (int index = 0; index < items.size() && index < SHOP_SHOWN; index++) {
			ItemStack stack = items.get(index);

			Optional<MarketService.Listing> listing = MarketService.listing(
					MarketplaceEconomy.state(source.getServer()), stack);
			Optional<KitCatalog.Kit> kit = KitCatalog.kitOf(stack);
			Optional<CustomKit> custom = CustomKits.kitOf(MarketplaceEconomy.state(source.getServer()), stack);
			OptionalDouble special = SpecialCatalog.priceOf(stack);
			double price;

			if (listing.isPresent()) {
				price = listing.get().order().payout();
			} else if (custom.isPresent()) {
				price = CustomKits.priceOf(custom.get());
			} else if (kit.isPresent()) {
				price = KitCatalog.priceOf(kit.get());
			} else if (special.isPresent()) {
				price = special.getAsDouble();
			} else {
				price = ItemPrices.priceOf(stack);
			}

			int number = index + 1;

			source.sendSuccess(() -> Component.translatable("marketplace.command.shop.item",
					Component.literal(number + ". "), stack.getHoverName(), money(price))
					.withStyle(special.isPresent() || custom.isPresent() || listing.isPresent()
							? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY), false);
		}

		if (items.size() > SHOP_SHOWN) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.shop.more",
					Component.literal(String.valueOf(items.size() - SHOP_SHOWN)))
					.withStyle(ChatFormatting.DARK_GRAY), false);
		}

		return items.size();
	}

	
	private static int showPrice(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return sendItemPrice(context, 1);
	}

	
	private static int showPriceForCount(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return sendItemPrice(context, IntegerArgumentType.getInteger(context, ARG_COUNT));
	}

	private static int sendItemPrice(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
		ItemStack stack = ItemArgument.getItem(context, ARG_ITEM).createItemStack(1);
		double unit = ItemPrices.priceOf(stack);
		double total = unit * count;

		MutableComponent title = count == 1
				? Component.translatable("marketplace.command.price.item", stack.getHoverName(), money(unit))
				: Component.translatable("marketplace.command.price.itemCount", stack.getHoverName(),
						Component.literal(String.valueOf(count)), money(total), money(unit));

		sendPriceReport(context.getSource(), stack.getItem(), title, total);

		return 1;
	}

	
	private static int showHandPrice(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		ItemStack stack = player.getMainHandItem();

		if (stack.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.handEmpty")
					.withStyle(ChatFormatting.GRAY), false);

			return 0;
		}

		double unit = ItemPrices.priceOf(stack.copyWithCount(1));
		double total = ItemPrices.priceOf(stack);

		MutableComponent title = stack.getCount() == 1
				? Component.translatable("marketplace.command.price.hand", stack.getHoverName(), money(total))
				: Component.translatable("marketplace.command.price.handCount", stack.getHoverName(),
						Component.literal(String.valueOf(stack.getCount())), money(total), money(unit));

		sendPriceReport(source, stack.getItem(), title, total);

		return 1;
	}

	
	private static void sendPriceReport(CommandSourceStack source, Item item, MutableComponent title, double total) {
		source.sendSuccess(() -> title.withStyle(ChatFormatting.GOLD), false);

		if (ItemPrices.isBuyable(item)) {
			var special = SpecialCatalog.priceOf(new ItemStack(item));

			if (special.isPresent()) {
				source.sendSuccess(() -> Component.translatable("marketplace.tooltip.buy.special",
						money(special.getAsDouble())), false);
			} else {
				source.sendSuccess(() -> Component.translatable("marketplace.command.price.buy",
						money(total * EconomyConfig.buyMultiplier())), false);
			}
		} else {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.cannotBuy")
					.withStyle(ChatFormatting.GRAY), false);
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.price.sell",
				money(total), money(total * EconomyConfig.maxMarketMarkup()),
				money(total * EconomyConfig.traderMultiplier())), false);

		if (!ItemPrices.isLoaded()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.notLoaded")
					.withStyle(ChatFormatting.RED), false);
		}
	}

	
	private static int showDrift(CommandContext<CommandSourceStack> context, long time) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ItemInput input = ItemArgument.getItem(context, ARG_ITEM);
		ItemStack stack = input.createItemStack(1);
		Item item = stack.getItem();

		if (!PriceDrift.isReady()) {

			throw NO_SEED.create();
		}

		if (!ItemPrices.isPriced(item)) {
			throw NO_PRICE.create();
		}


		long seed = PriceDrift.seed();
		long moment = time == Long.MIN_VALUE ? PriceDrift.time() : time;
		double multiplier = PriceDrift.multiplierAt(item, moment, seed);
		double base = ItemPrices.priceOf(item);
		double unit = base * multiplier;

		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.header",
				stack.getHoverName()).withStyle(ChatFormatting.GOLD), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.base",
				money(base)), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.multiplier",
				Component.literal(String.format("%.4f", multiplier)).withStyle(
						multiplier >= 1.0 ? ChatFormatting.GREEN : ChatFormatting.RED),
				percent(multiplier - 1.0), amplitude()).withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.unit",
				money(unit), amplitude())
				.withStyle(ChatFormatting.WHITE), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.buy",
				money(unit * EconomyConfig.buyMultiplier())), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.trader",
				money(unit * EconomyConfig.traderMultiplier())), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.time",
				Component.literal(String.valueOf(moment)), Component.literal(String.valueOf(seed)))
				.withStyle(ChatFormatting.DARK_GRAY), false);

		return 1;
	}

	
	private static int listEvents(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MarketplaceState state = MarketplaceEconomy.state(source.getServer());
		MarketEvent active = state.activeEvent();
		MarketEvent pending = state.pendingEvent();

		source.sendSuccess(() -> Component.translatable("marketplace.command.event.header")
				.withStyle(ChatFormatting.GOLD), false);

		if (active == null) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.none",
					Component.literal(one(EconomyConfig.eventDailyChance() * 100.0)))
					.withStyle(ChatFormatting.GRAY), false);
		} else {
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.active",
					active.name().title()).withStyle(ChatFormatting.GOLD), false);
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.target",
					EventMessages.targetNames(active)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.percent",
					eventPercent(active)).withStyle(ChatFormatting.GRAY), false);
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.left",
					MarketEvents.duration(state.eventRemaining())).withStyle(ChatFormatting.GRAY), false);
		}

		if (pending != null) {

			long left = Math.max(0L, state.eventPendingAt() - state.activeTicks());
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.pending",
					pending.name().title(), MarketEvents.duration(left))
					.withStyle(ChatFormatting.AQUA), false);
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.event.activeTime",
				MarketEvents.duration(state.activeTicks())).withStyle(ChatFormatting.DARK_GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.event.names")
				.withStyle(ChatFormatting.GRAY), false);

		for (MarketEvents.Name name : MarketEvents.Name.values()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.name",
					name.title(), name.direction().title()).withStyle(ChatFormatting.GRAY), false);
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.event.startHint")
				.withStyle(ChatFormatting.DARK_GRAY), false);

		return 1;
	}

	
	private static int startEvent(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String id = StringArgumentType.getString(context, ARG_EVENT);
		Optional<MarketEvents.Name> name = EVENT_RANDOM.equalsIgnoreCase(id)
				? Optional.of(MarketEvents.Name.random(source.getServer().overworld().getRandom()))
				: MarketEvents.Name.byId(id);

		if (name.isEmpty()) {
			source.sendFailure(Component.translatable("marketplace.command.event.unknown",
					Component.literal(id), Component.literal(MarketEvents.Name.ids())));
			return 0;
		}

		Optional<MarketEvent> started = EventService.startNow(source.getServer(), name.get());

		if (started.isEmpty()) {
			MarketEvent active = EventService.current().orElse(null);

			if (active == null) {
				source.sendFailure(Component.translatable("marketplace.command.event.failed"));
			} else {
				source.sendFailure(Component.translatable("marketplace.command.event.already",
						active.name().title()));
			}

			return 0;
		}

		MarketEvent event = started.get();
		source.sendSuccess(() -> Component.translatable("marketplace.command.event.started",
				event.name().title(), EventMessages.targetNames(event), eventPercent(event),
				MarketEvents.duration(event.durationTicks())).withStyle(ChatFormatting.GOLD), false);

		return 1;
	}

	
	private static int stopEvent(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Optional<MarketEvent> stopped = EventService.stop(source.getServer());

		if (stopped.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.stop")
					.withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.event.stopped",
				stopped.get().name().title()).withStyle(ChatFormatting.YELLOW), false);

		return 1;
	}

	
	private static int urgentStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Optional<UrgentQuest> current = UrgentQuests.current(source.getServer());

		if (current.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.urgent.none")
					.withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		UrgentQuest quest = current.get();
		source.sendSuccess(() -> Component.translatable("marketplace.command.urgent.active",
				quest.icon().getHoverName(),
				Component.literal(quest.count() + " (" + quest.stacks() + " СЃС‚Р°РєРѕРІ)"),
				Component.literal(MoneyFormat.format(quest.reward())),
				MarketEvents.duration(quest.remaining())).withStyle(ChatFormatting.GOLD), false);

		return 1;
	}

	
	private static int urgentStart(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Optional<UrgentQuest> started = UrgentQuests.startNow(source.getServer());

		if (started.isEmpty()) {
			if (UrgentQuests.current(source.getServer()).isPresent()) {
				source.sendFailure(Component.translatable("marketplace.command.urgent.already"));
			} else {
				source.sendFailure(Component.translatable("marketplace.command.urgent.failed"));
			}

			return 0;
		}

		UrgentQuest quest = started.get();
		source.sendSuccess(() -> Component.translatable("marketplace.command.urgent.started",
				quest.icon().getHoverName(),
				Component.literal(quest.count() + " (" + quest.stacks() + " СЃС‚Р°РєРѕРІ)"),
				Component.literal(MoneyFormat.format(quest.reward())),
				MarketEvents.duration(quest.remaining())).withStyle(ChatFormatting.GOLD), true);

		return 1;
	}

	
	private static int urgentStop(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Optional<UrgentQuest> stopped = UrgentQuests.stop(source.getServer(), true);

		if (stopped.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.urgent.none")
					.withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		UrgentQuest quest = stopped.get();
		source.sendSuccess(() -> Component.translatable("marketplace.command.urgent.stopped",
				quest.icon().getHoverName(),
				Component.literal(String.valueOf(quest.count()))).withStyle(ChatFormatting.YELLOW), false);

		return 1;
	}

	
	private static int createKit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		String raw = StringArgumentType.getString(context, ARG_NAME).trim();
		int space = raw.lastIndexOf(' ');
		String mode = space < 0 ? "" : raw.substring(space + 1).trim();
		String name = stripQuotes(space < 0 ? "" : raw.substring(0, space).trim());

		if (!mode.equalsIgnoreCase("true") && !mode.equalsIgnoreCase("false")) {

			source.sendFailure(Component.translatable("marketplace.command.kit.mode"));
			return 0;
		}

		boolean shulkers = mode.equalsIgnoreCase("true");
		CustomKits.CreateResult result = CustomKits.create(source.getServer(), player, name, shulkers);

		if (result.kit() == null) {
			switch (result.status()) {
				case BAD_NAME -> source.sendFailure(Component.translatable("marketplace.command.kit.badName",
						Component.literal(String.valueOf(CustomKits.MAX_NAME))));
				case NAME_TAKEN -> source.sendFailure(Component.translatable("marketplace.command.kit.nameTaken",
						Component.literal(name)));
				case LIMIT -> source.sendFailure(Component.translatable("marketplace.command.kit.limit",
						Component.literal(String.valueOf(CustomKits.LIMIT))));
				default -> source.sendFailure(Component.translatable("marketplace.command.kit.empty"));
			}

			return 0;
		}

		CustomKit kit = result.kit();
		boolean created = result.status() == CustomKits.CreateStatus.CREATED;
		source.sendSuccess(() -> Component.translatable(created
								? "marketplace.command.kit.created" : "marketplace.command.kit.replaced",
						Component.literal(kit.name()),
						Component.literal(String.valueOf(kit.size())),
						money(CustomKits.priceOf(kit))).withStyle(ChatFormatting.GREEN), false);

		return 1;
	}

	
	private static String stripQuotes(String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return value.substring(1, value.length() - 1).trim();
		}

		return value;
	}

	
	private static int deleteKit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = stripQuotes(StringArgumentType.getString(context, ARG_NAME).trim());
		ServerPlayer player = source.getPlayer();
		UUID requester = player == null ? null : player.getUUID();
		boolean operator = Commands.LEVEL_GAMEMASTERS.check(source.permissions());

		CustomKits.DeleteStatus status = CustomKits.delete(MarketplaceEconomy.state(source.getServer()),
				requester, operator, name);

		switch (status) {
			case DELETED -> source.sendSuccess(() -> Component.translatable("marketplace.command.kit.deleted",
					Component.literal(name)).withStyle(ChatFormatting.GREEN), true);
			case MISSING -> source.sendFailure(Component.translatable("marketplace.command.kit.missing",
					Component.literal(name)));
			case NOT_OWNER -> source.sendFailure(Component.translatable("marketplace.command.kit.notOwner",
					Component.literal(name)));
		}

		return status == CustomKits.DeleteStatus.DELETED ? 1 : 0;
	}

	
	private static int listKits(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<CustomKit> kits = CustomKits.all(MarketplaceEconomy.state(source.getServer()));

		if (kits.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.kit.list.empty")
					.withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.kit.list.header",
				Component.literal(String.valueOf(kits.size()))).withStyle(ChatFormatting.GOLD), false);

		for (CustomKit kit : kits) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.kit.list.line",
					Component.literal(kit.name()),
					Component.literal(String.valueOf(kit.size())),
					money(CustomKits.priceOf(kit)),
					Component.literal(kit.authorName())).withStyle(ChatFormatting.GRAY), false);
		}

		return kits.size();
	}

	
	private static int openVirtualEnderChest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();

		if (VirtualEnderChest.owns(context.getSource().getServer(), player.getUUID())) {

			VirtualEnderChest.open(player);
		} else {

			MarketplaceMenus.open(player, WindowKind.ENDER_CHEST);
		}

		return 1;
	}

	
	private static int ahSell(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return ahSell(context, -1);
	}

	
	private static int ahSellCount(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return ahSell(context, IntegerArgumentType.getInteger(context, ARG_COUNT));
	}

	
	private static int ahSell(CommandContext<CommandSourceStack> context, int requested)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		double price = DoubleArgumentType.getDouble(context, ARG_PRICE);
		ItemStack hand = player.getMainHandItem();
		int count = requested < 0 ? hand.getCount() : requested;
		ItemStack lot = hand.isEmpty() ? ItemStack.EMPTY : hand.copyWithCount(Math.min(count, hand.getCount()));

		MarketService.ListResult result = MarketService.listForPrice(MarketplaceEconomy.state(server),
				player.getUUID(), hand, count, price, GameTime.now(server), server.overworld().getRandom(),
				player.getScoreboardName());

		if (result.status() != MarketService.ListStatus.LISTED) {
			source.sendFailure(Component.translatable(switch (result.status()) {
				case NO_SLOT -> "marketplace.command.ah.noSlot";
				case BAD_PRICE -> "marketplace.command.ah.badPrice";
				case BAD_COUNT -> "marketplace.command.ah.badCount";
				case FORBIDDEN -> "marketplace.command.ah.forbidden";
				default -> "marketplace.command.ah.noItem";
			}));
			return 0;
		}

		hand.shrink(count);
		MarketOrder order = result.order();
		int slot = result.slot() + 1;

		source.sendSuccess(() -> Component.translatable("marketplace.command.ah.listed",
				Component.literal(String.valueOf(lot.getCount())), lot.getHoverName(),
				Component.literal(MoneyFormat.format(order.payout())),
				Component.literal(String.valueOf(slot))).withStyle(ChatFormatting.GREEN), false);

		if (order.waitingForBuyer()) {

			source.sendSuccess(() -> Component.translatable("marketplace.command.ah.waiting")
					.withStyle(ChatFormatting.GRAY), false);
		}

		return 1;
	}

	
	private static int ahList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		Map<Integer, MarketOrder> orders = MarketplaceEconomy.state(server).orders(player.getUUID());

		if (orders.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.ah.list.empty")
					.withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.ah.list.header",
				Component.literal(String.valueOf(orders.size()))).withStyle(ChatFormatting.GOLD), false);
		long now = GameTime.now(server);

		for (int slot : orders.keySet().stream().sorted().toList()) {
			MarketOrder order = orders.get(slot);
			int number = slot + 1;

			source.sendSuccess(() -> Component.translatable("marketplace.command.ah.list.line",
					Component.literal(String.valueOf(number)),
					Component.literal(String.valueOf(order.stack().getCount())),
					order.stack().getHoverName(),
					Component.literal(MoneyFormat.format(order.payout())),
					order.waitingForBuyer()
							? Component.translatable("marketplace.command.ah.list.waiting")
							: MarketEvents.duration(order.ticksLeft(now))).withStyle(ChatFormatting.GRAY), false);
		}

		return orders.size();
	}

	
	private static int ahCancelSlot(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		int slot = IntegerArgumentType.getInteger(context, ARG_SLOT) - 1;
		MarketOrder order = MarketService.cancelListing(MarketplaceEconomy.state(source.getServer()),
				player.getUUID(), slot);

		if (order == null) {
			source.sendFailure(Component.translatable("marketplace.command.ah.cancel.missing",
					Component.literal(String.valueOf(slot + 1))));
			return 0;
		}

		giveBack(player, order);
		source.sendSuccess(() -> Component.translatable("marketplace.command.ah.cancel.done",
				order.stack().getHoverName(),
				Component.literal(String.valueOf(order.stack().getCount()))).withStyle(ChatFormatting.YELLOW),
				false);

		return 1;
	}

	
	private static int ahCancelAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		MarketplaceState state = MarketplaceEconomy.state(source.getServer());
		List<Integer> slots = new ArrayList<>(state.orders(player.getUUID()).keySet());
		int cancelled = 0;

		for (int slot : slots) {
			MarketOrder order = MarketService.cancelListing(state, player.getUUID(), slot);

			if (order != null) {
				giveBack(player, order);
				cancelled++;
			}
		}

		if (cancelled == 0) {
			source.sendFailure(Component.translatable("marketplace.command.ah.list.empty"));
			return 0;
		}

		int total = cancelled;
		source.sendSuccess(() -> Component.translatable("marketplace.command.ah.cancel.all",
				Component.literal(String.valueOf(total))).withStyle(ChatFormatting.YELLOW), false);

		return cancelled;
	}

	
	private static void giveBack(ServerPlayer player, MarketOrder order) {
		player.getInventory().placeItemBackInInventory(order.stack().copy(), Prediction.SERVER_ONLY);
	}

	
	private static Component eventPercent(MarketEvent event) {
		return Component.literal(event.percentText())
				.withStyle(event.rises() ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	
	private static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	
	private static Component percent(double fraction) {
		return Component.literal(String.format("%+.2f%%", fraction * 100.0));
	}

	
	private static Component amplitude() {
		return Component.literal(String.format("%.1f", PriceDrift.amplitude() * 100.0));
	}
}
