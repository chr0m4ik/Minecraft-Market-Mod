package com.chr0m4ik.marketplace.menu;

import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Окно рынка. Один класс на все окна мода — вид задаётся через {@link WindowKind}.
 *
 * <p>Как устроено:
 * <ul>
 *     <li>верхняя часть окна (3 или 6 рядов) — «ненастоящий» контейнер {@link SimpleContainer}.
 *     Предметы в нём — это кнопки: их нельзя взять или подменить, а клик по ним выполняет
 *     действие (см. {@link #clicked});</li>
 *     <li>нижняя часть — обычный инвентарь игрока, он работает как всегда;</li>
 *     <li>баланс показывается на клиенте через {@link ContainerData} — это штатный способ
 *     передавать числа из серверной части окна в клиентскую.</li>
 * </ul>
 */
public class MarketplaceMenu extends AbstractContainerMenu {
	/** Баланс передаём как одно long-число (копейки), а слоты данных — это int. Отсюда 2. */
	private static final int DATA_SIZE = 2;

	/** Слот кнопки «Назад» в системной строке окон 9×6 (последний ряд, середина). */
	public static final int BACK_SLOT = 49;

	/** Слоты кнопок главного меню (средний ряд: 10, 13, 16). */
	public static final int MAIN_SLOT_TRADER = 10;
	public static final int MAIN_SLOT_SELL = 13;
	public static final int MAIN_SLOT_BUY = 16;

	private final WindowKind kind;
	private final SimpleContainer container;
	private final ContainerData data;
	/** Ссылка на игрока есть только на сервере; на клиенте здесь null. */
	private final ServerPlayer serverPlayer;

	public MarketplaceMenu(WindowKind kind, int syncId, Inventory inventory) {
		super(MarketplaceMenus.typeOf(kind), syncId);
		this.kind = kind;
		this.container = new SimpleContainer(kind.containerSlots());
		this.serverPlayer = inventory.player instanceof ServerPlayer player ? player : null;

		// Верхняя часть окна: все слоты — кнопки.
		for (int i = 0; i < kind.containerSlots(); i++) {
			this.addSlot(new ActionSlot(this.container, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18));
		}

		// Нижняя часть окна: инвентарь игрока (3 ряда + хотбар).
		this.addStandardInventorySlots(inventory, 8, kind.inventoryY());

		this.data = new SimpleContainerData(DATA_SIZE);
		this.addDataSlots(this.data);

		this.fillButtons();
		this.updateBalance();
	}

	/** Раскладываем предметы-кнопки по своим слотам. */
	private void fillButtons() {
		if (this.kind == WindowKind.MAIN) {
			this.setButton(MAIN_SLOT_TRADER, Items.EMERALD, "marketplace.gui.button.trader");
			this.setButton(MAIN_SLOT_SELL, Items.CLOCK, "marketplace.gui.button.sell");
			this.setButton(MAIN_SLOT_BUY, Items.CHEST, "marketplace.gui.button.buy");
		} else {
			this.setButton(BACK_SLOT, Items.ARROW, "marketplace.gui.button.back");
		}
	}

	private void setButton(int slot, Item item, String nameKey) {
		ItemStack stack = new ItemStack(item);
		// Своё название предмета — игрок увидит его в подсказке при наведении.
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable(nameKey));
		this.container.setItem(slot, stack);
	}

	/**
	 * Клик по слоту. Слоты-кнопки перехватываем целиком: ни взять предмет, ни положить,
	 * ни выбросить — только выполнить действие.
	 */
	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		if (slotId >= 0 && slotId < this.kind.containerSlots()) {
			if (input == ContainerInput.PICKUP && button == 0) {
				this.onButtonClick(slotId, player);
			}

			return;
		}

		super.clicked(slotId, button, input, player);
	}

	/** Что делать при нажатии на кнопку. */
	private void onButtonClick(int slotId, Player player) {
		// Окна открывает только сервер: на клиенте serverPlayer пустой.
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
		} else if (slotId == BACK_SLOT) {
			MarketplaceMenus.open(serverPlayer, WindowKind.MAIN);
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		// Shift-клик пока ничего не делает: слоты-кнопки предметы не принимают,
		// а слоты продажи появятся на следующих этапах.
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		// Окно не привязано к блоку, поэтому всегда доступно.
		return true;
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
}
