package com.chr0m4ik.marketplace.command;

import com.chr0m4ik.marketplace.economy.EconomyConfig;
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
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.market.SpecialCatalog;
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
import java.util.Optional;
import java.util.OptionalDouble;

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
 *     <li>{@code /market event list} — что за крупный ивент идёт сейчас и какие названия бывают;</li>
 *     <li>{@code /market event start <id|random>} — запустить ивент вручную (для проверки);</li>
 *     <li>{@code /market event stop} — остановить активный ивент.</li>
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
	private static final String ARG_SECTION = "section";

	/** Сколько товаров раздела показывать в команде (списки бывают на сотни предметов). */
	private static final int SHOP_SHOWN = 20;
	/** Особое «название» для команды: запустить ивент со случайным названием. */
	private static final String EVENT_RANDOM = "random";

	/** Предмета нет в прайсе — считать нечего. */
	private static final SimpleCommandExceptionType NO_PRICE =
			new SimpleCommandExceptionType(Component.translatable("marketplace.command.drift.noPrice"));

	/** Для указанного тика нет данных: сервер ещё не сообщил зерно мира. */
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
									.executes(MarketplaceCommands::startEvent)))
					.then(Commands.literal("stop")
							.executes(MarketplaceCommands::stopEvent));

			// Что лежит в разделе магазина. Нужно для проверки: список собирает сервер,
			// а игрок видит его только в окне — командой это видно и из консоли.
			LiteralArgumentBuilder<CommandSourceStack> shop = Commands.literal("shop")
					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.then(Commands.argument(ARG_SECTION, StringArgumentType.word())
							.executes(MarketplaceCommands::showShopSection));

			// Срочный заказ торговца: посмотреть текущий или запустить/остановить вручную (для проверки).
			LiteralArgumentBuilder<CommandSourceStack> urgent = Commands.literal("urgent")
					.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
					.executes(MarketplaceCommands::urgentStatus)
					.then(Commands.literal("status")
							.executes(MarketplaceCommands::urgentStatus))
					.then(Commands.literal("start")
							.executes(MarketplaceCommands::urgentStart))
					.then(Commands.literal("stop")
							.executes(MarketplaceCommands::urgentStop));

			dispatcher.register(Commands.literal("market")
					.executes(MarketplaceCommands::openMainMenu)
					.then(Commands.literal("help")
							.executes(MarketplaceCommands::showHelp))
					.then(price)
					.then(money)
					.then(drift)
					.then(shop)
					.then(event)
					.then(urgent));
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
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.drift",
				amplitude()).withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.event")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.shop")
				.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Component.translatable("marketplace.command.help.urgent")
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

	/**
	 * {@code /market shop <раздел>} — что лежит в разделе магазина.
	 *
	 * <p>Список собирает сервер: у клиента реестров мира нет, и книги своих зачарований там
	 * не построить (см. {@link ShopCatalog}). Команда берёт реестры у сервера и собирает
	 * список заново — так видно ровно то, что получит окно магазина.
	 */
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
		ShopCatalog.refresh(source.registryAccess());
		List<ItemStack> items = ShopCatalog.items(category);

		source.sendSuccess(() -> Component.translatable("marketplace.command.shop.header",
				Component.translatable(category.titleKey()),
				Component.literal(String.valueOf(items.size()))).withStyle(ChatFormatting.GOLD), false);

		for (int index = 0; index < items.size() && index < SHOP_SHOWN; index++) {
			ItemStack stack = items.get(index);
			OptionalDouble special = SpecialCatalog.priceOf(stack);
			double price = special.isPresent() ? special.getAsDouble() : ItemPrices.priceOf(stack);
			int number = index + 1;

			source.sendSuccess(() -> Component.translatable("marketplace.command.shop.item",
					Component.literal(number + ". "), stack.getHoverName(), money(price))
					.withStyle(special.isPresent() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY), false);
		}

		if (items.size() > SHOP_SHOWN) {
			source.sendSuccess(() -> Component.translatable("marketplace.command.shop.more",
					Component.literal(String.valueOf(items.size() - SHOP_SHOWN)))
					.withStyle(ChatFormatting.DARK_GRAY), false);
		}

		return items.size();
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
					money(total * EconomyConfig.buyMultiplier())), false);
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

	/**
	 * {@code /market event list} — что идёт сейчас и какие названия бывают.
	 *
	 * <p>Показывает и «активное время мира» — тики, которые копятся, только когда в игре есть
	 * игроки. По нему видно, что без игроков ивент замирает: число не растёт.
	 */
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
			// Запланированный ивент: видно, что день «выпал» и когда ивент начнётся.
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

	/**
	 * {@code /market event start <id|random>} — запустить ивент вручную. Второй ивент поверх
	 * идущего не создаётся: команда скажет, что сначала нужно остановить текущий.
	 */
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

	/** {@code /market event stop} — остановить активный ивент. Остановка объявляется всем в чат. */
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

	/** {@code /market urgent} — что за срочный заказ идёт сейчас. */
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
				Component.literal(quest.count() + " (" + quest.stacks() + " стаков)"),
				Component.literal(MoneyFormat.format(quest.reward())),
				MarketEvents.duration(quest.remaining())).withStyle(ChatFormatting.GOLD), false);

		return 1;
	}

	/** {@code /market urgent start} — запустить срочный заказ вручную (для проверки). */
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
				Component.literal(quest.count() + " (" + quest.stacks() + " стаков)"),
				Component.literal(MoneyFormat.format(quest.reward())),
				MarketEvents.duration(quest.remaining())).withStyle(ChatFormatting.GOLD), true);

		return 1;
	}

	/** {@code /market urgent stop} — досрочно завершить срочный заказ. */
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

	/** Отклонение цены ивентом: зелёным рост, красным падение. */
	private static Component eventPercent(MarketEvent event) {
		return Component.literal(event.percentText())
				.withStyle(event.rises() ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	/** Число с одним знаком после точки, точка как разделитель — для процентов в командах. */
	private static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** Отклонение дрейфа в процентах со знаком. */
	private static Component percent(double fraction) {
		return Component.literal(String.format("%+.2f%%", fraction * 100.0));
	}

	/** Амплитуда микро-дрейфа в процентах (берётся из настроек экономики) — для подсказок. */
	private static Component amplitude() {
		return Component.literal(String.format("%.1f", PriceDrift.amplitude() * 100.0));
	}
}
