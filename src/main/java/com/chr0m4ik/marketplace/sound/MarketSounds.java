package com.chr0m4ik.marketplace.sound;

import com.chr0m4ik.marketplace.economy.EconomyConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Звуки мода. Своих звуковых файлов нет — играем только ванильные (правило проекта:
 * ни своих текстур, ни своих звуков).
 *
 * <p><b>Почему не {@code player.playSound(...)}.</b> На сервере
 * {@code Player#playSound(SoundEvent, float, float)} уходит в {@code Level#playSound},
 * а тот рассылает звук всем игрокам рядом, <i>кроме</i> самого игрока (он передаётся как
 * «исключённый»). Для интерфейса это неверно: нажатие должен слышать тот, кто нажал.
 * Поэтому пакет {@link ClientboundSoundPacket} отправляем прямо в соединение нужного игрока —
 * так же поступает и сама игра ({@code ServerLevel#playSeededSound}, но у него список
 * получателей шире).
 *
 * <p>Все звуки идут с сервера: кнопки в окнах обрабатывает серверная часть окна
 * (см. {@code MarketplaceMenu#clicked}), поэтому дублирования «сервер + клиент» нет.
 *
 * <p>Громкость умеренная (0,25–0,6), питч слегка дрожит. Выключить всё можно флагом
 * {@code sounds} в {@code data/marketplace/economy.json} — проверка одна, в {@link #play}.
 *
 * <p><b>Звучат только содержательные события</b> (решение заказчика): покупка, продажа
 * торговцу, проданный ордер, деньги за офлайн-продажу, покупка слота, подтверждение ордеров
 * и смена режима/настройки. Обычные кнопки меню, страницы, открытие окон, забор предмета
 * и отказ — молчат: это был фоновый шум.
 */
public final class MarketSounds {
	/** Категория громкости: звуки интерфейса идут по общему ползунку «Общая громкость». */
	private static final SoundSource SOURCE = SoundSource.MASTER;

	/** Разброс питча (±5%), чтобы одинаковые нажатия не приедались. */
	private static final float PITCH_JITTER = 0.05F;

	private MarketSounds() {
		// Класс только со статическими методами.
	}

	/**
	 * Короткий щелчок смены режима или настройки. Играется только у кнопок, которые что-то
	 * переключают: количество, способ выдачи, наценка, режим-мишень. У остальных кнопок
	 * (выход, отмена, назад, разделы, справка) звука нет — иначе это фоновый шум.
	 */
	public static void buttonClick(ServerPlayer player) {
		play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.4F, 1.0F);
	}

	/** Покупка в магазине: обычный предмет — арфа, набор («кит») — колокольчики. */
	public static void shopBought(ServerPlayer player, boolean kit) {
		if (kit) {
			play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.5F, 1.2F);
		} else {
			play(player, SoundEvents.NOTE_BLOCK_HARP, 0.6F, 1.2F);
		}
	}

	/** Продажа торговцу: та же арфа, но нотой ниже — покупка и продажа различаются на слух. */
	public static void traderSold(ServerPlayer player) {
		play(player, SoundEvents.NOTE_BLOCK_HARP, 0.6F, 0.8F);
	}

	/** Ордер на рынке продался. */
	public static void orderSold(ServerPlayer player) {
		play(player, SoundEvents.PLAYER_LEVELUP, 0.5F, 1.0F);
	}

	/** Деньги за проданное, пока игрока не было в игре. */
	public static void offlineMoney(ServerPlayer player) {
		play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.2F);
	}

	/** Купили ячейку рынка. */
	public static void marketSlotBought(ServerPlayer player) {
		play(player, SoundEvents.NOTE_BLOCK_BELL, 0.5F, 1.2F);
	}

	/** Ордера выставлены (подтверждение в окне рынка). */
	public static void ordersPlaced(ServerPlayer player) {
		play(player, SoundEvents.NOTE_BLOCK_PLING, 0.5F, 1.4F);
	}

	/**
	 * Ванильный звук, заданный обычной константой ({@code SoundEvents.EXPERIENCE_ORB_PICKUP}
	 * и подобные). В держатель его превращаем так же, как сама игра
	 * ({@code Level#playSeededSound}): зарегистрированный звук становится ссылкой на реестр,
	 * а не «прямым» значением.
	 */
	public static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		play(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch);
	}

	/**
	 * Проигрывает звук ровно одному игроку. Если звуки выключены в настройках
	 * ({@code sounds: false} в {@code economy.json}), не делает ничего.
	 */
	public static void play(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
		if (!EconomyConfig.soundsEnabled()) {
			return;
		}

		// Питч слегка дрожит: одинаковые звуки подряд не так приедаются.
		float varied = pitch * (1.0F + (player.getRandom().nextFloat() * 2.0F - 1.0F) * PITCH_JITTER);
		player.connection.send(new ClientboundSoundPacket(sound, SOURCE,
				player.getX(), player.getY(), player.getZ(), volume, varied,
				player.getRandom().nextLong()));
	}
}
