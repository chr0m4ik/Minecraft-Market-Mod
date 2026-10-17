package com.chr0m4ik.marketplace.economy;

import net.minecraft.server.MinecraftServer;

public final class GameTime {

	public static final long TICKS_PER_DAY = 24_000L;

	private GameTime() {
		// Класс только со статическими методами.
	}

	public static long now(MinecraftServer server) {
		return server.overworld().getLevelData().getGameTime();
	}

	public static long day(MinecraftServer server) {
		return now(server) / TICKS_PER_DAY;
	}
}
