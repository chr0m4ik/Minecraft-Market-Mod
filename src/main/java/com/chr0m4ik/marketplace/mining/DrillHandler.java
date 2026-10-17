package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DrillHandler {

	private static final ResourceKey<Enchantment> VEIN_DRILL =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("vein_drill"));

	private static final int[] RADIUS_BY_LEVEL = { 0, 1, 2, 3 };

	private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

	private record Job(ResourceKey<Level> dimension, Deque<List<BlockPos>> layers) {
	}

	private static final Map<UUID, Job> JOBS = new HashMap<>();

	private DrillHandler() {
		// Класс только со статическими методами.
	}

	static boolean isActive() {
		return ACTIVE.get();
	}

	public static void register() {
		PlayerBlockBreakEvents.AFTER.register(DrillHandler::afterBlockBreak);

		ServerTickEvents.END_SERVER_TICK.register(DrillHandler::tick);
		// MarketplaceMod.LOGGER.info("Бур: обработчик подключён, куб 3×3×3/5×5×5/7×7×7, слоями по тику");
	}

	private static void afterBlockBreak(Level level, Player player, BlockPos pos, BlockState state,
			BlockEntity blockEntity) {

		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (ACTIVE.get()) {
			return;
		}

		ItemStack tool = serverPlayer.getMainHandItem();
		int drillLevel = MiningSupport.level(serverPlayer, VEIN_DRILL, tool);

		if (drillLevel <= 0) {
			return;
		}

		if (!MiningSupport.canBreak(tool, state)) {
			return;
		}

		List<List<BlockPos>> layers = plan(serverLevel, tool, pos, RADIUS_BY_LEVEL[drillLevel]);

		if (layers.isEmpty()) {
			return;
		}

		if (!MiningSupport.hasDurabilityFor(serverPlayer, tool, 1)) {
			return;
		}

		JOBS.put(serverPlayer.getUUID(), new Job(serverLevel.dimension(), new ArrayDeque<>(layers)));
	}

	private static void tick(MinecraftServer server) {
		if (JOBS.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<UUID, Job>> iterator = JOBS.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<UUID, Job> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Job job = entry.getValue();
			ServerLevel level = server.getLevel(job.dimension());

			if (player == null || !player.isAlive() || level == null || player.level() != level) {
				iterator.remove();
				continue;
			}

			ItemStack tool = player.getMainHandItem();

			if (tool.isEmpty() || MiningSupport.level(player, VEIN_DRILL, tool) <= 0) {
				iterator.remove();
				continue;
			}

			List<BlockPos> layer = job.layers().pollFirst();

			if (layer == null) {
				iterator.remove();
				continue;
			}

			breakLayer(player, level, tool, layer);

			if (job.layers().isEmpty()) {
				iterator.remove();
			}
		}
	}

	private static void breakLayer(ServerPlayer player, ServerLevel level, ItemStack tool, List<BlockPos> layer) {
		ACTIVE.set(true);

		try {
			for (BlockPos target : layer) {
				if (!MiningSupport.hasDurabilityFor(player, tool, 1)) {
					break;
				}

				if (level.getBlockState(target).isAir()) {
					continue;
				}

				player.gameMode.destroyBlock(target);
			}
		} finally {
			ACTIVE.set(false);
		}
	}

	private static List<List<BlockPos>> plan(ServerLevel level, ItemStack tool, BlockPos center, int radius) {
		List<List<BlockPos>> layers = new ArrayList<>();

		for (List<BlockPos> shell : shellLayers(radius)) {
			List<BlockPos> layer = new ArrayList<>();

			for (BlockPos offset : shell) {
				BlockPos target = center.offset(offset);

				if (!level.isLoaded(target)) {
					continue;
				}

				if (!canBreak(level, tool, target)) {
					continue;
				}

				layer.add(target);
			}

			if (!layer.isEmpty()) {
				layers.add(List.copyOf(layer));
			}
		}

		return layers;
	}

	static List<List<BlockPos>> shellLayers(int radius) {
		List<List<BlockPos>> layers = new ArrayList<>();

		for (int shell = 1; shell <= radius; shell++) {
			List<BlockPos> layer = new ArrayList<>();

			for (int dx = -shell; dx <= shell; dx++) {
				for (int dy = -shell; dy <= shell; dy++) {
					for (int dz = -shell; dz <= shell; dz++) {
						if (chebyshev(dx, dy, dz) == shell) {
							layer.add(new BlockPos(dx, dy, dz));
						}
					}
				}
			}

			layers.add(List.copyOf(layer));
		}

		return layers;
	}

	private static int chebyshev(int dx, int dy, int dz) {
		return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
	}

	private static boolean canBreak(ServerLevel level, ItemStack tool, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (state.isAir() || state.liquid()) {
			return false;
		}

		if (state.getDestroySpeed(level, pos) < 0.0F) {
			return false;
		}

		return MiningSupport.canBreak(tool, state);
	}
}
