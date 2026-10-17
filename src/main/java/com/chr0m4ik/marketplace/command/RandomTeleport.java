package com.chr0m4ik.marketplace.command;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Случайный телепорт по обычному миру в радиусе 5000 блоков от точки 0,0. */
public final class RandomTeleport {

	private static final int RADIUS = 5000;

	private static final int ATTEMPTS = 24;

	private static final long COOLDOWN_MS = 30_000L;

	private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();

	private RandomTeleport() {
	}

	public static int teleport(ServerPlayer player) {
		if (player.level().dimension() != Level.OVERWORLD) {
			player.sendSystemMessage(Component.translatable("marketplace.rtp.wrongDimension")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		long now = System.currentTimeMillis();
		long last = LAST_USE.getOrDefault(player.getUUID(), 0L);

		if (now - last < COOLDOWN_MS) {
			long left = (COOLDOWN_MS - (now - last) + 999L) / 1000L;
			player.sendSystemMessage(Component.translatable("marketplace.rtp.cooldown",
					Component.literal(String.valueOf(left))).withStyle(ChatFormatting.GRAY));
			return 0;
		}

		ServerLevel level = player.level();

		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			int x = player.getRandom().nextInt(RADIUS * 2 + 1) - RADIUS;
			int z = player.getRandom().nextInt(RADIUS * 2 + 1) - RADIUS;

			// Загружаем чанк, иначе карта высот вернёт пустую поверхность.
			level.getChunk(x >> 4, z >> 4);

			BlockPos feet = safeSpot(level, x, z);

			if (feet == null) {
				continue;
			}

			LAST_USE.put(player.getUUID(), now);
			player.stopRiding();
			player.closeContainer();
			player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
					java.util.Set.of(), player.getYRot(), 0.0F, true);
			player.setDeltaMovement(Vec3.ZERO);
			player.resetFallDistance();

			player.sendSystemMessage(Component.translatable("marketplace.rtp.success",
					Component.literal(feet.getX() + " " + feet.getY() + " " + feet.getZ()))
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}

		player.sendSystemMessage(Component.translatable("marketplace.rtp.failed").withStyle(ChatFormatting.RED));
		return 0;
	}

	/** Ищет место, где игрок встанет на твёрдый блок, а не в воду или кактус. */
	private static BlockPos safeSpot(ServerLevel level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

		if (y <= level.getMinY() + 1) {
			return null;
		}

		BlockPos feet = new BlockPos(x, y, z);
		BlockState floor = level.getBlockState(feet.below());

		if (floor.isAir() || !floor.getFluidState().isEmpty() || unsafe(floor)
				|| floor.getCollisionShape(level, feet.below()).isEmpty()) {
			return null;
		}

		if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) {
			return null;
		}

		return feet;
	}

	private static boolean unsafe(BlockState state) {
		return state.is(Blocks.WATER) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.FIRE)
				|| state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
				|| state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
	}
}
