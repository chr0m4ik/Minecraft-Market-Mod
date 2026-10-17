package com.chr0m4ik.marketplace.menu;

import net.minecraft.network.chat.Component;

/**
 * Какое окно открыто. От вида зависят размер окна, заголовок и раскладка слотов.
 *
 * <p>Размеры подобраны под ванильные текстуры (своих у мода нет):
 * <ul>
 *     <li>3 ряда слотов — текстура сундука {@code shulker_box.png} (176×167);</li>
 *     <li>6 рядов слотов — текстура двойного сундука {@code generic_54.png} (176×222).</li>
 * </ul>
 * Окна 9×5 + системная строка как раз дают 6 рядов, поэтому они выглядят как двойной сундук.
 *
 * <p>Слоты верхней части окна делятся на две части: сначала идут слоты для предметов
 * ({@link #tradeSlots()} — туда игрок кладёт товар), а последние {@link #buttonSlots()} слотов —
 * это кнопки-предметы.
 */
public enum WindowKind {
	/** Главное меню: 9×3, все 27 слотов — кнопки. */
	MAIN("marketplace.gui.main.title", 3, 84, 167, 0, false),
	/** Сдать торговцу: 45 слотов под товар + системная строка с кнопками. */
	TRADER("marketplace.gui.trader.title", 6, 139, 222, 45, false),
	/** Рынок: 45 ячеек под ордера + системная строка с кнопками. */
	SELL("marketplace.gui.sell.title", 6, 139, 222, 45, false),
	/** Подтверждение ордеров: 9×3 как у сундука, три кнопки по центру. */
	CONFIRM("marketplace.gui.confirm.title", 3, 84, 167, 0, false),
	/** Покупка слота: 9×3, одни кнопки. */
	BUY_SLOT("marketplace.gui.slot.title", 3, 84, 167, 0, false),
	/** Покупка: меню разделов магазина (двойной сундук, разделы прямоугольником по центру). */
	BUY("marketplace.gui.buy.title", 6, 139, 222, 0, false),
	/** Покупка: список товаров раздела (9×5 + системная строка). */
	BUY_LIST("marketplace.gui.buy.list.title", 6, 139, 222, 45, true),
	/** Покупка: окно выбора количества и подтверждения. */
	BUY_ITEM("marketplace.gui.buy.item.title", 3, 84, 167, 0, false),
	/** Задания торговца: до трёх заказов и кнопка «назад». */
	QUESTS("marketplace.gui.quests.title", 3, 84, 167, 0, false);

	/** Ширина всех ванильных окон-контейнеров. */
	public static final int IMAGE_WIDTH = 176;

	private final String titleKey;
	private final int rows;
	private final int inventoryY;
	private final int imageHeight;
	private final int tradeSlots;
	private final boolean readOnlySlots;

	WindowKind(String titleKey, int rows, int inventoryY, int imageHeight, int tradeSlots, boolean readOnlySlots) {
		this.titleKey = titleKey;
		this.rows = rows;
		this.inventoryY = inventoryY;
		this.imageHeight = imageHeight;
		this.tradeSlots = tradeSlots;
		this.readOnlySlots = readOnlySlots;
	}

	/** Можно ли брать и класть предметы в слоты верхней части окна. */
	public boolean readOnlySlots() {
		return this.readOnlySlots;
	}

	/** Заголовок окна. */
	public Component title() {
		return Component.translatable(this.titleKey);
	}

	/** Сколько всего слотов в верхней части окна. */
	public int containerSlots() {
		return this.rows * 9;
	}

	/** Сколько слотов отведено под предметы (товар, ордера и так далее). */
	public int tradeSlots() {
		return this.tradeSlots;
	}

	/** Сколько слотов-кнопок. Это последние слоты верхней части окна. */
	public int buttonSlots() {
		return containerSlots() - this.tradeSlots;
	}

	/** Сколько рядов занимает верхняя часть окна. */
	public int rows() {
		return this.rows;
	}

	/** Высота окна в пикселях. */
	public int imageHeight() {
		return this.imageHeight;
	}

	/** С какого Y начинается инвентарь игрока внутри окна. */
	public int inventoryY() {
		return this.inventoryY;
	}
}
