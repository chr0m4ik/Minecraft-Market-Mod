package com.chr0m4ik.marketplace.sound;

import com.chr0m4ik.marketplace.economy.EconomyConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class MarketSounds {

	private static final SoundSource SOURCE = SoundSource.MASTER;

	private static final float PITCH_JITTER = 0.05F;

	private MarketSounds() {
		// Класс только со статическими методами.
	}

	public static void buttonClick(ServerPlayer player) {
		play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.4F, 1.0F);
	}

	public static void shopBought(ServerPlayer player, boolean kit) {
		if (kit) {
			play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.5F, 1.2F);
		} else {
			play(player, SoundEvents.NOTE_BLOCK_HARP, 0.6F, 1.2F);
		}
	}

	public static void traderSold(ServerPlayer player) {
		play(player, SoundEvents.NOTE_BLOCK_HARP, 0.6F, 0.8F);
	}

	public static void orderSold(ServerPlayer player) {
		play(player, SoundEvents.PLAYER_LEVELUP, 0.5F, 1.0F);
	}

	public static void offlineMoney(ServerPlayer player) {
		play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.2F);
	}

	public static void marketSlotBought(ServerPlayer player) {
		play(player, SoundEvents.NOTE_BLOCK_BELL, 0.5F, 1.2F);
	}

	public static void ordersPlaced(ServerPlayer player) {
		play(player, SoundEvents.NOTE_BLOCK_PLING, 0.5F, 1.4F);
	}

	public static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		play(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch);
	}

	public static void play(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
		if (!EconomyConfig.soundsEnabled()) {
			return;
		}

		float varied = pitch * (1.0F + (player.getRandom().nextFloat() * 2.0F - 1.0F) * PITCH_JITTER);
		player.connection.send(new ClientboundSoundPacket(sound, SOURCE,
				player.getX(), player.getY(), player.getZ(), volume, varied,
				player.getRandom().nextLong()));
	}
}
