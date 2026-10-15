package com.chr0m4ik.marketplace.economy;

import net.minecraft.server.MinecraftServer;

/**
 * Игровое время: сколько тиков в сутках и какой сейчас день.
 * Считается по времени мира, поэтому переживает перезапуск игры.
 */
public final class GameTime {
	/** Длительность игровых суток в тиках. */
	public static final long TICKS_PER_DAY = 24_000L;

	private GameTime() {
		// Класс только со статическими методами.
	}

	/** Общее игровое время в тиках. */
	public static long now(MinecraftServer server) {
		return server.overworld().getLevelData().getGameTime();
	}

	/** Номер текущего игрового дня. */
	public static long day(MinecraftServer server) {
		return now(server) / TICKS_PER_DAY;
	}
}
