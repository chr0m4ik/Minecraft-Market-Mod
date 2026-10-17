package com.chr0m4ik.marketplace.client;

public final class MarketplaceCursor {

	private static boolean keep;

	private static double lastX;
	private static double lastY;
	private static boolean remembered;

	private MarketplaceCursor() {
		// Класс только со статическими методами.
	}

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

	public static void remember(double x, double y) {
		lastX = x;
		lastY = y;
		remembered = true;
	}
}
