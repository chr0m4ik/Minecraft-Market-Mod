package com.chr0m4ik.marketplace.menu;

import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
	private static final int DATA_SIZE = 2;

	/** Слоты кнопок главного меню (средний ряд: 10, 13, 16). */
	public static final int MAIN_SLOT_TRADER = 10;
	public static final int MAIN_SLOT_SELL = 13;
	public static final int MAIN_SLOT_BUY = 16;

	/** Кнопки в системной строке окна торговца. */
	public static final int TRADER_SLOT_CANCEL = 45;
	public static final int TRADER_SLOT_INFO = 47;
	public static final int TRADER_SLOT_TRADE = 49;

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

	public MarketplaceMenu(WindowKind kind, int syncId, Inventory inventory) {
		super(MarketplaceMenus.typeOf(kind), syncId);
		this.kind = kind;
		// Размер 0 у SimpleContainer допустим, но чтобы не думать об этом — минимум одна ячейка.
		this.buttonContainer = new SimpleContainer(Math.max(1, kind.buttonSlots()));
		this.tradeContainer = new SimpleContainer(Math.max(1, kind.tradeSlots()));
		this.serverPlayer = inventory.player instanceof ServerPlayer player ? player : null;

		int total = kind.containerSlots();
		int tradeSlots = kind.tradeSlots();

		// Сначала слоты под товар — они работают как обычные.
		for (int i = 0; i < tradeSlots; i++) {
			this.addSlot(new Slot(this.tradeContainer, i, slotX(i), slotY(i)));
		}

		// Потом слоты-кнопки.
		for (int i = tradeSlots; i < total; i++) {
			this.addSlot(new ActionSlot(this.buttonContainer, i - tradeSlots, slotX(i), slotY(i)));
		}

		// Нижняя часть окна: инвентарь игрока (3 ряда + хотбар).
		this.addStandardInventorySlots(inventory, 8, kind.inventoryY());

		this.data = new SimpleContainerData(DATA_SIZE);
		this.addDataSlots(this.data);

		this.fillButtons();
		this.updateBalance();
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

		this.setButton(BACK_SLOT, Items.ARROW, "marketplace.gui.button.back");
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

		if (slotId == BACK_SLOT) {
			MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
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
	 * Окно закрыли, а товар остался — возвращаем его игроку (или выбрасываем, если не влезает).
	 * Так предметы не пропадают, если игрок вышел из игры, не нажав «Торговать».
	 */
	@Override
	public void removed(Player player) {
		super.removed(player);

		// На клиенте ничего не делаем: там serverPlayer пустой, и предметы продублировались бы.
		if (this.serverPlayer == null) {
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
		this.updateBalance();
		super.broadcastChanges();
	}

	/** Серверная часть: кладём баланс в слоты данных, игра сама отправит их клиенту. */
	private void updateBalance() {
		if (this.serverPlayer == null) {
			return;
		}

		MinecraftServer server = this.serverPlayer.level().getServer();

		if (server == null) {
			return;
		}

		double balance = MarketplaceEconomy.getBalance(server, this.serverPlayer.getUUID());
		long cents = Math.round(balance * 100.0);
		this.data.set(0, (int) (cents >>> 32));
		this.data.set(1, (int) cents);
	}

	/** Баланс, который видит клиент (в долларах). */
	public double getBalance() {
		long cents = ((long) this.data.get(0) << 32) | (this.data.get(1) & 0xFFFFFFFFL);
		return cents / 100.0;
	}

	public WindowKind getKind() {
		return this.kind;
	}

	/** Сколько слотов отведено под товар (нужно клиенту для подсказок и подсчётов). */
	public int tradeSlotCount() {
		return this.kind.tradeSlots();
	}

	/** Что сейчас выставлено на продажу — для подсказки кнопки «итоговая цена». */
	public TraderService.Offer currentOffer() {
		return TraderService.evaluate(this.tradeContainer, this.kind.tradeSlots());
	}

	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount)).withStyle(ChatFormatting.GREEN);
	}
}
