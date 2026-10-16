package com.chr0m4ik.marketplace.command;

import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.menu.MarketplaceMenus;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.price.PriceDrift;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Команды мода.
 *
 * <p>Что умеет сейчас:
 * <ul>
 *     <li>{@code /market} — подсказка по командам (позже здесь будет открытие меню);</li>
 *     <li>{@code /market money get [игрок]} — посмотреть баланс;</li>
 *     <li>{@code /market money set <игрок> <сумма>} — установить баланс;</li>
 *     <li>{@code /market money add <игрок> <сумма>} — изменить баланс (отрицательная сумма вычитает);</li>
 *     <li>{@code /market drift <предмет> [тик]} — показать микро-дрейф цены предмета;</li>
 *     <li>{@code /market event ...} — заготовки под крупные ивенты (сами ивенты ещё не сделаны).</li>
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
	private static final String ARG_COUNT = "count";
	private static final String ARG_EVENT = "id";
	private static final String ARG_TICK = "tick";

	/** Предмета нет в прайсе — считать нечего. */
	private static final SimpleCommandExceptionType NO_PRICE =
			new SimpleCommandExceptionType(Component.translatable("marketplace.command.drift.noPrice"));

	/** Для указанного тика нет данных: сервер ещё не сообщил зерно мира. */
	private static final SimpleCommandExceptionType NO_SEED =
			new SimpleCommandExceptionType(Component.translatable("marketplace.command.drift.noSeed"));

	/**
	 * Крупные ивенты, которые появятся позже. Пока это только список названий:
	 * команда {@code /market event list} показывает их игроку.
	 */
	private static final List<String> PLANNED_EVENTS = List.of(
			"marketplace.event.sale",
			"marketplace.event.crash",
			"marketplace.event.hype",
			"marketplace.event.shortage",
			"marketplace.event.blackday");

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
					.then(Commands.literal("hand")
							.executes(MarketplaceCommands::showHandPrice))
					.then(Commands.argument(ARG_ITEM, ItemArgument.item(registryAccess))
							.executes(MarketplaceCommands::showPrice)
							.then(Commands.argument(ARG_COUNT, IntegerArgumentType.integer(1, 9999))
									.executes(MarketplaceCommands::showPriceForCount)));

			// Микро-дрейф: показывает, сколько сейчас стоит предмет на самом деле.
			// Второй аргумент — игровой тик: без него берётся текущее время, с ним можно
			// посмотреть цену на любой момент (это же удобно для проверки).
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
									.executes(MarketplaceCommands::startEventStub)))
					.then(Commands.literal("stop")
							.executes(MarketplaceCommands::stopEventStub));

			dispatcher.register(Commands.literal("market")
					.executes(MarketplaceCommands::openMainMenu)
					.then(Commands.literal("help")
							.executes(MarketplaceCommands::showHelp))
					.then(price)
					.then(money)
					.then(drift)
					.then(event));
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
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.price.hand")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.get")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.set")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.money.add")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.drift")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.event")
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

	/** {@code /market price <предмет>} — показать цену одного предмета. */
	private static int showPrice(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return sendItemPrice(context, 1);
	}

	/** {@code /market price <предмет> <количество>} — цена сразу нескольких штук. */
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

	/** {@code /market price hand} — цена всего, что лежит в руке (в правой руке). */
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

	/** Общий вывод цены: заголовок, покупка и продажа. */
	private static void sendPriceReport(CommandSourceStack source, Item item, MutableComponent title, double total) {
		source.sendSuccess(() -> title.withStyle(ChatFormatting.GOLD), false);

		if (ItemPrices.isBuyable(item)) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.buy",
					money(total * ItemPrices.BUY_MULTIPLIER)), false);
		} else {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.cannotBuy")
					.withStyle(ChatFormatting.GRAY), false);
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.price.sell",
				money(total), money(total * ItemPrices.MAX_MARKET_MARKUP),
				money(total * ItemPrices.TRADER_MULTIPLIER)), false);

		if (!ItemPrices.isLoaded()) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.price.notLoaded")
					.withStyle(ChatFormatting.RED), false);
		}
	}

	/**
	 * {@code /market drift <предмет> [тик]} — показать микро-дрейф цены.
	 *
	 * <p>Если тик не указан, берётся текущее игровое время. С указанным тиком можно посмотреть
	 * цену на любой момент — это нужно для проверки, что дрейф не выходит за ±5% и что
	 * одинаковые входные данные дают одинаковый множитель.
	 */
	private static int showDrift(CommandContext<CommandSourceStack> context, long time) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ItemInput input = ItemArgument.getItem(context, ARG_ITEM);
		ItemStack stack = input.createItemStack(1);
		Item item = stack.getItem();

		if (!PriceDrift.isReady()) {
			// Так бывает, если мир ещё не загрузился: множитель в этот момент равен 1.0.
			throw NO_SEED.create();
		}

		if (!ItemPrices.isPriced(item)) {
			throw NO_PRICE.create();
		}

		// Без указания тика берём текущее игровое время, с указанием — заданный момент.
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
				percent(multiplier - 1.0)).withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.unit",
				money(unit), Component.literal(String.format("%.1f", PriceDrift.AMPLITUDE * 100.0)))
				.withStyle(ChatFormatting.WHITE), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.buy",
				money(unit * ItemPrices.BUY_MULTIPLIER)), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.trader",
				money(unit * ItemPrices.TRADER_MULTIPLIER)), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.drift.time",
				Component.literal(String.valueOf(moment)), Component.literal(String.valueOf(seed)))
				.withStyle(ChatFormatting.DARK_GRAY), false);

		return 1;
	}

	/** {@code /market event list} — какие крупные ивенты запланированы. */
	private static int listEvents(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();

		source.sendSuccess(() -> Component.translatable("marketplace.command.event.header")
				.withStyle(ChatFormatting.GOLD), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.event.none")
				.withStyle(ChatFormatting.GRAY), false);

		for (String key : PLANNED_EVENTS) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.event.planned",
					Component.translatable(key)).withStyle(ChatFormatting.GRAY), false);
		}

		source.sendSuccess(() -> Component.translatable("marketplace.command.event.startHint")
				.withStyle(ChatFormatting.DARK_GRAY), false);

		return 1;
	}

	/** {@code /market event start <id>} — пока заглушка: ивенты ещё не реализованы. */
	private static int startEventStub(CommandContext<CommandSourceStack> context) {
		String id = StringArgumentType.getString(context, ARG_EVENT);

		context.getSource().sendSuccess(() -> Component.translatable("marketplace.command.event.start",
				Component.literal(id).withStyle(ChatFormatting.YELLOW)).withStyle(ChatFormatting.GRAY), false);

		return 0;
	}

	/** {@code /market event stop} — пока заглушка: останавливать нечего. */
	private static int stopEventStub(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(() -> Component.translatable("marketplace.command.event.stop")
				.withStyle(ChatFormatting.GRAY), false);

		return 0;
	}

	/** Отклонение дрейфа в процентах со знаком. */
	private static Component percent(double fraction) {
		return Component.literal(String.format("%+.2f%%", fraction * 100.0));
	}
}
