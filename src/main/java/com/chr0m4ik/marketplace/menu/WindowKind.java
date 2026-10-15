package com.chr0m4ik.marketplace.menu;

import net.minecraft.network.chat.Component;

/**
 * Какое окно открыто. От вида зависят размер окна, заголовок и расположение кнопок.
 *
 * <p>Размеры подобраны под ванильные текстуры (своих у мода нет):
 * <ul>
 *     <li>3 ряда слотов — текстура сундука {@code shulker_box.png} (176×167);</li>
 *     <li>6 рядов слотов — текстура двойного сундука {@code generic_54.png} (176×222).</li>
 * </ul>
 * Окна 9×5 + системная строка как раз дают 6 рядов, поэтому они выглядят как двойной сундук.
 */
public enum WindowKind {
	/** Главное меню: 9×3. */
	MAIN("marketplace.gui.main.title", 3, 84, 167),
	/** Сдать торговцу: 9×5 предметов + системная строка. */
	TRADER("marketplace.gui.trader.title", 6, 139, 222),
	/** Продажа на рынке: 9×5 слотов-ордеров + системная строка. */
	SELL("marketplace.gui.sell.title", 6, 139, 222),
	/** Покупка: 9×5 предметов + системная строка. */
	BUY("marketplace.gui.buy.title", 6, 139, 222);

	/** Ширина всех ванильных окон-контейнеров. */
	public static final int IMAGE_WIDTH = 176;

	private final String titleKey;
	private final int rows;
	private final int inventoryY;
	private final int imageHeight;

	WindowKind(String titleKey, int rows, int inventoryY, int imageHeight) {
		this.titleKey = titleKey;
		this.rows = rows;
		this.inventoryY = inventoryY;
		this.imageHeight = imageHeight;
	}

	/** Заголовок окна. */
	public Component title() {
		return Component.translatable(this.titleKey);
	}

	/** Сколько слотов в верхней части окна (кнопки и будущие слоты продажи). */
	public int containerSlots() {
		return this.rows * 9;
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
