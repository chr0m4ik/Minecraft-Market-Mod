package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Map;
import java.util.WeakHashMap;

public final class MagnetHandler {

	private static final ResourceKey<Enchantment> MAGNET =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("magnet"));

	private static final int[] LIMIT_BY_LEVEL = { 0, 256, 1024 };

	private static final class Session {
		private int remaining;
	}

	private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();

	private MagnetHandler() {
		// Класс только со статическими методами.
	}

	public static void register() {
		// MarketplaceMod.LOGGER.info("Магнит: дроп уходит в инвентарь, предел 256 предметов на I уровне "
				// + "и 1024 на II");
	}

	// Ломание блока: запас на нажатие

	public static void onSwingStart(ServerPlayer player) {

		// 256/1024 предметов на каждый слой.
		if (!DrillHandler.isActive()) {
			startAction(player);
		}
	}

	private static void startAction(ServerPlayer player) {
		session(player).remaining = limit(player, player.getMainHandItem());
	}

	private static Session session(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player, key -> {
			Session created = new Session();
			created.remaining = limit(player, player.getMainHandItem());
			return created;
		});
	}

	private static int limit(ServerPlayer player, ItemStack tool) {
		int level = MiningSupport.level(player, MAGNET, tool);

		if (level <= 0) {
			return 0;
		}

		return LIMIT_BY_LEVEL[Math.clamp(level, 1, LIMIT_BY_LEVEL.length - 1)];
	}

	// Перехват дропа

	public static void absorb(ItemStack itemStack) {
		ServerPlayer player = BreakContext.player();

		if (player == null || itemStack.isEmpty()) {
			return;
		}

		int limit = limit(player, BreakContext.tool());

		if (limit <= 0) {
			return;   // на инструменте нет магнита — дроп обычный
		}

		Session session = session(player);
		int taken = Math.min(session.remaining, itemStack.getCount());

		if (taken <= 0) {
			return;
		}

		session.remaining -= taken;

		// пропадает: предмет просто падает на землю.
		player.getInventory().placeItemBackInInventory(itemStack.split(taken), Prediction.SERVER_ONLY);
	}
}
