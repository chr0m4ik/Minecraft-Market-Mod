package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VeinMinerHandler {

	private static final ResourceKey<Enchantment> VEIN_MINER =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("vein_miner"));

	private static final int[] RADIUS_BY_LEVEL = { 0, 2, 4, 6 };

	private static final int MAX_BLOCKS = 128;

	private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

	private static final Set<Block> ORES = Set.of(
			// Уголь.
			Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
			// Железо.
			Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
			// Медь.
			Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
			// Золото (в том числе незерское).
			Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
			// Красный камень.
			Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
			// Изумруды.
			Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
			// Лазурит.
			Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
			// Алмазы.
			Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
			// Кварц в Незере.
			Blocks.NETHER_QUARTZ_ORE,
			// Древний обломок.
			Blocks.ANCIENT_DEBRIS);

	private VeinMinerHandler() {
		// Класс только со статическими методами.
	}

	public static void register() {
		PlayerBlockBreakEvents.AFTER.register(VeinMinerHandler::afterBlockBreak);
		// MarketplaceMod.LOGGER.info("Жилковый майнер: обработчик подключён, предел {} блоков", MAX_BLOCKS);
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
		int minerLevel = MiningSupport.level(serverPlayer, VEIN_MINER, tool);

		if (minerLevel <= 0) {
			return;
		}

		if (!isOre(state.getBlock())) {
			return;   // исходный блок не руда — жилу не ищем
		}

		if (!serverPlayer.hasCorrectToolForDrops(state)) {
			return;
		}

		List<BlockPos> targets = collect(serverLevel, serverPlayer, pos, state, RADIUS_BY_LEVEL[minerLevel]);

		if (targets.isEmpty()) {
			return;
		}

		if (!MiningSupport.hasDurabilityFor(serverPlayer, tool, targets.size() + 1)) {
			return;
		}

		ACTIVE.set(true);

		try {
			for (BlockPos target : targets) {

				if (!MiningSupport.hasDurabilityFor(serverPlayer, tool, 1)) {
					break;
				}

				serverPlayer.gameMode.destroyBlock(target);
			}
		} finally {
			ACTIVE.set(false);
		}
	}

	static boolean isOre(Block block) {
		return ORES.contains(block);
	}

	private static List<BlockPos> collect(ServerLevel level, ServerPlayer player, BlockPos origin,
			BlockState originState, int radius) {
		Block originBlock = originState.getBlock();
		int radiusSquared = radius * radius;
		List<BlockPos> targets = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();

		visited.add(origin);
		queue.addLast(origin);

		while (!queue.isEmpty() && targets.size() < MAX_BLOCKS) {
			BlockPos current = queue.removeFirst();

			for (Direction direction : Direction.values()) {
				BlockPos next = current.relative(direction);

				if (!visited.add(next)) {
					continue;
				}

				// За пределами сферы вокруг исходного блока.
				if (next.distSqr(origin) > radiusSquared) {
					continue;
				}

				if (!level.isLoaded(next)) {
					continue;
				}

				BlockState nextState = level.getBlockState(next);

				if (nextState.getBlock() != originBlock || !isOre(nextState.getBlock())) {
					continue;
				}

				if (!player.hasCorrectToolForDrops(nextState)) {
					continue;
				}

				targets.add(next);
				queue.addLast(next);

				if (targets.size() >= MAX_BLOCKS) {
					break;
				}
			}
		}

		return targets;
	}
}
