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

/**
 * Зачарование «Жилковый майнер»: вместе с исходным блоком руды ломает всю жилу вокруг него.
 *
 * <p>Область — сфера 5×5×5 / 9×9×9 / 13×13×13 (радиус 2/4/6) по уровню зачарования. Ломаются
 * только блоки <b>того же типа</b>, что и исходный, и только руды; блоки, которые инструмент
 * не может добыть (каменная кирка и алмазная руда), не трогаются.
 *
 * <p><b>Почему подписка на {@code AFTER}, а не на {@code BEFORE}.</b> Событие {@code AFTER}
 * приходит уже после того, как ваниль убрала исходный блок, но <i>до</i> того, как она нанесла
 * урон инструменту и выбросила дроп. Значит:
 * <ul>
 *     <li>нужен {@link #ACTIVE флаг рекурсии} — иначе каждый сломанный нами блок снова вызовет
 *     этот же обработчик;</li>
 *     <li>урон за исходный блок игра нанесёт уже после нас, поэтому при расчёте прочности
 *     резервируем одну единицу и на него.</li>
 * </ul>
 *
 * <p><b>Как ломаем.</b> Только через {@code serverPlayer.gameMode.destroyBlock(pos)} — это полный
 * ванильный цикл: проверка уровня инструмента ({@code canDestroyBlock}), защита территорий и
 * спавна ({@code blockActionRestricted}), событие {@code BEFORE} для чужих модов-защитников,
 * урон инструменту с учётом «Прочности» и дроп с «Шёлковым касанием»/«Удачей» и опытом.
 * Свой урон инструменту наносить нельзя — он уже внутри.
 *
 * <p><b>Видимость</b> блоков обеспечивает сам обход: идём в ширину только по 6 ортогональным
 * соседям, поэтому путь до каждого блока проходит через уже пробитые пустоты. Рейкаст до каждого
 * блока не делаем — это дорого.
 */
public final class VeinMinerHandler {
	/**
	 * Ключ нашего зачарования. {@code Holder} в статике не держим: реестр зачарований в 26.3
	 * динамический и пересоздаётся при {@code /reload} — устаревший держатель перестал бы
	 * находить зачарование.
	 */
	private static final ResourceKey<Enchantment> VEIN_MINER =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("vein_miner"));

	/** Радиус области по уровням зачарования (1..3): 5×5×5, 9×9×9, 13×13×13. */
	private static final int[] RADIUS_BY_LEVEL = { 0, 2, 4, 6 };

	/**
	 * Предел: не больше 128 блоков за одно нажатие (константа заказчика из docs/special-items.md).
	 * Исходный блок в предел не входит — его ломает сама игра.
	 */
	private static final int MAX_BLOCKS = 128;

	/**
	 * Флаг «жила уже ломается». Событие приходит и на вложенные вызовы
	 * {@code gameMode.destroyBlock}, поэтому без флага был бы бесконечный цикл.
	 * Ломание блоков идёт в главном потоке сервера, так что одного флага на весь сервер хватает.
	 */
	private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

	/**
	 * Что считаем рудой. Список намеренно явный (а не тег {@code minecraft:ores}): в ванильный
	 * тег не входит древний обломок, а он заказчику нужен. Дописывать новые руды — сюда.
	 * Варианты «глубинного сланца» и Незера перечислены отдельно: майнер ломает только блоки
	 * того же типа, поэтому без них жила в глубоком сланце не работала бы.
	 */
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

	/** Подключает обработчик. Вызывается один раз при загрузке мода. */
	public static void register() {
		PlayerBlockBreakEvents.AFTER.register(VeinMinerHandler::afterBlockBreak);
		MarketplaceMod.LOGGER.info("Жилковый майнер: обработчик подключён, предел {} блоков", MAX_BLOCKS);
	}

	/** Игрок сломал блок: если на инструменте есть «Жилковый майнер» — ломаем жилу вокруг. */
	private static void afterBlockBreak(Level level, Player player, BlockPos pos, BlockState state,
			BlockEntity blockEntity) {
		// Событие общее (клиент и сервер), а ломать блоки можно только на сервере.
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		// Вложенный вызов: мы уже ломаем жилу, повторно запускаться нельзя.
		if (ACTIVE.get()) {
			return;
		}

		ItemStack tool = serverPlayer.getMainHandItem();
		int minerLevel = MiningSupport.level(serverPlayer, VEIN_MINER, tool);

		if (minerLevel <= 0) {
			return;   // на инструменте нет нашего зачарования — не наше дело
		}

		if (!isOre(state.getBlock())) {
			return;   // исходный блок не руда — жилу не ищем
		}

		if (!serverPlayer.hasCorrectToolForDrops(state)) {
			return;   // инструмент не может добыть этот блок: его не должно быть и в жиле
		}

		List<BlockPos> targets = collect(serverLevel, serverPlayer, pos, state, RADIUS_BY_LEVEL[minerLevel]);

		if (targets.isEmpty()) {
			return;
		}

		// Прочности должно хватить на все дополнительные блоки и ещё на одну единицу для
		// исходного блока (её игра нанесёт уже после нас). Не хватает — ломается только он.
		if (!MiningSupport.hasDurabilityFor(serverPlayer, tool, targets.size() + 1)) {
			return;
		}

		ACTIVE.set(true);

		try {
			for (BlockPos target : targets) {
				// Инструмент мог износиться по дороге (например, с «Прочностью» урон случаен).
				if (!MiningSupport.hasDurabilityFor(serverPlayer, tool, 1)) {
					break;
				}

				// Полный ванильный цикл ломания: уровень инструмента, защита, урон, дроп и опыт.
				serverPlayer.gameMode.destroyBlock(target);
			}
		} finally {
			ACTIVE.set(false);
		}
	}

	/**
	 * Руда ли это (см. {@link #ORES}). Список общий: им пользуются «Жилковый майнер» и «Плавильня»
	 * (второй — чтобы плавить дроп только руд, а не песка и еды).
	 */
	static boolean isOre(Block block) {
		return ORES.contains(block);
	}

	/**
	 * Собирает блоки жилы: обход в ширину от исходного блока только по 6 ортогональным соседям
	 * (диагонали и рейкасты не нужны — «видимость» даёт сам обход). Берём блоки того же типа,
	 * что и исходный, только руды, не дальше радиуса и не больше {@link #MAX_BLOCKS} штук.
	 */
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

				// Незагруженный чанк не трогаем: getBlockState подгрузил бы его синхронно.
				if (!level.isLoaded(next)) {
					continue;
				}

				BlockState nextState = level.getBlockState(next);

				// Только тот же самый блок (угольная жила — только уголь) и только руды.
				if (nextState.getBlock() != originBlock || !isOre(nextState.getBlock())) {
					continue;
				}

				// Инструмент не может добыть этот блок — не трогаем и дальше через него не идём.
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
