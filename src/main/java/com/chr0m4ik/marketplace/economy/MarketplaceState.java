package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Данные экономики, которые лежат прямо в сейве мира (файл {@code data/marketplace_economy.dat}).
 *
 * <p>Сейчас внутри только балансы: «UUID игрока → сумма в долларах». Игрок, которого нет в таблице,
 * считается имеющим 0 долларов. Позже сюда добавятся купленные слоты, отложенные продажи
 * и история продаж — для этого в NBT просто появится ещё один ключ, старые сейвы продолжат
 * открываться (отсутствующий ключ читается как «пусто»).
 */
public class MarketplaceState extends SavedData {
	/** Ключ в NBT, под которым лежит таблица балансов. */
	private static final String BALANCES_KEY = "balances";

	private final Map<UUID, Double> balances = new HashMap<>();

	public MarketplaceState() {
	}

	/** Переводит NBT в наш объект и обратно. */
	private static final Codec<MarketplaceState> CODEC = CompoundTag.CODEC.xmap(
			MarketplaceState::fromTag,
			MarketplaceState::toTag
	);

	/** Описание данных для игры: имя файла в сейве, конструктор и codec. */
	public static final SavedDataType<MarketplaceState> TYPE = new SavedDataType<>(
			MarketplaceMod.id("economy"),
			MarketplaceState::new,
			CODEC,
			null // datafixer не нужен: свои старые форматы мы разбираем сами
	);

	/** Возвращает баланс игрока. Если игрока нет в таблице — 0. */
	public double getBalance(UUID playerId) {
		return balances.getOrDefault(playerId, 0.0);
	}

	/** Записывает баланс игрока и помечает данные как изменённые, чтобы мир их сохранил. */
	public void setBalance(UUID playerId, double amount) {
		balances.put(playerId, MoneyFormat.round(amount));
		setDirty();
	}

	// --- Превращение в NBT и обратно ---

	private static MarketplaceState fromTag(CompoundTag tag) {
		MarketplaceState state = new MarketplaceState();
		CompoundTag storedBalances = tag.getCompoundOrEmpty(BALANCES_KEY);

		for (String key : storedBalances.keySet()) {
			Optional<UUID> playerId = parseUuid(key);

			if (playerId.isEmpty()) {
				// Не падаем и не прячем проблему: пишем в лог, что именно не так.
				MarketplaceMod.LOGGER.warn("В сейве найден некорректный UUID игрока, запись пропущена: {}", key);
				continue;
			}

			state.balances.put(playerId.get(), MoneyFormat.round(storedBalances.getDoubleOr(key, 0.0)));
		}

		return state;
	}

	private static CompoundTag toTag(MarketplaceState state) {
		CompoundTag tag = new CompoundTag();
		CompoundTag storedBalances = new CompoundTag();

		state.balances.forEach((playerId, amount) -> storedBalances.putDouble(playerId.toString(), amount));

		tag.put(BALANCES_KEY, storedBalances);
		return tag;
	}

	private static Optional<UUID> parseUuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
