package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
	/** Доля денег, которую игрок теряет при смерти. */
	public static final double DEATH_LOSS_RATE = 0.05;

	/** Сколько максимум можно потерять за одну смерть. */
	public static final double DEATH_LOSS_LIMIT = 10_000_000.0;

	private MarketplaceEconomy() {
		// Класс только со статическими методами.
	}

	/** Достаёт данные экономики из сейва мира (при первом обращении создаёт их). */
	public static MarketplaceState state(MinecraftServer server) {
		// Реестры нужны до чтения сейва: без них зачарованные предметы из ордеров не разбираются.
		MarketplaceState.setRegistries(server.registryAccess());
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
	 * Смерть игрока: он теряет часть денег — {@value #DEATH_LOSS_RATE} от баланса, но не больше
	 * {@value #DEATH_LOSS_LIMIT} за одну смерть. Так несколько миллиардов, накопленных за долгую
	 * игру, не пропадают из-за одной ошибки.
	 *
	 * <p>В креативе и режиме наблюдателя деньги не теряются: там их и не тратят.
	 *
	 * @return сколько денег потеряно (0, если терять было нечего)
	 */
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

		// Пишем в лог: если баланс вдруг окажется нулевым, причина будет видна сразу.
		MarketplaceMod.LOGGER.info("Смерть игрока {}: баланс {}, потеряно {}",
				player.getScoreboardName(), balance, loss);

		if (loss <= 0.0) {
			return 0.0;
		}

		setBalance(server, player.getUUID(), balance - loss);
		return loss;
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
