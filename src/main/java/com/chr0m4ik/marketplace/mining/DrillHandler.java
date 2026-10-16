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

/**
 * Зачарование «Бур»: вместе с исходным блоком ломает куб 3×3×3 / 5×5×5 / 7×7×7 вокруг него.
 *
 * <p>Исходный (сломанный игроком) блок — <b>центр</b> области и входит в неё; остальное ломаем мы.
 * Берём <b>любые</b> блоки, которые инструмент может добыть ({@code hasCorrectToolForDrops}) —
 * не только руды. Предел — {@link #MAX_BLOCKS} дополнительных блоков за одно нажатие.
 *
 * <p><b>Послойно, по одному слою за тик.</b> Заказчик попросил ломать слоями: так красивее и
 * безопаснее для сервера. Поэтому обработчик сломанного блока не ломает ничего сам, а строит
 * очередь: слои куба от центра наружу (расстояние Чебышёва 1, 2, 3), и каждый серверный тик
 * ломается очередной слой. Очередь живёт в памяти и <b>привязана к игроку</b>: если игрок вышел,
 * умер, сменил измерение или инструмент с зачарованием пропал — очередь просто сбрасывается.
 *
 * <p><b>Как ломаем.</b> Только через {@code serverPlayer.gameMode.destroyBlock(pos)} — это полный
 * ванильный цикл: проверка уровня инструмента ({@code canDestroyBlock}), защита территорий и
 * спавна ({@code blockActionRestricted}), событие {@code BEFORE} для чужих модов-защитников,
 * урон инструменту с учётом «Прочности», дроп с «Шёлковым касанием»/«Удачей» и опыт. Свой урон
 * инструменту наносить нельзя — он уже внутри.
 *
 * <p>«Шёлковое касание» и «Удача» с буром совместимы (см. теги несовместимостей): дроп считает
 * сама ваниль. С «Жилковым майнером» бур несовместим.
 */
public final class DrillHandler {
	/**
	 * Ключ нашего зачарования. {@code Holder} в статике не держим: реестр зачарований в 26.3
	 * динамический и пересоздаётся при {@code /reload} — устаревший держатель перестал бы
	 * находить зачарование.
	 */
	private static final ResourceKey<Enchantment> VEIN_DRILL =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("vein_drill"));

	/** Радиус куба по уровням зачарования (1..3): 3×3×3, 5×5×5, 7×7×7. */
	private static final int[] RADIUS_BY_LEVEL = { 0, 1, 2, 3 };

	/**
	 * Предел: не больше 128 дополнительных блоков за одно нажатие (как у «Жилкового майнера»).
	 * Исходный блок в предел не входит — его ломает сама игра. Из-за предела полный куб 7×7×7
	 * (342 блока вокруг центра) целиком не ломается: берём первые 128 блоков от центра наружу.
	 */
	private static final int MAX_BLOCKS = 128;

	/**
	 * Флаг «бур уже ломает блоки». Событие приходит и на вложенные вызовы
	 * {@code gameMode.destroyBlock}, поэтому без флага был бы бесконечный цикл.
	 * Ломание идёт в главном потоке сервера, так что одного флага на весь сервер хватает.
	 */
	private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

	/** Что осталось сломать у игрока: измерение и слои куба (от центра наружу). */
	private record Job(ResourceKey<Level> dimension, Deque<List<BlockPos>> layers) {
	}

	/** Очереди по игрокам. Ключ — UUID: очередь не сохраняется в сейв и живёт до выхода игрока. */
	private static final Map<UUID, Job> JOBS = new HashMap<>();

	private DrillHandler() {
		// Класс только со статическими методами.
	}

	/** Подключает обработчики. Вызывается один раз при загрузке мода. */
	public static void register() {
		PlayerBlockBreakEvents.AFTER.register(DrillHandler::afterBlockBreak);
		// Слои ломаются по одному за тик — очередь разбираем в конце серверного тика.
		ServerTickEvents.END_SERVER_TICK.register(DrillHandler::tick);
		MarketplaceMod.LOGGER.info("Бур: обработчик подключён, предел {} блоков, слоями по тику", MAX_BLOCKS);
	}

	/** Игрок сломал блок: если на инструменте есть «Бур» — строим очередь слоёв вокруг блока. */
	private static void afterBlockBreak(Level level, Player player, BlockPos pos, BlockState state,
			BlockEntity blockEntity) {
		// Событие общее (клиент и сервер), а ломать блоки можно только на сервере.
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		// Вложенный вызов: мы уже ломаем блоки буром, повторно запускаться нельзя.
		if (ACTIVE.get()) {
			return;
		}

		ItemStack tool = serverPlayer.getMainHandItem();
		int drillLevel = MiningSupport.level(serverPlayer, VEIN_DRILL, tool);

		if (drillLevel <= 0) {
			return;   // на инструменте нет нашего зачарования — не наше дело
		}

		// Инструмент не может добыть сам сломанный блок — область не наша (его сломала ваниль).
		if (!serverPlayer.hasCorrectToolForDrops(state)) {
			return;
		}

		List<List<BlockPos>> layers = plan(serverLevel, serverPlayer, pos, RADIUS_BY_LEVEL[drillLevel]);

		if (layers.isEmpty()) {
			return;
		}

		// Прочности должно хватить хотя бы на первый блок: за исходный блок игра ударит уже
		// после нас. Дальше проверяем перед каждым блоком (см. breakLayer), чтобы инструмент
		// не уничтожился; если прочности не хватает — ломается только исходный блок.
		if (!MiningSupport.hasDurabilityFor(serverPlayer, tool, 1)) {
			return;
		}

		// Новый сломанный блок начинает новую очередь: старая просто заменяется.
		JOBS.put(serverPlayer.getUUID(), new Job(serverLevel.dimension(), new ArrayDeque<>(layers)));
	}

	/** Каждый серверный тик ломает очередной слой у каждого игрока с активной очередью. */
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

			// Игрок вышел, умер или уехал в другое измерение — очередь сбрасывается.
			if (player == null || !player.isAlive() || level == null || player.level() != level) {
				iterator.remove();
				continue;
			}

			ItemStack tool = player.getMainHandItem();

			// Инструмент пропал или зачарование с него сняли — очередь сбрасывается.
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

	/**
	 * Ломает один слой. Перед каждым блоком проверяем прочность: инструмент не должен
	 * уничтожиться (в том числе с «Прочностью», где урон случаен). Не хватило — слой
	 * обрывается, а очередь снимет {@link #tick}.
	 */
	private static void breakLayer(ServerPlayer player, ServerLevel level, ItemStack tool, List<BlockPos> layer) {
		ACTIVE.set(true);

		try {
			for (BlockPos target : layer) {
				if (!MiningSupport.hasDurabilityFor(player, tool, 1)) {
					break;
				}

				// Блок мог исчезнуть сам (например, другой игрок его сломал) — тогда пропускаем:
				// destroyBlock по воздуху ничего не делает, но зря потратил бы проверку прочности.
				if (level.getBlockState(target).isAir()) {
					continue;
				}

				// Полный ванильный цикл ломания: уровень инструмента, защита, урон, дроп и опыт.
				player.gameMode.destroyBlock(target);
			}
		} finally {
			ACTIVE.set(false);
		}
	}

	/**
	 * План ломания: слои куба от центра наружу. Слой — внешняя оболочка куба радиуса {@code shell}
	 * (расстояние Чебышёва), поэтому внутренность уже сломана предыдущими слоями.
	 *
	 * <p>Берём только блоки, которые инструмент может добыть. Предел {@link #MAX_BLOCKS}
	 * дополнительных блоков: как только он выбран, следующие слои не строим (последний слой
	 * может оказаться короче).
	 */
	private static List<List<BlockPos>> plan(ServerLevel level, ServerPlayer player, BlockPos center, int radius) {
		List<List<BlockPos>> layers = new ArrayList<>();
		int planned = 0;

		for (int shell = 1; shell <= radius && planned < MAX_BLOCKS; shell++) {
			List<BlockPos> layer = new ArrayList<>();

			for (int dx = -shell; dx <= shell && planned < MAX_BLOCKS; dx++) {
				for (int dy = -shell; dy <= shell && planned < MAX_BLOCKS; dy++) {
					for (int dz = -shell; dz <= shell && planned < MAX_BLOCKS; dz++) {
						if (chebyshev(dx, dy, dz) != shell) {
							continue;   // внутренность куба: её ломают предыдущие слои
						}

						BlockPos target = center.offset(dx, dy, dz);

						// Незагруженный чанк не трогаем: getBlockState подгрузил бы его синхронно.
						if (!level.isLoaded(target)) {
							continue;
						}

						if (!canBreak(level, player, target)) {
							continue;
						}

						layer.add(target);
						planned++;
					}
				}
			}

			if (!layer.isEmpty()) {
				layers.add(List.copyOf(layer));
			}
		}

		return layers;
	}

	/** Расстояние Чебышёва: сколько «слоёв» от центра до блока. */
	private static int chebyshev(int dx, int dy, int dz) {
		return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
	}

	/**
	 * Можно ли сломать этот блок буром. Воздух и жидкости пропускаем: их нельзя «добыть»
	 * инструментом, а {@code destroyBlock} по воде просто убрал бы её. Неразрушимые блоки
	 * (бедрок, барьер) тоже пропускаем — инструмент их не берёт. Всё остальное решает
	 * ванильная проверка {@code hasCorrectToolForDrops}: кирка берёт камень, лопата — землю,
	 * топор — дерево, а алмазная руда каменной киркой не берётся.
	 */
	private static boolean canBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (state.isAir() || state.liquid()) {
			return false;
		}

		if (state.getDestroySpeed(level, pos) < 0.0F) {
			return false;
		}

		return player.hasCorrectToolForDrops(state);
	}
}
