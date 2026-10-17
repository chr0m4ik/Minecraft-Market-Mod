package com.chr0m4ik.marketplace.menu;

import net.minecraft.network.chat.Component;

public enum WindowKind {

	MAIN("marketplace.gui.main.title", 3, 84, 167, 0, false),

	TRADER("marketplace.gui.trader.title", 6, 139, 222, 45, false),

	SELL("marketplace.gui.sell.title", 6, 139, 222, 45, false),

	CONFIRM("marketplace.gui.confirm.title", 3, 84, 167, 0, false),

	BUY_SLOT("marketplace.gui.slot.title", 3, 84, 167, 0, false),

	BUY("marketplace.gui.buy.title", 6, 139, 222, 0, false),

	BUY_LIST("marketplace.gui.buy.list.title", 6, 139, 222, 45, true),

	BUY_ITEM("marketplace.gui.buy.item.title", 3, 84, 167, 0, false),

	QUESTS("marketplace.gui.quests.title", 3, 84, 167, 0, false),

	ENDER_CHEST("marketplace.gui.ender.title", 3, 84, 167, 0, false);

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

	public boolean readOnlySlots() {
		return this.readOnlySlots;
	}

	public Component title() {
		return Component.translatable(this.titleKey);
	}

	public int containerSlots() {
		return this.rows * 9;
	}

	public int tradeSlots() {
		return this.tradeSlots;
	}

	public int buttonSlots() {
		return containerSlots() - this.tradeSlots;
	}

	public int rows() {
		return this.rows;
	}

	public int imageHeight() {
		return this.imageHeight;
	}

	public int inventoryY() {
		return this.inventoryY;
	}
}
