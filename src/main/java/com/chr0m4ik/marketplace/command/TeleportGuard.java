package com.chr0m4ik.marketplace.command;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Запрет телепортации в бою и общий кулдаун на все телепорты. */
public final class TeleportGuard {

	private static final long COOLDOWN_MS = 30_000L;

	private static final long COMBAT_MS = 30_000L;

	private static final Map<UUID, Long> LAST_TELEPORT = new ConcurrentHashMap<>();

	private static final Map<UUID, Long> COMBAT = new ConcurrentHashMap<>();

	private TeleportGuard() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (damageTaken <= 0.0F || !(entity instanceof ServerPlayer player)) {
				return;
			}

			// Урон от существа (моб, игрок, его стрела) — бой; падение и прочее не считаем.
			if (source.getEntity() == null) {
				return;
			}

			COMBAT.put(player.getUUID(), System.currentTimeMillis() + COMBAT_MS);
		});
	}

	/** Проверяет запреты и пишет причину игроку; true — телепорт нельзя. */
	public static boolean blocked(ServerPlayer player) {
		long combat = secondsLeft(COMBAT.getOrDefault(player.getUUID(), 0L));

		if (combat > 0) {
			player.sendSystemMessage(Component.translatable("marketplace.teleport.combat",
					Component.literal(String.valueOf(combat))).withStyle(ChatFormatting.RED));
			return true;
		}

		long cooldown = secondsLeft(LAST_TELEPORT.getOrDefault(player.getUUID(), 0L) + COOLDOWN_MS);

		if (cooldown > 0) {
			player.sendSystemMessage(Component.translatable("marketplace.teleport.cooldown",
					Component.literal(String.valueOf(cooldown))).withStyle(ChatFormatting.GRAY));
			return true;
		}

		return false;
	}

	/** Отмечает успешный телепорт — начинается общий кулдаун. */
	public static void mark(ServerPlayer player) {
		LAST_TELEPORT.put(player.getUUID(), System.currentTimeMillis());
	}

	private static long secondsLeft(long until) {
		long left = until - System.currentTimeMillis();
		return left <= 0 ? 0 : (left + 999L) / 1000L;
	}
}
