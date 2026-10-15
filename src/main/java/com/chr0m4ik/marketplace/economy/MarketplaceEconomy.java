package com.chr0m4ik.marketplace.economy;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Сервис денег: единственное место, через которое мод меняет балансы.
 *
 * <p>Это «серверная» логика — она работает только на стороне сервера (в одиночной игре сервер
 * встроен в игру). Клиентские окна не должны менять деньги напрямую: они попросят сервер.
 *
 * <p>Главные правила (AGENTS.md §5.1):
 * <ul>
 *     <li>баланс не может стать отрицательным;</li>
 *     <li>если денег не хватает — покупка не совершается ({@link #trySpend}).</li>
 * </ul>
 */
public final class MarketplaceEconomy {
	private MarketplaceEconomy() {
		// Класс только со статическими методами.
	}

	/** Достаёт данные экономики из сейва мира (при первом обращении создаёт их). */
	public static MarketplaceState state(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(MarketplaceState.TYPE);
	}

	/** Текущий баланс игрока. */
	public static double getBalance(MinecraftServer server, UUID playerId) {
		return state(server).getBalance(playerId);
	}

	/** Устанавливает баланс. Отрицательное значение превращается в 0. */
	public static double setBalance(MinecraftServer server, UUID playerId, double amount) {
		double safeAmount = Math.max(0.0, MoneyFormat.round(amount));
		state(server).setBalance(playerId, safeAmount);
		return safeAmount;
	}

	/**
	 * Прибавляет сумму к балансу (отрицательная сумма — вычитает).
	 * Ниже нуля баланс не опускается. Возвращает получившийся баланс.
	 */
	public static double addBalance(MinecraftServer server, UUID playerId, double amount) {
		double current = getBalance(server, playerId);
		return setBalance(server, playerId, current + amount);
	}

	/**
	 * Пытается списать деньги.
	 *
	 * @return {@code true}, если денег хватило и они списаны; {@code false}, если не хватило —
	 *         в этом случае баланс не меняется.
	 */
	public static boolean trySpend(MinecraftServer server, UUID playerId, double amount) {
		if (amount < 0.0) {
			// Отрицательная сумма — это ошибка в коде, а не ситуация в игре. Пусть падает громко.
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
