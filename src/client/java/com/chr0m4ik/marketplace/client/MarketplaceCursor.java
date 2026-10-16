package com.chr0m4ik.marketplace.client;

/**
 * Память о позиции курсора для миксина мыши.
 *
 * <p>Держим её отдельно от самого миксина: Mixin запрещает публичные статические методы
 * в классе-миксине (он вливается в класс игры), а нашему экрану нужно сообщать миксину,
 * что окно магазина открыто.
 */
public final class MarketplaceCursor {
	/** Нужно ли возвращать курсор на место (включает наш экран). */
	private static boolean keep;
	/** Последняя настоящая позиция курсора. */
	private static double lastX;
	private static double lastY;
	private static boolean remembered;

	private MarketplaceCursor() {
		// Класс только со статическими методами.
	}

	/** Наш экран сообщает, что открыт (или закрыт). */
	public static void keep(boolean value) {
		keep = value;
	}

	public static boolean shouldKeep() {
		return keep;
	}

	public static boolean hasPosition() {
		return remembered;
	}

	public static double x() {
		return lastX;
	}

	public static double y() {
		return lastY;
	}

	/** Запомнить позицию курсора. */
	public static void remember(double x, double y) {
		lastX = x;
		lastY = y;
		remembered = true;
	}
}
