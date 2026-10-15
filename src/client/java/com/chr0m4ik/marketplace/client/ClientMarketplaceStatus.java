package com.chr0m4ik.marketplace.client;

/**
 * Последнее состояние, полученное с сервера: баланс и стоимость инвентаря.
 * Нужно только для плашки на HUD.
 */
public final class ClientMarketplaceStatus {
	/** Пока данных с сервера нет, плашку не показываем. */
	private static boolean hasData;

	private static long balanceCents;
	private static long inventoryCents;

	private ClientMarketplaceStatus() {
		// Класс только со статическими методами.
	}

	public static void update(long balance, long inventory) {
		balanceCents = balance;
		inventoryCents = inventory;
		hasData = true;
	}

	/** Вызывается при выходе из мира, чтобы не показывать старые числа в меню. */
	public static void clear() {
		hasData = false;
		balanceCents = 0L;
		inventoryCents = 0L;
	}

	public static boolean hasData() {
		return hasData;
	}

	public static double balance() {
		return balanceCents / 100.0;
	}

	public static double inventoryValue() {
		return inventoryCents / 100.0;
	}
}
