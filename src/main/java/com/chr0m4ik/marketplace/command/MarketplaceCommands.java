package com.chr0m4ik.marketplace.command;

import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Команды мода.
 *
 * <p>Что умеет сейчас:
 * <ul>
 *     <li>{@code /market} — подсказка по командам (позже здесь будет открытие меню);</li>
 *     <li>{@code /market money get [игрок]} — посмотреть баланс;</li>
 *     <li>{@code /market money set <игрок> <сумма>} — установить баланс;</li>
 *     <li>{@code /market money add <игрок> <сумма>} — изменить баланс (отрицательная сумма вычитает).</li>
 * </ul>
 *
 * <p>Всё, что связано с деньгами, требует прав оператора: в одиночной игре это значит, что
 * при создании мира должны быть включены читы; на сервере — что игрок оператор; из консоли
 * сервера команды доступны всегда. Сама {@code /market} доступна всем — это то же самое,
 * что нажать клавишу {@code I}.
 */
public final class MarketplaceCommands {
	private MarketplaceCommands() {
		// Класс только со статическими методами.
	}

	private static final String ARG_PLAYER = "player";
	private static final String ARG_AMOUNT = "amount";
	private static final String ARG_ITEM = "item";

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
					// Деньгами можно управлять только с правами оператора.
					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.executes(MarketplaceCommands::showOwnBalance)
					.then(moneyGet)
					.then(moneySet)
					.then(moneyAdd);

			LiteralArgumentBuilder<CommandSourceStack> price = Commands.literal("price")
					.then(Commands.argument(ARG_ITEM, ItemArgument.item(registryAccess))
							.executes(MarketplaceCommands::showPrice));

			dispatcher.register(Commands.literal("market")
					.executes(MarketplaceCommands::openMainMenu)
					.then(Commands.literal("help")
							.executes(MarketplaceCommands::showHelp))
					.then(price)
					.then(money));
		});
	}

	/** {@code /market} — открыть главное меню рынка. */
	private static int openMainMenu(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		MarketplaceMenus.open(player, WindowKind.MAIN);
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
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.get")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.set")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.add")
				.withStyle(ChatFormatting.GRAY), false);

		return 1;
	}

	/** {@code /market money} без аргументов — показать свой баланс. */
	private static int showOwnBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		double balance = MarketplaceEconomy.getBalance(source.getServer(), player.getUUID());

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.balance.self", money(balance)), false);

		return 1;
	}

	/** {@code /market money get <игрок>} — показать баланс другого игрока. */
	private static int showBalanceOf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);
		double balance = MarketplaceEconomy.getBalance(source.getServer(), target.getUUID());

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.balance.other",
				target.getScoreboardName(), money(balance)), false);

		return 1;
	}

	/** {@code /market money set <игрок> <сумма>}. */
	private static int setBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);
		double amount = DoubleArgumentType.getDouble(context, ARG_AMOUNT);
		double now = MarketplaceEconomy.setBalance(source.getServer(), target.getUUID(), amount);

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.set",
				target.getScoreboardName(), money(now)), true);

		return 1;
	}

	/** {@code /market money add <игрок> <сумма>}. */
	private static int addBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);
		double amount = DoubleArgumentType.getDouble(context, ARG_AMOUNT);
		double now = MarketplaceEconomy.addBalance(source.getServer(), target.getUUID(), amount);

		source.sendSuccess(() -> Component.translatable("marketplace.command.money.changed",
				target.getScoreboardName(), money(amount), money(now)), true);

		return 1;
	}

	/** Сумма в виде цветного текста — так её видно в сообщении. */
	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount)).withStyle(ChatFormatting.GREEN);
	}

	/** {@code /market price <предмет>} — показать цену предмета. */
	private static int showPrice(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ItemStack stack = ItemArgument.getItem(context, ARG_ITEM).createItemStack(1);
		Item item = stack.getItem();
		double price = ItemPrices.priceOf(item);

		source.sendSuccess(() -> Component.translatable("marketplace.command.price.item",
				stack.getHoverName(), money(price)).withStyle(ChatFormatting.GOLD), false);

		if (ItemPrices.isBuyable(item)) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.buy",
					money(price * ItemPrices.BUY_MULTIPLIER)), false);
		} else {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.cannotBuy")
					.withStyle(ChatFormatting.GRAY), false);
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.price.sell",
				money(price), money(price * ItemPrices.MAX_MARKET_MARKUP),
				money(price * ItemPrices.TRADER_MULTIPLIER)), false);

		if (!ItemPrices.isLoaded()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.notLoaded")
					.withStyle(ChatFormatting.RED), false);
		}

		return 1;
	}
}
