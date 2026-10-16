package com.chr0m4ik.marketplace.menu;

import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderService;
import com.chr0m4ik.marketplace.market.MarketOrder;
import com.chr0m4ik.marketplace.market.MarketService;
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.market.KitCatalog;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	/**
	 * Сколько ячеек данных занимает одна сумма. Ячейка несёт только 16 бит: пакет данных окна
	 * ({@code ClientboundContainerSetDataPacket}) пишет числа как {@code short}, поэтому всё, что
	 * больше 32 767, приходит на клиент искажённым. Суммы считаем в копейках (до 2^63), значит
	 * режем их на четыре куска по 16 бит.
	 */
	private static final int MONEY_CELLS = 4;

	/** Где лежат суммы: баланс, деньги по ордерам и по неподтверждённым предметам. */
	private static final int BALANCE_DATA = 0;
	private static final int ORDERS_DATA = BALANCE_DATA + MONEY_CELLS;
	private static final int STAGED_DATA = ORDERS_DATA + MONEY_CELLS;

	/** Счётчики предметов, наценка и число открытых ячеек рынка. */
	private static final int ORDERS_COUNT_DATA = STAGED_DATA + MONEY_CELLS;
	private static final int STAGED_COUNT_DATA = ORDERS_COUNT_DATA + 1;
	private static final int MARKUP_DATA = STAGED_COUNT_DATA + 1;
	private static final int UNLOCKED_DATA = MARKUP_DATA + 1;

	/** Сколько ячеек занимает шапка данных: суммы, счётчики, наценка и открытые ячейки. */
	private static final int HEADER_SIZE = UNLOCKED_DATA + 1;

	/** Сколько всего ячеек данных синхронизируется с клиентом. */
	private static final int DATA_SIZE = HEADER_SIZE + MarketService.SLOTS * 2;

	/** С какого места в данных начинаются минуты до конца продажи по каждой ячейке. */
	private static final int CELL_DATA_START = HEADER_SIZE;

	/** Где лежат номер страницы магазина, всего страниц и номер раздела. */
	private static final int PAGE_DATA = HEADER_SIZE;

	/** С какого места начинаются наценки по ячейкам. */
	private static final int CELL_MARKUP_START = HEADER_SIZE + MarketService.SLOTS;

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

	/** Кнопки меню разделов магазина: выход снизу по центру, разделы — прямоугольником по центру. */
	public static final int BUY_SLOT_EXIT = 49;
	public static final int BUY_GRID_ROWS = 3;
	public static final int BUY_GRID_COLUMNS = 7;

	/** Кнопки списка товаров: выход, предыдущая и следующая страницы. */
	public static final int BUY_LIST_EXIT = 45;
	public static final int BUY_LIST_PREV = 47;
	public static final int BUY_LIST_NEXT = 51;

	/** Кнопки окна покупки: выход, количество, сам предмет, выдача, подтверждение. */
	public static final int BUY_ITEM_BACK = 9;
	public static final int BUY_ITEM_QUANTITY = 11;
	public static final int BUY_ITEM_PREVIEW = 13;
	public static final int BUY_ITEM_DROP = 15;
	public static final int BUY_ITEM_CONFIRM = 17;

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
	/** Ячейки, которые сейчас показывают подтверждённый ордер (по ним ловим момент продажи). */
	private final Set<Integer> orderCells = new HashSet<>();

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
		this.loadShopPage();
	}

	/** Заполняет ячейки окна списка товарами текущей страницы раздела. */
	private void loadShopPage() {
		if (this.serverPlayer == null || this.kind != WindowKind.BUY_LIST) {
			return;
		}

		MarketService.ShopState shop = MarketService.shop(this.serverPlayer.getUUID());
		ShopCatalog.Category[] categories = ShopCatalog.Category.values();
		ShopCatalog.Category category = categories[Math.max(0, Math.min(shop.category(), categories.length - 1))];
		List<ItemStack> page = ShopCatalog.page(category, shop.page());

		for (int i = 0; i < this.kind.tradeSlots(); i++) {
			this.tradeContainer.setItem(i, i < page.size() ? page.get(i).copy() : ItemStack.EMPTY);
		}
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

			if (order != null) {
				this.orderCells.add(i);
			}

			this.tradeContainer.setItem(i, order != null ? order.stack() : (staged == null ? ItemStack.EMPTY : staged));
		}
	}

	/** Красная панель — «ячейка не куплена». */
	private static ItemStack lockedPane() {
		// В 26.3 цветные предметы лежат в наборе ColorCollection, поэтому берём цвет через pick.
		ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.RED));
		pane.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.slot.locked")));
		return pane;
	}

	/** Открыта ли эта ячейка рынка (данные приходят с сервера, поэтому работает и на клиенте). */
	public boolean isCellUnlocked(int cell) {
		return cell < this.data.get(UNLOCKED_DATA);
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
				this.orderCells.remove(i);
			} else if (state.order(playerId, i) != null) {
				expected = state.order(playerId, i).stack();
				this.orderCells.add(i);
			} else if (this.orderCells.remove(i)) {
				// Ордер продался, пока окно открыто: ячейку сразу освобождаем,
				// иначе предмет можно было бы утащить второй раз.
				expected = ItemStack.EMPTY;
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

		if (this.kind == WindowKind.BUY) {
			// Разделы выкладываем прямоугольником по центру окна.
			ShopCatalog.Category[] categories = ShopCatalog.Category.values();
			int index = 0;

			for (int row = 1; row <= BUY_GRID_ROWS && index < categories.length; row++) {
				for (int col = 1; col <= BUY_GRID_COLUMNS && index < categories.length; col++) {
					ItemStack button = new ItemStack(categories[index].icon());
					button.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.category",
							Component.translatable(categories[index].titleKey()))));
					button.set(DataComponents.LORE, new ItemLore(List.of(
							Component.translatable("marketplace.gui.category.count",
									Component.literal(String.valueOf(ShopCatalog.items(categories[index]).size())))
									.withStyle(ChatFormatting.GRAY))));
					this.setButtonStack(row * 9 + col, button);
					index++;
				}
			}

			this.setButton(BUY_SLOT_EXIT, Items.BARRIER, "marketplace.gui.button.exit");
			return;
		}

		if (this.kind == WindowKind.BUY_LIST) {
			this.setButton(BUY_LIST_EXIT, Items.BARRIER, "marketplace.gui.button.exit");
			this.setButton(BUY_LIST_PREV, Items.SPECTRAL_ARROW, "marketplace.gui.button.prevPage");
			this.setButton(BUY_LIST_NEXT, Items.ARROW, "marketplace.gui.button.nextPage");
			return;
		}

		if (this.kind == WindowKind.BUY_ITEM) {
			this.setButton(BUY_ITEM_BACK, Items.BARRIER, "marketplace.gui.button.back");
			this.updateShopButtons();
			return;
		}

		this.setButton(BACK_SLOT, Items.ARROW, "marketplace.gui.button.back");
	}

	/** Кнопки окна покупки: количество, сам предмет, выдача и подтверждение. */
	private void updateShopButtons() {
		if (this.serverPlayer == null) {
			return;
		}

		MarketService.ShopState shop = MarketService.shop(this.serverPlayer.getUUID());
		ItemStack quantity = new ItemStack(Items.REPEATER);
		quantity.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.button.quantity",
				Component.literal(shop.quantityText()))));
		quantity.set(DataComponents.LORE, new ItemLore(List.of(
				Component.translatable("marketplace.gui.button.quantity.hint").withStyle(ChatFormatting.GRAY))));
		this.setButtonStack(BUY_ITEM_QUANTITY, quantity);

		// Сам предмет: только для показа, брать его нельзя.
		this.setButtonStack(BUY_ITEM_PREVIEW, shop.selected().copy());

		ItemStack drop = new ItemStack(Items.DISPENSER);
		drop.set(DataComponents.CUSTOM_NAME, plain(Component.translatable(shop.drop()
				? "marketplace.gui.button.drop.ground" : "marketplace.gui.button.drop.inventory")));

		if (shop.drop()) {
			drop.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}

		this.setButtonStack(BUY_ITEM_DROP, drop);
		this.setButton(BUY_ITEM_CONFIRM, Items.EMERALD, "marketplace.gui.button.buy");
	}

	/** Кладёт готовый предмет-кнопку в системный слот (номер — как его видит игрок). */
	private void setButtonStack(int containerSlot, ItemStack stack) {
		this.buttonContainer.setItem(containerSlot - this.kind.tradeSlots(), stack);
	}

	/**
	 * Кнопка смены цены: в подсказке перечислены все наценки, текущая выделена зелёным.
	 * По умолчанию — x1.
	 */
	private void updatePriceButton() {
		ItemStack button = new ItemStack(Items.GOLD_INGOT);
		button.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.button.price")));
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
		button.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.button.target",
				Component.translatable(this.targetMode
						? "marketplace.gui.button.target.on" : "marketplace.gui.button.target.off"))));
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
	 * Убирает курсив у названия предмета. Игра рисует любое изменённое название
	 * ({@link DataComponents#CUSTOM_NAME}) курсивом, а у кнопок он выглядит неаккуратно.
	 * Стиль снимаем у {@link MutableComponent}: сам {@code Component} неизменяемый и такого
	 * метода не имеет.
	 */
	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	/**
	 * @param containerSlot номер слота в верхней части окна (как его видит игрок),
	 *                      а не индекс внутри контейнера кнопок.
	 */
	private void setButton(int containerSlot, Item item, String nameKey) {
		ItemStack stack = new ItemStack(item);
		// Своё название предмета — игрок увидит его в подсказке при наведении.
		stack.set(DataComponents.CUSTOM_NAME, plain(Component.translatable(nameKey)));
		this.buttonContainer.setItem(containerSlot - this.kind.tradeSlots(), stack);
	}

	/** Кнопка, в названии которой есть значение (например, текущая наценка). */
	private void setButtonWithArg(int containerSlot, Item item, String nameKey, Component arg) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, plain(Component.translatable(nameKey, arg)));
		this.buttonContainer.setItem(containerSlot - this.kind.tradeSlots(), stack);
	}

	/** Наценка в виде «1», «1.25», «1.5», «1.75», «2». */
	private String markupText() {
		double markup = this.markupValue();
		return markup == Math.floor(markup) ? String.valueOf((int) markup) : String.valueOf(markup);
	}

	/** Наценка из данных окна (на сервере — из сейва, на клиенте — из синхронизированных данных). */
	private double markupValue() {
		return this.data.get(MARKUP_DATA) / 100.0;
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

		// Клик по товару в списке: выбираем предмет и открываем окно покупки.
		if (this.kind == WindowKind.BUY_LIST && input == ContainerInput.PICKUP && button == 0
				&& slotId >= 0 && slotId < this.kind.tradeSlots()) {
			this.selectShopItem(player, slotId);
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
				MarketService.setWindowTransition(true);
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

		if (this.kind == WindowKind.BUY_LIST) {
			if (slotId == BUY_LIST_EXIT) {
				MarketplaceMenus.open(serverPlayer, WindowKind.BUY);
			} else if (slotId == BUY_LIST_PREV) {
				this.turnShopPage(serverPlayer, -1);
			} else if (slotId == BUY_LIST_NEXT) {
				this.turnShopPage(serverPlayer, 1);
			}

			return;
		}

		if (this.kind == WindowKind.BUY_ITEM) {
			MarketService.ShopState shop = MarketService.shop(serverPlayer.getUUID());

			if (slotId == BUY_ITEM_BACK) {
				// Возврат к списку: количество и способ выдачи сбрасываются.
				MarketService.setShop(serverPlayer.getUUID(), shop.withQuantity(1).withDrop(false));
				MarketplaceMenus.open(serverPlayer, WindowKind.BUY_LIST);
			} else if (slotId == BUY_ITEM_QUANTITY) {
				MarketService.setShop(serverPlayer.getUUID(),
						shop.withQuantity(MarketService.nextQuantity(shop)));
				this.updateShopButtons();
				this.broadcastChanges();
			} else if (slotId == BUY_ITEM_DROP) {
				MarketService.setShop(serverPlayer.getUUID(), shop.withDrop(!shop.drop()));
				this.updateShopButtons();
				this.broadcastChanges();
			} else if (slotId == BUY_ITEM_CONFIRM) {
				this.buyShopItem(serverPlayer);
			}

			return;
		}

		if (this.kind == WindowKind.BUY) {
			if (slotId == BUY_SLOT_EXIT) {
				MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
			} else {
				// Разделы лежат прямоугольником по центру: строки 1…3, столбцы 1…7.
				int row = slotId / 9;
				int col = slotId % 9;

				if (row >= 1 && row <= BUY_GRID_ROWS && col >= 1 && col <= BUY_GRID_COLUMNS) {
					int index = (row - 1) * BUY_GRID_COLUMNS + (col - 1);

					if (index < ShopCatalog.Category.values().length) {
						MarketService.setShop(serverPlayer.getUUID(),
								MarketService.shop(serverPlayer.getUUID()).withCategory(index));
						MarketplaceMenus.open(serverPlayer, WindowKind.BUY_LIST);
					}
				}
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

	/** Выбрали товар в списке: запоминаем предмет и открываем окно покупки. */
	private void selectShopItem(Player player, int cell) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		ItemStack stack = this.tradeContainer.getItem(cell);

		if (stack.isEmpty()) {
			return;
		}

		MarketService.setShop(serverPlayer.getUUID(), MarketService.shop(serverPlayer.getUUID()).withSelected(stack.copy()));
		MarketplaceMenus.open(serverPlayer, WindowKind.BUY_ITEM);
	}

	/** Перелистывание страниц списка товаров (без переоткрытия окна). */
	private void turnShopPage(ServerPlayer player, int delta) {
		MarketService.ShopState shop = MarketService.shop(player.getUUID());
		ShopCatalog.Category[] categories = ShopCatalog.Category.values();
		ShopCatalog.Category category = categories[Math.max(0, Math.min(shop.category(), categories.length - 1))];
		int pages = ShopCatalog.pages(category);
		int page = Math.floorMod(shop.page() + delta, pages);
		MarketService.setShop(player.getUUID(), shop.withPage(page));
		this.loadShopPage();
		this.updateData();
		this.broadcastChanges();
	}

	/** Покупка выбранного предмета. Окно не закрывается — можно покупать дальше. */
	private void buyShopItem(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		MarketService.ShopState shop = MarketService.shop(player.getUUID());
		double cost = MoneyFormat.round(ItemPrices.priceOf(shop.selected()) * shop.count()
				* ItemPrices.BUY_MULTIPLIER);

		if (MarketService.buy(server, player, shop)) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.buy.bought",
					Component.literal(String.valueOf(shop.count())), shop.selected().getHoverName(),
					money(player.isCreative() ? 0.0 : cost)).withStyle(ChatFormatting.GREEN));
		} else {
			player.sendSystemMessage(Component.translatable("marketplace.gui.buy.noMoney", money(cost))
					.withStyle(ChatFormatting.RED));
		}

		this.updateData();
		this.broadcastChanges();
	}

	/** Выбрали товар в списке: запоминаем предмет и открываем окно покупки. */
	private void selectShopItemPlaceholder() {
		// Заглушка не нужна: метод selectShopItem(Player, int) объявлен ниже.
	}
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
			// Игрок положил предметы, но не оформил продажу — возвращаем их в инвентарь,
			// иначе рынок превращается в бесплатное хранилище. Ордера и переход в другое
			// окно рынка (подтверждение) не трогаем — иначе предметы не выставить.
			boolean transition = MarketService.isWindowTransition();
			MarketService.setWindowTransition(false);

			if (!transition) {
				for (int i = 0; i < this.kind.tradeSlots(); i++) {
					// Закрытые ячейки показывают панели — их возвращать нельзя.
					if (i >= this.getUnlockedSlots() || this.orderCells.contains(i)) {
						continue;
					}

					ItemStack stack = this.tradeContainer.getItem(i);

					if (stack.isEmpty()) {
						continue;
					}

					this.tradeContainer.setItem(i, ItemStack.EMPTY);
					player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
				}
			}

			this.saveMarketCells();
			return;
		}

		// Возвращать предметы в инвентарь имеет смысл только у торговца: там в слотах лежат
		// вещи игрока. В остальных окнах (список товаров магазина, подтверждение) слоты
		// служат только для показа, и «возвращать» их нельзя — иначе товары выпадут на пол.
		if (this.kind != WindowKind.TRADER) {
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

		setMoney(BALANCE_DATA, MarketplaceEconomy.getBalance(server, playerId));
		// Список книг зачарований строится по реестру мира — отдаём его каталогу магазина.
		ShopCatalog.setRegistries(server.registryAccess());
		// Наборы тоже собираются из реестров (зачарования и зелья внутри).
		KitCatalog.setRegistries(server.registryAccess());
		this.data.set(MARKUP_DATA, (int) Math.round(state.markup(playerId) * 100.0));
		this.data.set(UNLOCKED_DATA, MarketService.unlockedSlots(state, playerId));

		if (this.kind == WindowKind.BUY_ITEM) {
			// Номер выбранного количества — клиент считает по нему цену в подсказке.
			this.data.set(PAGE_DATA, MarketService.shop(playerId).quantityIndex());
			return;
		}

		if (this.kind == WindowKind.BUY_LIST) {
			// Данные для надписи «страница X из Y» справа вверху.
			MarketService.ShopState shop = MarketService.shop(playerId);
			ShopCatalog.Category[] categories = ShopCatalog.Category.values();
			ShopCatalog.Category category = categories[Math.max(0, Math.min(shop.category(), categories.length - 1))];
			this.data.set(PAGE_DATA, shop.page() + 1);
			this.data.set(PAGE_DATA + 1, ShopCatalog.pages(category));
			this.data.set(PAGE_DATA + 2, shop.category());
			return;
		}

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

		setMoney(ORDERS_DATA, ordersMoney);
		setMoney(STAGED_DATA, stagedMoney);
		this.data.set(ORDERS_COUNT_DATA, ordersCount);
		this.data.set(STAGED_COUNT_DATA, stagedCount);

		// Сколько минут осталось каждому ордеру и с какой наценкой он выставлен.
		for (int i = 0; i < MarketService.SLOTS; i++) {
			MarketOrder order = state.order(playerId, i);
			ItemStack staged = state.staging(playerId).get(i);
			// Время продажи игроку не показываем: пусть это остаётся загадкой.
			this.data.set(CELL_DATA_START + i, -1);
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

	/**
	 * Кладёт сумму в копейках в {@link #MONEY_CELLS} ячеек данных — по 16 бит в каждую.
	 * Так приходится делать потому, что пакет данных окна передаёт числа как {@code short}:
	 * значение больше 32 767 в одну ячейку не влезает и приходит на клиент искажённым.
	 */
	private void setMoney(int index, double amount) {
		long cents = Math.round(amount * 100.0);

		for (int i = 0; i < MONEY_CELLS; i++) {
			this.data.set(index + i, (int) ((cents >>> (16 * i)) & 0xFFFFL));
		}
	}

	/** Собирает сумму обратно из ячеек (см. {@link #setMoney}). */
	private double money(int index) {
		long cents = 0L;

		for (int i = 0; i < MONEY_CELLS; i++) {
			cents |= (long) (this.data.get(index + i) & 0xFFFF) << (16 * i);
		}

		return cents / 100.0;
	}

	/** Номер текущей страницы магазина (с единицы). */
	public int getShopPage() {
		return this.data.get(PAGE_DATA);
	}

	/** Номер выбранного количества в окне покупки. */
	public int getShopQuantityIndex() {
		return this.data.get(PAGE_DATA);
	}

	/** Сколько всего страниц в разделе. */
	public int getShopPages() {
		return Math.max(1, this.data.get(PAGE_DATA + 1));
	}

	/** Баланс, который видит клиент (в долларах). */
	public double getBalance() {
		return money(BALANCE_DATA);
	}

	/** Сколько дадут за все подтверждённые ордера. */
	public double getOrdersPayout() {
		return money(ORDERS_DATA);
	}

	/** Сколько дадут за предметы, которые положили, но ещё не подтвердили. */
	public double getStagedPayout() {
		return money(STAGED_DATA);
	}

	public int getOrdersCount() {
		return this.data.get(ORDERS_COUNT_DATA);
	}

	public int getStagedCount() {
		return this.data.get(STAGED_COUNT_DATA);
	}

	/** Текущая наценка (x1…x2). */
	public double getMarkup() {
		return this.data.get(MARKUP_DATA) / 100.0;
	}

	/** Сколько ячеек рынка открыто. */
	public int getUnlockedSlots() {
		return this.data.get(UNLOCKED_DATA);
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
