package com.chr0m4ik.marketplace.menu;

import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderService;
import com.chr0m4ik.marketplace.market.MarketOrder;
import com.chr0m4ik.marketplace.market.MarketService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Окно рынка. Один класс на все окна мода — вид задаётся через {@link WindowKind}.
 *
 * <p>Как устроено:
 * <ul>
 *     <li>верхняя часть окна делится на слоты для предметов ({@link WindowKind#tradeSlots()}) и
 *     слоты-кнопки. Кнопка — это предмет, который нельзя забрать или подменить, а клик по нему
 *     выполняет действие (см. {@link #clicked});</li>
 *     <li>нижняя часть — обычный инвентарь игрока;</li>
 *     <li>баланс показывается на клиенте через {@link ContainerData} — штатный способ передать
 *     числа из серверной части окна в клиентскую.</li>
 * </ul>
 */
public class MarketplaceMenu extends AbstractContainerMenu {
	/** Баланс передаём как одно long-число (копейки), а слоты данных — это int. Отсюда 2. */
	private static final int DATA_SIZE = 10 + MarketService.SLOTS * 2;

	/** С какого места в данных начинаются минуты до конца продажи по каждой ячейке. */
	private static final int CELL_DATA_START = 10;

	/** С какого места начинаются наценки по ячейкам. */
	private static final int CELL_MARKUP_START = 10 + MarketService.SLOTS;

	/** Слоты кнопок главного меню (средний ряд: 10, 13, 16). */
	public static final int MAIN_SLOT_TRADER = 10;
	public static final int MAIN_SLOT_SELL = 13;
	public static final int MAIN_SLOT_BUY = 16;

	/** Кнопки в системной строке окна торговца. */
	public static final int TRADER_SLOT_CANCEL = 45;
	public static final int TRADER_SLOT_INFO = 47;
	public static final int TRADER_SLOT_TRADE = 49;

	/** Кнопки окна рынка. */
	public static final int MARKET_SLOT_EXIT = 45;
	public static final int MARKET_SLOT_BUY = 47;
	public static final int MARKET_SLOT_CHECKOUT = 49;
	public static final int MARKET_SLOT_INFO = 51;
	public static final int MARKET_SLOT_TARGET = 53;

	/** Кнопки окна подтверждения ордеров (как в главном меню — по центру). */
	public static final int CONFIRM_SLOT_EXIT = 10;
	public static final int CONFIRM_SLOT_PRICE = 13;
	public static final int CONFIRM_SLOT_CONFIRM = 16;

	/** Кнопки окна покупки слота. */
	public static final int SLOT_WINDOW_CANCEL = 10;
	public static final int SLOT_WINDOW_INFO = 13;
	public static final int SLOT_WINDOW_CONFIRM = 16;

	/** Кнопка «Назад» в окнах-заготовках. */
	public static final int BACK_SLOT = 49;

	private final WindowKind kind;
	/** Предметы-кнопки. */
	private final SimpleContainer buttonContainer;
	/** Предметы, выставленные на продажу (у торговца — товар). */
	private final SimpleContainer tradeContainer;
	private final ContainerData data;
	/** Ссылка на игрока есть только на сервере; на клиенте здесь null. */
	private final ServerPlayer serverPlayer;
	/** Включён ли безопасный режим снятия ордеров (кнопка-мишень). */
	private boolean targetMode;

	public MarketplaceMenu(WindowKind kind, int syncId, Inventory inventory) {
		super(MarketplaceMenus.typeOf(kind), syncId);
		this.kind = kind;
		// Размер 0 у SimpleContainer допустим, но чтобы не думать об этом — минимум одна ячейка.
		this.buttonContainer = new SimpleContainer(Math.max(1, kind.buttonSlots()));
		this.tradeContainer = new SimpleContainer(Math.max(1, kind.tradeSlots()));
		this.serverPlayer = inventory.player instanceof ServerPlayer player ? player : null;

		int total = kind.containerSlots();
		int tradeSlots = kind.tradeSlots();

		// Сначала слоты под товар. В окне подтверждения их нельзя брать — только смотреть,
		// а закрытые ячейки рынка вообще ничего не принимают.
		for (int i = 0; i < tradeSlots; i++) {
			if (kind.readOnlySlots()) {
				this.addSlot(new ActionSlot(this.tradeContainer, i, slotX(i), slotY(i)));
			} else if (kind == WindowKind.SELL) {
				this.addSlot(new MarketSlot(this, this.tradeContainer, i, slotX(i), slotY(i)));
			} else {
				this.addSlot(new Slot(this.tradeContainer, i, slotX(i), slotY(i)));
			}
		}

		// Потом слоты-кнопки.
		for (int i = tradeSlots; i < total; i++) {
			this.addSlot(new ActionSlot(this.buttonContainer, i - tradeSlots, slotX(i), slotY(i)));
		}

		// Нижняя часть окна: инвентарь игрока (3 ряда + хотбар).
		this.addStandardInventorySlots(inventory, 8, kind.inventoryY());

		this.data = new SimpleContainerData(DATA_SIZE);
		this.addDataSlots(this.data);

		// Сначала данные (в них лежит текущая наценка), потом кнопки: кнопка цены выделяет
		// в подсказке именно текущую наценку, поэтому ей нужны уже заполненные данные.
		this.updateData();
		this.fillButtons();
		this.loadMarketCells();
	}
	private void loadMarketCells() {
		if (this.serverPlayer == null || !this.hasMarketCells()) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(this.serverPlayer.level().getServer());
		UUID playerId = this.serverPlayer.getUUID();
		int unlocked = MarketService.unlockedSlots(state, playerId);

		for (int i = 0; i < this.kind.tradeSlots(); i++) {
			if (i >= unlocked) {
				this.tradeContainer.setItem(i, lockedPane());
				continue;
			}

			MarketOrder order = state.order(playerId, i);
			ItemStack staged = state.staging(playerId).get(i);
			this.tradeContainer.setItem(i, order != null ? order.stack() : (staged == null ? ItemStack.EMPTY : staged));
		}
	}

	/** Красная панель — «ячейка не куплена». */
	private static ItemStack lockedPane() {
		// В 26.3 цветные предметы лежат в наборе ColorCollection, поэтому берём цвет через pick.
		ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.RED));
		pane.set(DataComponents.CUSTOM_NAME, Component.translatable("marketplace.gui.slot.locked"));
		return pane;
	}

	/** Открыта ли эта ячейка рынка (данные приходят с сервера, поэтому работает и на клиенте). */
	public boolean isCellUnlocked(int cell) {
		return cell < this.data.get(9);
	}

	/** Есть ли в этом окне ячейки рынка. */
	private boolean hasMarketCells() {
		return this.kind == WindowKind.SELL;
	}

	/**
	 * Лежит ли в ячейке подтверждённый ордер. Такую ячейку нельзя утащить мышкой —
	 * сначала нужно включить кнопку-мишень и нажать shift.
	 */
	public boolean isCellOrder(int cell) {
		return cell >= 0 && cell < MarketService.SLOTS && this.getCellMinutes(cell) >= 0;
	}

	/** Сохраняет то, что игрок разложил по ячейкам, в сейв (кроме подтверждённых ордеров). */
	private void saveMarketCells() {
		if (this.serverPlayer == null || this.kind != WindowKind.SELL) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(this.serverPlayer.level().getServer());
		UUID playerId = this.serverPlayer.getUUID();
		int unlocked = MarketService.unlockedSlots(state, playerId);
		Map<Integer, ItemStack> items = new HashMap<>();

		for (int i = 0; i < Math.min(this.kind.tradeSlots(), unlocked); i++) {
			if (state.order(playerId, i) != null) {
				continue;
			}

			ItemStack stack = this.tradeContainer.getItem(i);

			if (!stack.isEmpty()) {
				items.put(i, stack.copy());
			}
		}

		state.setStaging(playerId, items);
	}

	/**
	 * Забрать предмет из ячейки рынка. Неподтверждённый предмет забирается свободно,
	 * а подтверждённый ордер — только когда включён безопасный режим (кнопка-мишень).
	 */
	private void takeMarketCell(Player player, int cell) {
		if (this.serverPlayer == null || cell < 0 || cell >= this.kind.tradeSlots()
				|| !this.isCellUnlocked(cell)) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(this.serverPlayer.level().getServer());
		UUID playerId = this.serverPlayer.getUUID();
		MarketOrder order = state.order(playerId, cell);
		ItemStack stack;

		if (order != null) {
			if (!this.targetMode) {
				player.sendSystemMessage(Component.translatable("marketplace.gui.market.needTarget")
						.withStyle(ChatFormatting.RED));
				return;
			}

			state.removeOrder(playerId, cell);
			stack = order.stack();
			player.sendSystemMessage(Component.translatable("marketplace.gui.market.cancelled",
					stack.getHoverName()).withStyle(ChatFormatting.YELLOW));
		} else {
			stack = this.tradeContainer.getItem(cell);

			if (stack.isEmpty()) {
				return;
			}

			this.tradeContainer.setItem(cell, ItemStack.EMPTY);
		}

		player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
		this.saveMarketCells();
		this.updateData();
		this.broadcastChanges();
	}

	/**
	 * Приводит ячейки в порядок: показывает ордера из сейва и красные панели на закрытых ячейках.
	 * Ячейки без ордера НЕ трогаем — в них лежит то, что игрок только что положил (иначе предметы
	 * пропадали бы: сохранение в сейв идёт отдельно и могло ещё не сработать).
	 */
	private void syncMarketCells() {
		if (this.serverPlayer == null || this.kind != WindowKind.SELL) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(this.serverPlayer.level().getServer());
		UUID playerId = this.serverPlayer.getUUID();
		int unlocked = MarketService.unlockedSlots(state, playerId);

		for (int i = 0; i < this.kind.tradeSlots(); i++) {
			ItemStack expected = null;

			if (i >= unlocked) {
				expected = lockedPane();
			} else if (state.order(playerId, i) != null) {
				expected = state.order(playerId, i).stack();
			}

			if (expected != null && !ItemStack.matches(this.tradeContainer.getItem(i), expected)) {
				this.tradeContainer.setItem(i, expected);
			}
		}
	}

	/** Игрок что-то переложил в окне — сразу сохраняем в сейв. */
	@Override
	public void slotsChanged(Container container) {
		super.slotsChanged(container);

		if (container == this.tradeContainer) {
			this.saveMarketCells();
		}
	}

	private static int slotX(int index) {
		return 8 + (index % 9) * 18;
	}

	private static int slotY(int index) {
		return 18 + (index / 9) * 18;
	}

	/** Раскладываем предметы-кнопки по своим слотам. */
	private void fillButtons() {
		if (this.kind == WindowKind.MAIN) {
			this.setButton(MAIN_SLOT_TRADER, Items.EMERALD, "marketplace.gui.button.trader");
			this.setButton(MAIN_SLOT_SELL, Items.CLOCK, "marketplace.gui.button.sell");
			this.setButton(MAIN_SLOT_BUY, Items.CHEST, "marketplace.gui.button.buy");
			return;
		}

		if (this.kind == WindowKind.TRADER) {
			this.setButton(TRADER_SLOT_CANCEL, Items.BARRIER, "marketplace.gui.button.cancel");
			this.setButton(TRADER_SLOT_INFO, Items.PAPER, "marketplace.gui.button.total");
			this.setButton(TRADER_SLOT_TRADE, Items.EMERALD, "marketplace.gui.button.trade");
			return;
		}

		if (this.kind == WindowKind.SELL) {
			this.setButton(MARKET_SLOT_EXIT, Items.BARRIER, "marketplace.gui.button.exit");
			this.setButton(MARKET_SLOT_BUY, Items.ITEM_FRAME, "marketplace.gui.button.buySlot");
			this.setButton(MARKET_SLOT_CHECKOUT, Items.EMERALD, "marketplace.gui.button.checkout");
			this.setButton(MARKET_SLOT_INFO, Items.PAPER, "marketplace.gui.button.info");
			this.updateTargetButton();
			return;
		}

		if (this.kind == WindowKind.CONFIRM) {
			this.setButton(CONFIRM_SLOT_EXIT, Items.BARRIER, "marketplace.gui.button.cancel");
			this.setButton(CONFIRM_SLOT_CONFIRM, Items.EMERALD, "marketplace.gui.button.confirm");
			this.updatePriceButton();
			return;
		}

		if (this.kind == WindowKind.BUY_SLOT) {
			this.setButton(SLOT_WINDOW_CANCEL, Items.BARRIER, "marketplace.gui.button.cancel");
			this.setButton(SLOT_WINDOW_INFO, Items.PAPER, "marketplace.gui.button.slotInfo");
			this.setButton(SLOT_WINDOW_CONFIRM, Items.EMERALD, "marketplace.gui.button.confirm");
			return;
		}

		this.setButton(BACK_SLOT, Items.ARROW, "marketplace.gui.button.back");
	}

	/**
	 * Кнопка смены цены: в подсказке перечислены все наценки, текущая выделена зелёным.
	 * По умолчанию — x1.
	 */
	private void updatePriceButton() {
		ItemStack button = new ItemStack(Items.GOLD_INGOT);
		button.set(DataComponents.CUSTOM_NAME, Component.translatable("marketplace.gui.button.price"));
		List<Component> lore = new ArrayList<>();
		double current = this.markupValue();

		for (double markup : MarketService.MARKUPS) {
			MutableComponent line = Component.translatable("marketplace.gui.button.price.line", text(markup));
			lore.add(Math.abs(markup - current) < 0.001 ? line.withStyle(ChatFormatting.GREEN) : line);
		}

		button.set(DataComponents.LORE, new ItemLore(lore));
		this.buttonContainer.setItem(CONFIRM_SLOT_PRICE - this.kind.tradeSlots(), button);
	}

	/** Кнопка «снять ордер»: включён ли сейчас безопасный режим снятия. */
	private void updateTargetButton() {
		ItemStack button = new ItemStack(Items.TARGET);
		button.set(DataComponents.CUSTOM_NAME, Component.translatable("marketplace.gui.button.target",
				Component.translatable(this.targetMode
						? "marketplace.gui.button.target.on" : "marketplace.gui.button.target.off")));
		button.set(DataComponents.LORE, new ItemLore(List.of(
				Component.translatable("marketplace.gui.button.target.hint").withStyle(ChatFormatting.GRAY))));

		if (this.targetMode) {
			// Подсвечиваем кнопку, как зачарованную — видно, что режим включён.
			button.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}

		this.buttonContainer.setItem(MARKET_SLOT_TARGET - this.kind.tradeSlots(), button);
	}

	/** Наценка в виде «1», «1.25», «1.5», «1.75», «2». */
	private static String text(double markup) {
		return markup == Math.floor(markup) ? String.valueOf((int) markup) : String.valueOf(markup);
	}

	/**
	 * @param containerSlot номер слота в верхней части окна (как его видит игрок),
	 *                      а не индекс внутри контейнера кнопок.
	 */
	private void setButton(int containerSlot, Item item, String nameKey) {
		ItemStack stack = new ItemStack(item);
		// Своё название предмета — игрок увидит его в подсказке при наведении.
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable(nameKey));
		this.buttonContainer.setItem(containerSlot - this.kind.tradeSlots(), stack);
	}

	/** Кнопка, в названии которой есть значение (например, текущая наценка). */
	private void setButtonWithArg(int containerSlot, Item item, String nameKey, Component arg) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable(nameKey, arg));
		this.buttonContainer.setItem(containerSlot - this.kind.tradeSlots(), stack);
	}

	/** Наценка в виде «1», «1.25», «1.5», «1.75», «2». */
	private String markupText() {
		double markup = this.markupValue();
		return markup == Math.floor(markup) ? String.valueOf((int) markup) : String.valueOf(markup);
	}

	/** Наценка из данных окна (на сервере — из сейва, на клиенте — из синхронизированных данных). */
	private double markupValue() {
		return this.data.get(8) / 100.0;
	}

	/**
	 * Клик по слоту. Слоты-кнопки перехватываем целиком: ни взять предмет, ни положить,
	 * ни выбросить — только выполнить действие. Слоты с товаром работают как обычно.
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		if (this.isButtonSlot(slotId)) {
			if (input == ContainerInput.PICKUP && button == 0) {
				this.onButtonClick(slotId, player);
			}

			return;
		}

		// Shift-клик по ячейке рынка: забрать предмет или снять ордер.
		if (input == ContainerInput.QUICK_MOVE && this.hasMarketCells()
				&& slotId >= 0 && slotId < this.kind.tradeSlots()) {
			this.takeMarketCell(player, slotId);
			return;
		}

		super.clicked(slotId, button, input, player);
	}

	/** Является ли слот верхней части окна кнопкой. */
	public boolean isButtonSlot(int slotIndex) {
		return slotIndex >= this.kind.tradeSlots() && slotIndex < this.kind.containerSlots();
	}

	/** Что делать при нажатии на кнопку. */
	private void onButtonClick(int slotId, Player player) {
		// Окна открывает и сделки проводит только сервер: на клиенте serverPlayer пустой.
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (this.kind == WindowKind.MAIN) {
			if (slotId == MAIN_SLOT_TRADER) {
				MarketplaceMenus.open(serverPlayer, WindowKind.TRADER);
			} else if (slotId == MAIN_SLOT_SELL) {
				MarketplaceMenus.open(serverPlayer, WindowKind.SELL);
			} else if (slotId == MAIN_SLOT_BUY) {
				MarketplaceMenus.open(serverPlayer, WindowKind.BUY);
			}

			return;
		}

		if (this.kind == WindowKind.TRADER) {
			if (slotId == TRADER_SLOT_CANCEL) {
				// Возврат в главное меню; товар вернётся в инвентарь сам (см. removed).
				MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
			} else if (slotId == TRADER_SLOT_TRADE) {
				this.tradeToTrader(serverPlayer);
			}

			// Кнопка «итоговая цена» ничего не делает — она только показывает подсказку.
			return;
		}

		if (this.kind == WindowKind.SELL) {
			if (slotId == MARKET_SLOT_EXIT) {
				// Предметы остаются в ячейках рынка: они лежат в сейве, а не в окне.
				this.saveMarketCells();
				MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
			} else if (slotId == MARKET_SLOT_BUY) {
				this.saveMarketCells();
				MarketplaceMenus.open(serverPlayer, WindowKind.BUY_SLOT);
			} else if (slotId == MARKET_SLOT_CHECKOUT) {
				this.saveMarketCells();
				MarketplaceMenus.open(serverPlayer, WindowKind.CONFIRM);
			} else if (slotId == MARKET_SLOT_TARGET) {
				// Безопасный режим: пока он включён, shift-клик снимает ордер.
				this.targetMode = !this.targetMode;
				this.updateTargetButton();
				this.broadcastChanges();
			}

			// Кнопка «информация» ничего не делает — она только показывает подсказку.
			return;
		}

		if (this.kind == WindowKind.CONFIRM) {
			if (slotId == CONFIRM_SLOT_EXIT) {
				MarketplaceMenus.open(serverPlayer, WindowKind.SELL);
			} else if (slotId == CONFIRM_SLOT_PRICE) {
				this.cycleMarkup(serverPlayer);
			} else if (slotId == CONFIRM_SLOT_CONFIRM) {
				this.confirmOrders(serverPlayer);
			}

			return;
		}

		if (this.kind == WindowKind.BUY_SLOT) {
			if (slotId == SLOT_WINDOW_CONFIRM) {
				this.buyNextSlot(serverPlayer);
			} else if (slotId == SLOT_WINDOW_CANCEL) {
				MarketplaceMenus.open(serverPlayer, WindowKind.SELL);
			}

			return;
		}

		if (slotId == BACK_SLOT) {
			MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
		}
	}

	/** Переключение наценки по кругу: x1 → x1,25 → x1,5 → x1,75 → x2 → x1. */
	private void cycleMarkup(ServerPlayer player) {
		MarketplaceState state = MarketplaceEconomy.state(player.level().getServer());
		state.setMarkup(player.getUUID(), MarketService.nextMarkup(state.markup(player.getUUID())));
		// Обновляем кнопку и данные на месте — окно не переоткрываем, чтобы мышка не прыгала.
		this.updateData();
		this.updatePriceButton();
		this.broadcastChanges();
	}

	/** Подтверждение: неподтверждённые предметы становятся ордерами. */
	private void confirmOrders(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(server);
		UUID playerId = player.getUUID();
		Map<Integer, ItemStack> staged = state.staging(playerId);

		// Если в этой сессии ничего не выложили — кнопка ничего не делает.
		if (staged.isEmpty()) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.market.nothing")
					.withStyle(ChatFormatting.RED));
			return;
		}

		long now = GameTime.now(server);
		double markup = state.markup(playerId);
		int created = 0;

		for (Map.Entry<Integer, ItemStack> entry : Map.copyOf(staged).entrySet()) {
			if (MarketService.placeOrder(state, playerId, entry.getKey(), entry.getValue(), markup, now,
					server.overworld().getRandom()) != null) {
				created++;
			}
		}

		state.setStaging(playerId, Map.of());
		// Наценку сбрасываем: по умолчанию всегда x1.
		state.setMarkup(playerId, 1.0);
		MarketplaceMenus.open(player, WindowKind.SELL);

		if (created > 0) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.market.listed",
					Component.literal(String.valueOf(created))).withStyle(ChatFormatting.GREEN));
		}
	}

	/** Покупка следующего слота рынка. */
	private void buyNextSlot(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(server);
		double price = MarketService.slotPrice(state, player.getUUID());

		if (MarketService.buySlot(server, player.getUUID())) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.slot.bought", money(price))
					.withStyle(ChatFormatting.GREEN));
			// Окно не переоткрываем: обновляем данные, чтобы сразу показалась новая цена слота.
			this.updateData();
			this.broadcastChanges();
		} else {
			player.sendSystemMessage(Component.translatable("marketplace.gui.slot.noMoney", money(price))
					.withStyle(ChatFormatting.RED));
		}
	}

	/** Сделка с торговцем: товар исчезает, деньги начисляются по цене с уценкой. */
	private void tradeToTrader(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		TraderService.Sale sale = TraderService.sell(server, player, this.tradeContainer, this.kind.tradeSlots());

		if (sale.isEmpty()) {
			if (sale.limitReached()) {
				sendLimitMessage(player);
			}

			return;
		}

		this.broadcastChanges();

		player.sendSystemMessage(Component.translatable("marketplace.gui.trader.sold",
				Component.literal(String.valueOf(sale.itemCount())), money(sale.payout())));

		if (sale.limitReached()) {
			sendLimitMessage(player);
		}
	}

	/** Сообщение о том, что торговец больше не берёт столько за сегодня. */
	private static void sendLimitMessage(ServerPlayer player) {
		player.sendSystemMessage(Component.translatable("marketplace.gui.trader.limit")
				.withStyle(ChatFormatting.RED));
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		int tradeSlots = this.kind.tradeSlots();
		int containerSlots = this.kind.containerSlots();

		// В главном меню shift-клик ничего не делает: там одни кнопки.
		if (tradeSlots == 0 || slotIndex < 0 || slotIndex >= this.slots.size()) {
			return ItemStack.EMPTY;
		}

		Slot slot = this.slots.get(slotIndex);

		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack moved = stack.copy();

		if (slotIndex < containerSlots) {
			// Из окна торговца — назад в инвентарь.
			if (!this.moveItemStackTo(stack, containerSlots, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (!this.moveItemStackTo(stack, 0, tradeSlots, false)) {
			// Из инвентаря — в окно торговца.
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}

		return moved;
	}

	@Override
	public boolean stillValid(Player player) {
		// Окно не привязано к блоку, поэтому всегда доступно.
		return true;
	}

	/**
	 * Окно закрыли. Товар торговца возвращаем в инвентарь, а ячейки рынка — нет: они лежат
	 * в сейве мира и остаются в окне (забрать предмет можно shift-кликом по ячейке).
	 */
	@Override
	public void removed(Player player) {
		super.removed(player);

		// На клиенте ничего не делаем: там serverPlayer пустой, и предметы продублировались бы.
		if (this.serverPlayer == null) {
			return;
		}

		if (this.kind == WindowKind.SELL) {
			this.saveMarketCells();
			return;
		}

		if (this.kind == WindowKind.CONFIRM) {
			return;
		}

		for (int i = 0; i < this.kind.tradeSlots(); i++) {
			ItemStack stack = this.tradeContainer.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			this.tradeContainer.setItem(i, ItemStack.EMPTY);
			// Штатный способ вернуть предметы: что не влезет в инвентарь — упадёт под игроком.
			player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
		}
	}

	@Override
	public void broadcastChanges() {
		this.updateData();
		this.saveMarketCells();
		this.syncMarketCells();
		super.broadcastChanges();
	}

	/**
	 * Серверная часть: кладём в слоты данных всё, что нужно клиенту для подсказок —
	 * баланс, суммы по ордерам и неподтверждённым предметам, их количество и наценку.
	 */
	private void updateData() {
		if (this.serverPlayer == null) {
			return;
		}

		MinecraftServer server = this.serverPlayer.level().getServer();

		if (server == null) {
			return;
		}

		UUID playerId = this.serverPlayer.getUUID();
		MarketplaceState state = MarketplaceEconomy.state(server);

		setMoney(0, MarketplaceEconomy.getBalance(server, playerId));
		this.data.set(8, (int) Math.round(state.markup(playerId) * 100.0));
		this.data.set(9, MarketService.unlockedSlots(state, playerId));

		if (this.kind != WindowKind.SELL) {
			return;
		}

		double ordersMoney = 0.0;
		int ordersCount = 0;

		for (MarketOrder order : state.orders(playerId).values()) {
			ordersMoney += order.payout();
			ordersCount += order.stack().getCount();
		}

		double stagedMoney = 0.0;
		int stagedCount = 0;

		for (ItemStack stack : state.staging(playerId).values()) {
			stagedMoney += MarketService.payoutFor(stack, state.markup(playerId));
			stagedCount += stack.getCount();
		}

		setMoney(2, ordersMoney);
		setMoney(4, stagedMoney);
		this.data.set(6, ordersCount);
		this.data.set(7, stagedCount);

		// Сколько минут осталось каждому ордеру и с какой наценкой он выставлен.
		long now = GameTime.now(server);

		for (int i = 0; i < MarketService.SLOTS; i++) {
			MarketOrder order = state.order(playerId, i);
			ItemStack staged = state.staging(playerId).get(i);
			this.data.set(CELL_DATA_START + i,
					order == null ? -1 : (int) Math.min(Integer.MAX_VALUE, order.ticksLeft(now) / 1200L));
			this.data.set(CELL_MARKUP_START + i,
					order != null ? (int) Math.round(order.markup() * 100.0)
							: (staged == null ? 0 : (int) Math.round(state.markup(playerId) * 100.0)));
		}
	}

	/** Сколько минут осталось продаваться ордеру в этой ячейке ({@code -1} — ордера нет). */
	public int getCellMinutes(int cell) {
		if (cell < 0 || cell >= MarketService.SLOTS) {
			return -1;
		}

		return this.data.get(CELL_DATA_START + cell);
	}

	/** С какой наценкой выставлен предмет в этой ячейке (0 — ячейка пуста). */
	public double getCellMarkup(int cell) {
		if (cell < 0 || cell >= MarketService.SLOTS) {
			return 0.0;
		}

		return this.data.get(CELL_MARKUP_START + cell) / 100.0;
	}

	/** Кладёт сумму в копейках в две ячейки данных (числа там только int). */
	private void setMoney(int index, double amount) {
		long cents = Math.round(amount * 100.0);
		this.data.set(index, (int) (cents >>> 32));
		this.data.set(index + 1, (int) cents);
	}

	private double money(int index) {
		long cents = ((long) this.data.get(index) << 32) | (this.data.get(index + 1) & 0xFFFFFFFFL);
		return cents / 100.0;
	}

	/** Баланс, который видит клиент (в долларах). */
	public double getBalance() {
		return money(0);
	}

	/** Сколько дадут за все подтверждённые ордера. */
	public double getOrdersPayout() {
		return money(2);
	}

	/** Сколько дадут за предметы, которые положили, но ещё не подтвердили. */
	public double getStagedPayout() {
		return money(4);
	}

	public int getOrdersCount() {
		return this.data.get(6);
	}

	public int getStagedCount() {
		return this.data.get(7);
	}

	/** Текущая наценка (x1…x2). */
	public double getMarkup() {
		return this.data.get(8) / 100.0;
	}

	/** Сколько ячеек рынка открыто. */
	public int getUnlockedSlots() {
		return this.data.get(9);
	}

	/** Цена следующего слота рынка: считаем из числа открытых ячеек — так надёжнее. */
	public double getNextSlotPrice() {
		return MarketService.slotPriceFor(this.getUnlockedSlots());
	}

	public WindowKind getKind() {
		return this.kind;
	}

	/** Сколько слотов отведено под товар (нужно клиенту для подсказок и подсчётов). */
	public int tradeSlotCount() {
		return this.kind.tradeSlots();
	}

	/** Контейнер с товаром — по нему клиент считает итоговую цену в подсказке. */
	public Container getTradeContainer() {
		return this.tradeContainer;
	}

	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount)).withStyle(ChatFormatting.GREEN);
	}
}
