package com.chr0m4ik.marketplace.menu;

import com.chr0m4ik.marketplace.economy.GameTime;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderQuest;
import com.chr0m4ik.marketplace.economy.TraderQuests;
import com.chr0m4ik.marketplace.economy.TraderService;
import com.chr0m4ik.marketplace.economy.UrgentQuest;
import com.chr0m4ik.marketplace.economy.UrgentQuests;
import com.chr0m4ik.marketplace.event.MarketEvents;
import com.chr0m4ik.marketplace.market.CustomKits;
import com.chr0m4ik.marketplace.market.VirtualEnderChest;
import com.chr0m4ik.marketplace.market.MarketOrder;
import com.chr0m4ik.marketplace.market.MarketService;
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.market.KitCatalog;
import com.chr0m4ik.marketplace.sound.MarketSounds;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class MarketplaceMenu extends AbstractContainerMenu {

	private static final int MONEY_CELLS = 4;

	private static final int BALANCE_DATA = 0;
	private static final int ORDERS_DATA = BALANCE_DATA + MONEY_CELLS;
	private static final int STAGED_DATA = ORDERS_DATA + MONEY_CELLS;

	private static final int ORDERS_COUNT_DATA = STAGED_DATA + MONEY_CELLS;
	private static final int STAGED_COUNT_DATA = ORDERS_COUNT_DATA + 1;
	private static final int MARKUP_DATA = STAGED_COUNT_DATA + 1;
	private static final int UNLOCKED_DATA = MARKUP_DATA + 1;

	private static final int TARGET_DATA = UNLOCKED_DATA + 1;

	private static final int HEADER_SIZE = TARGET_DATA + 1;

	private static final int CELL_MARKUP_START = HEADER_SIZE + MarketService.SLOTS;

	private static final int CELL_PAYOUT_START = CELL_MARKUP_START + MarketService.SLOTS;

	private static final int DATA_SIZE = CELL_PAYOUT_START + MarketService.SLOTS * MONEY_CELLS;

	private static final int CELL_DATA_START = HEADER_SIZE;

	private static final int CELL_ORDER = 1;

	private static final int CELL_ORDER_WAITING = 2;

	private static final int CELL_FREE = 0;

	private static final int PAGE_DATA = HEADER_SIZE;

	private static final int SHOP_COUNT_DATA = PAGE_DATA + 3;

	private static final int URGENT_ACTIVE_DATA = HEADER_SIZE + 20;
	private static final int URGENT_SECONDS_DATA = URGENT_ACTIVE_DATA + 1;

	public static final int MAIN_SLOT_TRADER = 10;
	public static final int MAIN_SLOT_SELL = 13;
	public static final int MAIN_SLOT_BUY = 16;

	public static final int TRADER_SLOT_CANCEL = 45;
	public static final int TRADER_SLOT_INFO = 47;
	public static final int TRADER_SLOT_TRADE = 49;

	public static final int TRADER_SLOT_QUESTS = 51;

	public static final int[] QUEST_SLOTS = {10, 11, 12, 13, 14, 15, 16};
	public static final int QUESTS_SLOT_BACK = 18;
	public static final int QUESTS_SLOT_URGENT = 22;

	public static final int ENDER_SLOT_CANCEL = 10;
	public static final int ENDER_SLOT_INFO = 13;
	public static final int ENDER_SLOT_CONFIRM = 16;

	public static final int MARKET_SLOT_EXIT = 45;
	public static final int MARKET_SLOT_BUY = 47;
	public static final int MARKET_SLOT_CHECKOUT = 49;
	public static final int MARKET_SLOT_INFO = 51;
	public static final int MARKET_SLOT_TARGET = 53;

	public static final int CONFIRM_SLOT_EXIT = 10;
	public static final int CONFIRM_SLOT_PRICE = 13;
	public static final int CONFIRM_SLOT_CONFIRM = 16;

	public static final int SLOT_WINDOW_CANCEL = 10;
	public static final int SLOT_WINDOW_INFO = 13;
	public static final int SLOT_WINDOW_CONFIRM = 16;

	public static final int BUY_SLOT_EXIT = 49;
	public static final int BUY_GRID_ROWS = 3;
	public static final int BUY_GRID_COLUMNS = 7;

	public static final int BUY_LIST_EXIT = 45;
	public static final int BUY_LIST_PREV = 47;
	public static final int BUY_LIST_NEXT = 51;

	public static final int BUY_ITEM_BACK = 9;
	public static final int BUY_ITEM_QUANTITY = 11;
	public static final int BUY_ITEM_PREVIEW = 13;
	public static final int BUY_ITEM_DROP = 15;
	public static final int BUY_ITEM_CONFIRM = 17;

	public static final int BACK_SLOT = 49;

	private final WindowKind kind;

	private final SimpleContainer buttonContainer;

	private final SimpleContainer tradeContainer;
	private final ContainerData data;

	private final ServerPlayer serverPlayer;

	private boolean targetMode;

	private final Set<Integer> orderCells = new HashSet<>();

	private int[] shopCounts;

	private String loadedShopPage;

	public MarketplaceMenu(WindowKind kind, int syncId, Inventory inventory) {
		super(MarketplaceMenus.typeOf(kind), syncId);
		this.kind = kind;

		this.buttonContainer = new SimpleContainer(Math.max(1, kind.buttonSlots()));
		this.tradeContainer = new SimpleContainer(Math.max(1, kind.tradeSlots()));
		this.serverPlayer = inventory.player instanceof ServerPlayer player ? player : null;

		int total = kind.containerSlots();
		int tradeSlots = kind.tradeSlots();

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

		this.addStandardInventorySlots(inventory, 8, kind.inventoryY());

		this.data = new SimpleContainerData(DATA_SIZE);
		this.addDataSlots(this.data);

		this.updateData();
		this.fillButtons();
		this.loadMarketCells();
		this.loadShopPage();
	}

	private void loadShopPage() {
		if (this.serverPlayer == null || this.kind != WindowKind.BUY_LIST) {
			return;
		}

		prepareShopCatalog();

		MarketService.ShopState shop = MarketService.shop(this.serverPlayer.getUUID());
		ShopCatalog.Category category = shopCategory(shop);
		List<ItemStack> page = ShopCatalog.page(category, shop.page());

		this.loadedShopPage = shopPageKey(category, shop.page());

		for (int i = 0; i < this.kind.tradeSlots(); i++) {
			this.tradeContainer.setItem(i, i < page.size() ? page.get(i).copy() : ItemStack.EMPTY);
		}
	}

	private void prepareShopCatalog() {
		MinecraftServer server = this.serverPlayer.level().getServer();

		if (server == null) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(server);
		ShopCatalog.setCustomKits(CustomKits.all(state));

		List<ItemStack> lots = new ArrayList<>();

		for (MarketService.Listing listing : MarketService.allListings(state)) {
			lots.add(MarketService.listingIcon(listing.order(), listing.sellerId(), listing.slot()));
		}

		ShopCatalog.setAuctionIcons(lots);
		ShopCatalog.refresh(server.registryAccess());
	}

	private static ShopCatalog.Category shopCategory(MarketService.ShopState shop) {
		ShopCatalog.Category[] categories = ShopCatalog.Category.values();
		return categories[Math.max(0, Math.min(shop.category(), categories.length - 1))];
	}

	private static String shopPageKey(ShopCatalog.Category category, int page) {
		return category.name() + "/" + page;
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

			this.tradeContainer.setItem(i, order != null ? order.stack().copy()
					: (staged == null ? ItemStack.EMPTY : staged.copy()));
		}
	}

	private static ItemStack lockedPane() {

		ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.RED));
		pane.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.slot.locked")));
		return pane;
	}

	public boolean isCellUnlocked(int cell) {
		return cell < this.data.get(UNLOCKED_DATA);
	}

	public boolean isTargetMode() {
		return this.serverPlayer != null ? this.targetMode : this.data.get(TARGET_DATA) == 1;
	}

	private boolean hasMarketCells() {
		return this.kind == WindowKind.SELL;
	}

	public boolean isCellOrder(int cell) {
		if (cell < 0 || cell >= MarketService.SLOTS) {
			return false;
		}

		if (this.serverPlayer != null) {
			MinecraftServer server = this.serverPlayer.level().getServer();
			return server != null
					&& MarketplaceEconomy.state(server).order(this.serverPlayer.getUUID(), cell) != null;
		}

		int cellData = this.data.get(CELL_DATA_START + cell);
		return cellData == CELL_ORDER || cellData == CELL_ORDER_WAITING;
	}

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

	private void takeMarketCell(Player player, int cell) {
		if (this.serverPlayer == null || cell < 0 || cell >= this.kind.tradeSlots()
				|| !this.isCellUnlocked(cell)) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(this.serverPlayer.level().getServer());
		UUID playerId = this.serverPlayer.getUUID();
		MarketOrder order = state.order(playerId, cell);

		if (order == null && this.orderCells.contains(cell)) {

			this.tradeContainer.setItem(cell, ItemStack.EMPTY);
			this.orderCells.remove(cell);
			this.updateData();
			this.broadcastChanges();
			return;
		}

		ItemStack stack;

		if (order != null) {
			if (!this.targetMode) {

				player.sendSystemMessage(Component.translatable("marketplace.gui.market.needTarget")
						.withStyle(ChatFormatting.RED));
				return;
			}

			state.removeOrder(playerId, cell);

			stack = order.stack().copy();
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
				expected = state.order(playerId, i).stack().copy();

				ItemStack current = this.tradeContainer.getItem(i);

				if (!current.isEmpty() && !ItemStack.matches(current, expected)) {
					this.tradeContainer.setItem(i, ItemStack.EMPTY);
					this.serverPlayer.getInventory().placeItemBackInInventory(current, Prediction.SERVER_ONLY);
				}

				this.orderCells.add(i);
			} else if (this.orderCells.remove(i)) {

				// иначе предмет можно было бы утащить второй раз.
				expected = ItemStack.EMPTY;
			}

			if (expected != null && !ItemStack.matches(this.tradeContainer.getItem(i), expected)) {
				this.tradeContainer.setItem(i, expected);
			}
		}
	}

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
			this.setButton(TRADER_SLOT_QUESTS, Items.BOOK, "marketplace.gui.button.quests");
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

			ShopCatalog.Category[] categories = ShopCatalog.Category.values();
			int index = 0;

			for (int row = 1; row <= BUY_GRID_ROWS && index < categories.length; row++) {
				for (int col = 1; col <= BUY_GRID_COLUMNS && index < categories.length; col++) {
					ItemStack button = new ItemStack(categories[index].icon());
					button.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.category",
							Component.translatable(categories[index].titleKey()))));

					button.set(DataComponents.LORE, new ItemLore(List.of(
							Component.translatable("marketplace.gui.category.count",
									Component.literal(String.valueOf(this.data.get(SHOP_COUNT_DATA + index))))
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

		if (this.kind == WindowKind.ENDER_CHEST) {
			this.setButton(ENDER_SLOT_CANCEL, Items.BARRIER, "marketplace.gui.button.cancel");
			this.setButton(ENDER_SLOT_INFO, Items.PAPER, "marketplace.gui.button.enderInfo");

			ItemStack buy = new ItemStack(Items.EMERALD);
			buy.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.button.enderBuy",
					money(VirtualEnderChest.PRICE))));
			buy.set(DataComponents.LORE, new ItemLore(List.of(
					plain(Component.translatable("marketplace.gui.ender.hint").withStyle(ChatFormatting.GRAY)))));
			this.setButtonStack(ENDER_SLOT_CONFIRM, buy);
			return;
		}

		if (this.kind == WindowKind.QUESTS) {
			this.fillQuests();
			this.setButton(QUESTS_SLOT_BACK, Items.BARRIER, "marketplace.gui.button.back");
			this.updateUrgentButton();
			return;
		}

		this.setButton(BACK_SLOT, Items.ARROW, "marketplace.gui.button.back");
	}

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

	private void setButtonStack(int containerSlot, ItemStack stack) {
		this.buttonContainer.setItem(containerSlot - this.kind.tradeSlots(), stack);
	}

	private void updatePriceButton() {
		ItemStack button = new ItemStack(Items.GOLD_INGOT);
		button.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.button.price")));
		List<Component> lore = new ArrayList<>();
		double current = this.markupValue();

		for (double markup : MarketService.markups()) {
			MutableComponent line = Component.translatable("marketplace.gui.button.price.line", text(markup));
			lore.add(Math.abs(markup - current) < 0.001 ? line.withStyle(ChatFormatting.GREEN) : line);
		}

		button.set(DataComponents.LORE, new ItemLore(lore));
		this.buttonContainer.setItem(CONFIRM_SLOT_PRICE - this.kind.tradeSlots(), button);
	}

	private void updateTargetButton() {
		ItemStack button = new ItemStack(Items.TARGET);
		button.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.button.target",
				Component.translatable(this.targetMode
						? "marketplace.gui.button.target.on" : "marketplace.gui.button.target.off"))));
		button.set(DataComponents.LORE, new ItemLore(List.of(
				Component.translatable("marketplace.gui.button.target.hint").withStyle(ChatFormatting.GRAY))));

		if (this.targetMode) {

			button.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}

		this.buttonContainer.setItem(MARKET_SLOT_TARGET - this.kind.tradeSlots(), button);
	}

	private static String text(double markup) {
		return markup == Math.floor(markup) ? String.valueOf((int) markup) : String.valueOf(markup);
	}

	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	private void setButton(int containerSlot, Item item, String nameKey) {
		ItemStack stack = new ItemStack(item);

		stack.set(DataComponents.CUSTOM_NAME, plain(Component.translatable(nameKey)));
		this.buttonContainer.setItem(containerSlot - this.kind.tradeSlots(), stack);
	}

	private double markupValue() {
		return this.data.get(MARKUP_DATA) / 100.0;
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		if (this.isButtonSlot(slotId)) {
			if (input == ContainerInput.PICKUP && button == 0) {
				this.onButtonClick(slotId, player);
			}

			return;
		}

		if (this.kind == WindowKind.BUY_LIST && input == ContainerInput.PICKUP && button == 0
				&& slotId >= 0 && slotId < this.kind.tradeSlots()) {
			this.selectShopItem(player, slotId);
			return;
		}

		if (this.hasMarketCells() && slotId >= 0 && slotId < this.kind.tradeSlots()) {
			boolean order = this.isCellOrder(slotId);

			boolean shiftClick = input == ContainerInput.QUICK_MOVE
					|| (input == ContainerInput.PICKUP && player.isShiftKeyDown());

			if (shiftClick) {

				// проверка режима внутри takeMarketCell.
				this.takeMarketCell(player, slotId);
				return;
			}

			if (order) {

				return;
			}
		}

		super.clicked(slotId, button, input, player);
	}

	public boolean isButtonSlot(int slotIndex) {
		return slotIndex >= this.kind.tradeSlots() && slotIndex < this.kind.containerSlots();
	}

	private void onButtonClick(int slotId, Player player) {

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

				MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
			} else if (slotId == TRADER_SLOT_TRADE) {
				this.tradeToTrader(serverPlayer);
			} else if (slotId == TRADER_SLOT_QUESTS) {

				MarketplaceMenus.open(serverPlayer, WindowKind.QUESTS);
			}

			return;
		}

		if (this.kind == WindowKind.SELL) {
			if (slotId == MARKET_SLOT_EXIT) {

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

				MarketSounds.buttonClick(serverPlayer);
				this.targetMode = !this.targetMode;
				this.updateTargetButton();
				this.updateData();
				this.broadcastChanges();
			}

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

				MarketService.setShop(serverPlayer.getUUID(), shop.withQuantity(1).withDrop(false));
				MarketplaceMenus.open(serverPlayer, WindowKind.BUY_LIST);
			} else if (slotId == BUY_ITEM_QUANTITY) {
				MarketSounds.buttonClick(serverPlayer);
				MarketService.setShop(serverPlayer.getUUID(),
						shop.withQuantity(MarketService.nextQuantity(shop)));
				this.updateShopButtons();
				this.broadcastChanges();
			} else if (slotId == BUY_ITEM_DROP) {
				MarketSounds.buttonClick(serverPlayer);
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

		if (this.kind == WindowKind.ENDER_CHEST) {
			if (slotId == ENDER_SLOT_CANCEL) {

				serverPlayer.closeContainer();
			} else if (slotId == ENDER_SLOT_CONFIRM) {
				this.buyEnderChest(serverPlayer);
			}

			return;
		}

		if (this.kind == WindowKind.QUESTS) {
			if (slotId == QUESTS_SLOT_BACK) {
				MarketplaceMenus.open(serverPlayer, WindowKind.TRADER);
			} else if (slotId == QUESTS_SLOT_URGENT) {
				this.deliverUrgent(serverPlayer);
			} else {
				for (int index = 0; index < QUEST_SLOTS.length; index++) {
					if (QUEST_SLOTS[index] == slotId) {
						this.deliverQuest(serverPlayer, index);
						break;
					}
				}
			}

			return;
		}

		if (slotId == BACK_SLOT) {
			MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
		}
	}

	private void cycleMarkup(ServerPlayer player) {

		MarketSounds.buttonClick(player);
		MarketplaceState state = MarketplaceEconomy.state(player.level().getServer());
		state.setMarkup(player.getUUID(), MarketService.nextMarkup(state.markup(player.getUUID())));

		this.updateData();
		this.updatePriceButton();
		this.broadcastChanges();
	}

	private void confirmOrders(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		MarketplaceState state = MarketplaceEconomy.state(server);
		UUID playerId = player.getUUID();
		Map<Integer, ItemStack> staged = state.staging(playerId);

		if (staged.isEmpty()) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.market.nothing")
					.withStyle(ChatFormatting.RED));
			return;
		}

		long now = GameTime.now(server);
		double markup = state.markup(playerId);
		int created = 0;
		List<ItemStack> leftovers = new ArrayList<>();

		for (Map.Entry<Integer, ItemStack> entry : Map.copyOf(staged).entrySet()) {
			if (MarketService.placeOrder(state, playerId, entry.getKey(), entry.getValue(), markup, now,
					server.overworld().getRandom(), player.getScoreboardName()) != null) {
				created++;
			} else {

				leftovers.add(entry.getValue().copy());
			}
		}

		state.setStaging(playerId, Map.of());
		// Наценку сбрасываем: по умолчанию всегда x1.
		state.setMarkup(playerId, 1.0);
		MarketplaceMenus.open(player, WindowKind.SELL);

		for (ItemStack stack : leftovers) {
			player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
			player.sendSystemMessage(Component.translatable("marketplace.gui.market.leftover",
					stack.getHoverName()).withStyle(ChatFormatting.RED));
		}

		if (created > 0) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.market.listed",
					Component.literal(String.valueOf(created))).withStyle(ChatFormatting.GREEN));
			MarketSounds.ordersPlaced(player);
		}
	}

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
			MarketSounds.marketSlotBought(player);

			this.updateData();
			this.broadcastChanges();
		} else {
			player.sendSystemMessage(Component.translatable("marketplace.gui.slot.noMoney", money(price))
					.withStyle(ChatFormatting.RED));
		}
	}

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
		MarketSounds.traderSold(player);

		if (sale.limitReached()) {
			sendLimitMessage(player);
		}
	}

	private void fillQuests() {
		if (this.serverPlayer == null) {
			return;
		}

		MinecraftServer server = this.serverPlayer.level().getServer();

		if (server == null) {
			return;
		}

		List<TraderQuest> quests = TraderQuests.forPlayer(server, this.serverPlayer.getUUID());

		for (int index = 0; index < QUEST_SLOTS.length; index++) {
			ItemStack icon = index < quests.size() ? questIcon(quests.get(index)) : ItemStack.EMPTY;
			this.setButtonStack(QUEST_SLOTS[index], icon);
		}
	}

	private static ItemStack questIcon(TraderQuest quest) {
		ItemStack icon = quest.icon();

		if (icon.isEmpty()) {
			return ItemStack.EMPTY;
		}

		List<Component> lore = new ArrayList<>();
		lore.add(plain(Component.translatable("marketplace.gui.quests.need",
				Component.literal(String.valueOf(quest.count()))).withStyle(ChatFormatting.GRAY)));

		if (quest.complete()) {
			lore.add(plain(Component.translatable("marketplace.gui.quests.done")
					.withStyle(ChatFormatting.GREEN)));

			icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		} else {
			if (quest.delivered() > 0) {

				lore.add(plain(Component.translatable("marketplace.gui.quests.progress",
						Component.literal(String.valueOf(quest.delivered())),
						Component.literal(String.valueOf(quest.count()))).withStyle(ChatFormatting.YELLOW)));
				lore.add(plain(Component.translatable("marketplace.gui.quests.risk")
						.withStyle(ChatFormatting.DARK_RED)));
			}

			lore.add(plain(Component.translatable("marketplace.gui.quests.reward",
					money(TraderQuests.rewardOf(quest))).withStyle(ChatFormatting.GOLD)));
			lore.add(plain(Component.translatable("marketplace.gui.quests.hint")
					.withStyle(ChatFormatting.DARK_GRAY)));
		}

		icon.set(DataComponents.LORE, new ItemLore(lore));
		return icon;
	}

	private void deliverQuest(ServerPlayer player, int index) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		TraderQuests.Delivery delivery = TraderQuests.deliver(server, player, index);
		TraderQuest quest = delivery.quest();

		switch (delivery.status()) {
			case DELIVERED -> {
				MarketSounds.traderSold(player);
				player.sendSystemMessage(Component.translatable("marketplace.gui.quests.delivered",
						quest.icon().getHoverName(),
						Component.literal(String.valueOf(quest.count())),
						money(delivery.paid())).withStyle(ChatFormatting.GREEN));
			}
			case PARTIAL -> {

				player.sendSystemMessage(Component.translatable("marketplace.gui.quests.partial",
						quest.icon().getHoverName(),
						Component.literal(String.valueOf(delivery.taken())),
						Component.literal(String.valueOf(delivery.missing()))).withStyle(ChatFormatting.YELLOW));
				player.sendSystemMessage(Component.translatable("marketplace.gui.quests.partialHint",
						money(TraderQuests.rewardOf(quest))).withStyle(ChatFormatting.GRAY));
			}
			case NOT_ENOUGH -> player.sendSystemMessage(Component.translatable("marketplace.gui.quests.notEnough",
					quest.icon().getHoverName(),
					Component.literal(String.valueOf(delivery.missing()))).withStyle(ChatFormatting.RED));
			case ALREADY_DONE -> player.sendSystemMessage(Component.translatable("marketplace.gui.quests.already")
					.withStyle(ChatFormatting.GRAY));
			case BROKEN -> player.sendSystemMessage(Component.translatable("marketplace.gui.quests.empty")
					.withStyle(ChatFormatting.GRAY));
		}

		this.fillQuests();
		this.broadcastChanges();
	}

	private void updateUrgentButton() {
		if (this.serverPlayer == null || this.kind != WindowKind.QUESTS) {
			return;
		}

		MinecraftServer server = this.serverPlayer.level().getServer();

		if (server == null) {
			return;
		}

		UrgentQuest quest = MarketplaceEconomy.state(server).urgentQuest();

		if (quest == null) {
			this.setButtonStack(QUESTS_SLOT_URGENT, ItemStack.EMPTY);
			return;
		}

		ItemStack button = new ItemStack(Items.EMERALD);
		button.set(DataComponents.CUSTOM_NAME, plain(Component.translatable("marketplace.gui.quests.urgent")));

		button.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		button.set(DataComponents.LORE, new ItemLore(List.of(
				plain(Component.translatable("marketplace.gui.quests.urgent.line",
						quest.icon().getHoverName(),
						Component.literal(String.valueOf(quest.count()))).withStyle(ChatFormatting.YELLOW)),
				plain(Component.translatable("marketplace.gui.quests.urgent.reward",
						money(MoneyFormat.round(quest.reward()))).withStyle(ChatFormatting.GOLD)),
				plain(Component.translatable("marketplace.gui.quests.urgent.left",
						MarketEvents.duration(quest.remaining())).withStyle(ChatFormatting.GRAY)),
				plain(Component.translatable("marketplace.gui.quests.urgent.hint")
						.withStyle(ChatFormatting.DARK_GRAY)))));
		this.setButtonStack(QUESTS_SLOT_URGENT, button);
	}

	private void buyEnderChest(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		VirtualEnderChest.BuyResult result = VirtualEnderChest.buy(server, player);

		if (!result.bought()) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.ender.noMoney",
					money(VirtualEnderChest.PRICE)).withStyle(ChatFormatting.RED));
			return;
		}

		if (result.paid() > 0.0) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.ender.bought",
					money(result.paid())).withStyle(ChatFormatting.GREEN));
		} else {

			player.sendSystemMessage(Component.translatable("marketplace.gui.ender.boughtFree")
					.withStyle(ChatFormatting.GREEN));
		}

		VirtualEnderChest.open(player);
	}

	private void deliverUrgent(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		UrgentQuests.Delivery delivery = UrgentQuests.deliver(server, player);
		UrgentQuest quest = delivery.quest();

		switch (delivery.status()) {
			case DELIVERED -> MarketSounds.traderSold(player);
			case NOT_ENOUGH -> player.sendSystemMessage(Component.translatable("marketplace.urgent.notEnough",
					quest.icon().getHoverName(),
					Component.literal(String.valueOf(delivery.missing()))).withStyle(ChatFormatting.RED));
			case NO_QUEST -> player.sendSystemMessage(Component.translatable("marketplace.urgent.none")
					.withStyle(ChatFormatting.GRAY));
			case BROKEN -> player.sendSystemMessage(Component.translatable("marketplace.urgent.broken")
					.withStyle(ChatFormatting.RED));
		}

		this.fillQuests();
		this.updateUrgentButton();
		this.broadcastChanges();
	}

	private void selectShopItem(Player player, int cell) {		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		ItemStack stack = this.tradeContainer.getItem(cell);

		if (stack.isEmpty()) {
			return;
		}

		MarketService.setShop(serverPlayer.getUUID(), MarketService.shop(serverPlayer.getUUID()).withSelected(stack.copy()));
		MarketplaceMenus.open(serverPlayer, WindowKind.BUY_ITEM);
	}

	private int[] shopCounts() {
		if (this.shopCounts == null) {

			prepareShopCatalog();
			ShopCatalog.Category[] categories = ShopCatalog.Category.values();
			int[] counts = new int[categories.length];

			for (int index = 0; index < categories.length; index++) {
				counts[index] = ShopCatalog.items(categories[index]).size();
			}

			this.shopCounts = counts;
		}

		return this.shopCounts;
	}

	private void turnShopPage(ServerPlayer player, int delta) {
		MarketService.ShopState shop = MarketService.shop(player.getUUID());
		ShopCatalog.Category category = shopCategory(shop);
		int pages = ShopCatalog.pages(category);
		int page = Math.floorMod(shop.page() + delta, pages);
		MarketService.setShop(player.getUUID(), shop.withPage(page));
		this.loadShopPage();
		this.updateData();
		this.broadcastChanges();
	}

	private void buyShopItem(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		MarketService.ShopState shop = MarketService.shop(player.getUUID());
		MarketplaceState world = MarketplaceEconomy.state(server);

		Optional<MarketService.Listing> listing = MarketService.listing(world, shop.selected());

		if (listing.isPresent()) {
			MarketService.BuyResult result = MarketService.buyListing(server, player, shop.selected(),
					shop.drop());
			double price = result.order() == null ? 0.0 : result.order().payout();

			switch (result.status()) {
				case BOUGHT -> {
					player.sendSystemMessage(Component.translatable("marketplace.gui.auction.bought",
							shop.selected().getHoverName(),
							money(player.isCreative() ? 0.0 : result.paid())).withStyle(ChatFormatting.GREEN));
					MarketSounds.shopBought(player, false);
				}
				case MISSING -> player.sendSystemMessage(Component.translatable("marketplace.gui.auction.missing")
						.withStyle(ChatFormatting.RED));
				case OWN_LOT -> player.sendSystemMessage(Component.translatable("marketplace.gui.auction.own")
						.withStyle(ChatFormatting.RED));
				case NO_MONEY -> player.sendSystemMessage(Component.translatable("marketplace.gui.buy.noMoney",
						money(price)).withStyle(ChatFormatting.RED));
			}

			this.updateData();
			this.broadcastChanges();
			return;
		}

		if (UrgentQuests.isShoppingBlocked(server, shop.selected().getItem())) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.quests.blocked")
					.withStyle(ChatFormatting.RED));
			return;
		}

		double cost = MarketService.costOf(MarketplaceEconomy.state(server), shop);

		if (MarketService.buy(server, player, shop)) {
			player.sendSystemMessage(Component.translatable("marketplace.gui.buy.bought",
					Component.literal(String.valueOf(shop.count())), shop.selected().getHoverName(),
					money(player.isCreative() ? 0.0 : cost)).withStyle(ChatFormatting.GREEN));

			MarketSounds.shopBought(player, KitCatalog.kitOf(shop.selected()).isPresent());
		} else {
			player.sendSystemMessage(Component.translatable("marketplace.gui.buy.noMoney", money(cost))
					.withStyle(ChatFormatting.RED));
		}

		this.updateData();
		this.broadcastChanges();
	}

	private static void sendLimitMessage(ServerPlayer player) {
		player.sendSystemMessage(Component.translatable("marketplace.gui.trader.limit")
				.withStyle(ChatFormatting.RED));
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		int tradeSlots = this.kind.tradeSlots();
		int containerSlots = this.kind.containerSlots();

		if (tradeSlots == 0 || slotIndex < 0 || slotIndex >= this.slots.size()) {
			return ItemStack.EMPTY;
		}

		Slot slot = this.slots.get(slotIndex);

		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack moved = stack.copy();

		if (this.kind == WindowKind.SELL && this.hasMarketCells()
				&& (this.orderCells.contains(slotIndex) || this.isCellOrder(slotIndex))) {
			return ItemStack.EMPTY;
		}

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

		return true;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);

		if (this.serverPlayer == null) {
			return;
		}

		if (this.kind == WindowKind.SELL) {

			boolean transition = MarketService.isWindowTransition();
			MarketService.setWindowTransition(false);

			if (!transition) {
				for (int i = 0; i < this.kind.tradeSlots(); i++) {

					if (i >= this.getUnlockedSlots() || this.orderCells.contains(i) || this.isCellOrder(i)) {
						this.tradeContainer.setItem(i, ItemStack.EMPTY);
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

		if (this.kind != WindowKind.TRADER) {
			return;
		}

		for (int i = 0; i < this.kind.tradeSlots(); i++) {
			ItemStack stack = this.tradeContainer.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			this.tradeContainer.setItem(i, ItemStack.EMPTY);

			player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
		}
	}

	@Override
	public void broadcastChanges() {
		this.updateData();
		this.ensureShopPage();
		this.updateUrgentButton();
		this.saveMarketCells();
		this.syncMarketCells();
		super.broadcastChanges();
	}

	private void ensureShopPage() {
		if (this.serverPlayer == null || this.kind != WindowKind.BUY_LIST) {
			return;
		}

		MarketService.ShopState shop = MarketService.shop(this.serverPlayer.getUUID());
		String expected = shopPageKey(shopCategory(shop), shop.page());

		if (!expected.equals(this.loadedShopPage)) {
			this.loadShopPage();
		}
	}

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

		KitCatalog.setRegistries(server.registryAccess());
		this.data.set(MARKUP_DATA, (int) Math.round(state.markup(playerId) * 100.0));
		this.data.set(UNLOCKED_DATA, MarketService.unlockedSlots(state, playerId));

		this.data.set(TARGET_DATA, this.targetMode ? 1 : 0);

		if (this.kind == WindowKind.BUY) {

			int[] counts = this.shopCounts();

			for (int index = 0; index < counts.length; index++) {
				this.data.set(SHOP_COUNT_DATA + index, counts[index]);
			}

			return;
		}

		if (this.kind == WindowKind.BUY_ITEM) {

			this.data.set(PAGE_DATA, MarketService.shop(playerId).quantityIndex());
			return;
		}

		if (this.kind == WindowKind.BUY_LIST) {

			MarketService.ShopState shop = MarketService.shop(playerId);
			ShopCatalog.Category[] categories = ShopCatalog.Category.values();
			ShopCatalog.Category category = categories[Math.max(0, Math.min(shop.category(), categories.length - 1))];
			this.data.set(PAGE_DATA, shop.page() + 1);
			this.data.set(PAGE_DATA + 1, ShopCatalog.pages(category));
			this.data.set(PAGE_DATA + 2, shop.category());
			return;
		}

		if (this.kind == WindowKind.QUESTS) {

			UrgentQuest urgent = state.urgentQuest();
			this.data.set(URGENT_ACTIVE_DATA, urgent != null ? 1 : 0);
			this.data.set(URGENT_SECONDS_DATA,
					urgent == null ? 0 : (int) Math.min(Integer.MAX_VALUE, urgent.remaining() / 20L));
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

		for (int i = 0; i < MarketService.SLOTS; i++) {
			MarketOrder order = state.order(playerId, i);
			ItemStack staged = state.staging(playerId).get(i);
			this.data.set(CELL_DATA_START + i, order == null ? CELL_FREE
					: (order.waitingForBuyer() ? CELL_ORDER_WAITING : CELL_ORDER));
			this.data.set(CELL_MARKUP_START + i,
					order != null ? (int) Math.round(order.markup() * 100.0)
							: (staged == null ? 0 : (int) Math.round(state.markup(playerId) * 100.0)));

			setMoney(CELL_PAYOUT_START + i * MONEY_CELLS, order != null ? order.payout() : 0.0);
		}
	}

	public boolean isCellWaiting(int cell) {
		if (cell < 0 || cell >= MarketService.SLOTS) {
			return false;
		}

		if (this.serverPlayer != null) {
			MinecraftServer server = this.serverPlayer.level().getServer();

			if (server == null) {
				return false;
			}

			MarketOrder order = MarketplaceEconomy.state(server).order(this.serverPlayer.getUUID(), cell);
			return order != null && order.waitingForBuyer();
		}

		return this.data.get(CELL_DATA_START + cell) == CELL_ORDER_WAITING;
	}

	public double getCellMarkup(int cell) {
		if (cell < 0 || cell >= MarketService.SLOTS) {
			return 0.0;
		}

		return this.data.get(CELL_MARKUP_START + cell) / 100.0;
	}

	public double getCellPayout(int cell) {
		if (cell < 0 || cell >= MarketService.SLOTS) {
			return 0.0;
		}

		return money(CELL_PAYOUT_START + cell * MONEY_CELLS);
	}

	private void setMoney(int index, double amount) {
		long cents = Math.round(amount * 100.0);

		for (int i = 0; i < MONEY_CELLS; i++) {
			this.data.set(index + i, (int) ((cents >>> (16 * i)) & 0xFFFFL));
		}
	}

	private double money(int index) {
		long cents = 0L;

		for (int i = 0; i < MONEY_CELLS; i++) {
			cents |= (long) (this.data.get(index + i) & 0xFFFF) << (16 * i);
		}

		return cents / 100.0;
	}

	public int getShopPage() {
		return this.data.get(PAGE_DATA);
	}

	public int getShopQuantityIndex() {
		return this.data.get(PAGE_DATA);
	}

	public int getShopPages() {
		return Math.max(1, this.data.get(PAGE_DATA + 1));
	}

	public double getBalance() {
		return money(BALANCE_DATA);
	}

	public double getOrdersPayout() {
		return money(ORDERS_DATA);
	}

	public double getStagedPayout() {
		return money(STAGED_DATA);
	}

	public int getOrdersCount() {
		return this.data.get(ORDERS_COUNT_DATA);
	}

	public int getStagedCount() {
		return this.data.get(STAGED_COUNT_DATA);
	}

	public double getMarkup() {
		return this.data.get(MARKUP_DATA) / 100.0;
	}

	public int getUnlockedSlots() {
		return this.data.get(UNLOCKED_DATA);
	}

	public double getNextSlotPrice() {
		return MarketService.slotPriceFor(this.getUnlockedSlots());
	}

	public WindowKind getKind() {
		return this.kind;
	}

	public int tradeSlotCount() {
		return this.kind.tradeSlots();
	}

	public Container getTradeContainer() {
		return this.tradeContainer;
	}

	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount)).withStyle(ChatFormatting.GREEN);
	}
}
