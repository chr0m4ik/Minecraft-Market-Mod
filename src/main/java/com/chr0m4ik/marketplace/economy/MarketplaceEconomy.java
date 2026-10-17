package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class MarketplaceEconomy {

	public static final double DEATH_LOSS_RATE = 0.05;

	public static final double DEATH_LOSS_LIMIT = 10_000_000.0;

	private MarketplaceEconomy() {
		// Класс только со статическими методами.
	}

	public static MarketplaceState state(MinecraftServer server) {

		MarketplaceState.setRegistries(server.registryAccess());
		return server.overworld().getDataStorage().computeIfAbsent(MarketplaceState.TYPE);
	}

	public static double getBalance(MinecraftServer server, UUID playerId) {
		return state(server).getBalance(playerId);
	}

	public static double setBalance(MinecraftServer server, UUID playerId, double amount) {
		double safeAmount = Math.max(0.0, MoneyFormat.round(amount));
		state(server).setBalance(playerId, safeAmount);
		return safeAmount;
	}

	public static double addBalance(MinecraftServer server, UUID playerId, double amount) {
		double current = getBalance(server, playerId);
		return setBalance(server, playerId, current + amount);
	}

	public static double applyDeathPenalty(ServerPlayer player) {
		if (player.isCreative() || player.isSpectator()) {
			return 0.0;
		}

		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return 0.0;
		}

		double balance = getBalance(server, player.getUUID());
		double loss = MoneyFormat.round(Math.min(balance * DEATH_LOSS_RATE, DEATH_LOSS_LIMIT));

		// MarketplaceMod.LOGGER.info("Смерть игрока {}: баланс {}, потеряно {}",
				// player.getScoreboardName(), balance, loss);

		if (loss <= 0.0) {
			return 0.0;
		}

		setBalance(server, player.getUUID(), balance - loss);
		return loss;
	}

	public static boolean trySpend(MinecraftServer server, UUID playerId, double amount) {
		if (amount < 0.0) {

			throw new IllegalArgumentException("Сумма списания не может быть отрицательной: " + amount);
		}

		double cost = MoneyFormat.round(amount);

		if (cost == 0.0) {
			return true;
		}

		double current = getBalance(server, playerId);

		if (current < cost) {
			return false;
		}

		setBalance(server, playerId, current - cost);
		return true;
	}
}
