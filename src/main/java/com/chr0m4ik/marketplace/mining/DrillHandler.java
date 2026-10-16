package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.Diag;
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
 * Берём <b>любые</b> блоки, которые инструмент может добыть ({@link MiningSupport#canBreak}) —
 * не только руды.
 *
 * <p><b>Послойно, по одному слою за тик.</b> Заказчик попросил ломать слоями: так красивее и
 * безопаснее для сервера. Поэтому обработчик сломанного блока не ломает ничего сам, а строит
 * очередь: слои куба от центра наружу (расстояние Чебышёва 1, 2, 3), и каждый серверный тик
 * ломается очередной слой. Очередь живёт в памяти и <b>привязана к игроку</b>: если игрок вышел,
 * умер, сменил измерение или инструмент с зачарованием пропал — очередь просто сбрасывается.
 *
 * <p><b>Слои не режем пополам.</b> Раньше здесь был предел в 128 блоков «как у жилкового
 * майнера», и куб 7×7×7 обрывался на середине внешнего слоя: получалась не ровная фигура,
 * а «куб с прикушенным углом» — заказчик описал это как «копает немного иначе». Предела у бура
 * нет: он и так ограничен размером куба (3³−1 = 26, 5³−1 = 124, 7³−1 = 342 блока).
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

	/**
	 * Ломает ли бур блоки прямо сейчас. Нужно «Магниту»: слои куба ломаются своим вызовом
	 * {@code destroyBlock} каждый тик, но это всё ещё одно нажатие игрока — запас магнита на них
	 * не должен сбрасываться.
	 */
	static boolean isActive() {
		return ACTIVE.get();
	}

	/** Подключает обработчики. Вызывается один раз при загрузке мода. */
	public static void register() {
		PlayerBlockBreakEvents.AFTER.register(DrillHandler::afterBlockBreak);
		// Слои ломаются по одному за тик — очередь разбираем в конце серверного тика.
		ServerTickEvents.END_SERVER_TICK.register(DrillHandler::tick);
		MarketplaceMod.LOGGER.info("Бур: обработчик подключён, куб 3×3×3/5×5×5/7×7×7, слоями по тику");
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
		// Проверка та же, что и для блоков куба: тип инструмента плюс его уровень.
		if (!MiningSupport.canBreak(tool, state)) {
			return;
		}

		List<List<BlockPos>> layers = plan(serverLevel, tool, pos, RADIUS_BY_LEVEL[drillLevel]);

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
		// ВРЕМЕННО (диагностика проблемы 2): сколько блоков слоя реально сломано.
		int broken = 0;
		int skipped = 0;

		try {
			for (BlockPos target : layer) {
				if (!MiningSupport.hasDurabilityFor(player, tool, 1)) {
					break;
				}

				// Блок мог исчезнуть сам (например, другой игрок его сломал) — тогда пропускаем:
				// destroyBlock по воздуху ничего не делает, но зря потратил бы проверку прочности.
				if (level.getBlockState(target).isAir()) {
					skipped++;
					continue;
				}

				// Полный ванильный цикл ломания: уровень инструмента, защита, урон, дроп и опыт.
				if (player.gameMode.destroyBlock(target)) {
					broken++;
				} else {
					skipped++;
				}
			}
		} finally {
			ACTIVE.set(false);
		}

		// ВРЕМЕННО (диагностика проблемы 2): по этим строкам видно, что слой ломается целиком.
		Diag.log("бур: слой из {} блоков — сломано {}, пропущено {}", layer.size(), broken, skipped);
	}

	/**
	 * План ломания: слои куба от центра наружу. Слой — внешняя оболочка куба радиуса {@code shell}
	 * (расстояние Чебышёва), поэтому внутренность уже сломана предыдущими слоями.
	 *
	 * <p>Берём только блоки, которые инструмент может добыть, — по этой же причине слой может
	 * оказаться короче или пропуститься целиком. Резать слой пополам нельзя: тогда фигура
	 * перестаёт быть ровным кубом (именно так и было раньше из-за предела в 128 блоков).
	 */
	private static List<List<BlockPos>> plan(ServerLevel level, ItemStack tool, BlockPos center, int radius) {
		List<List<BlockPos>> layers = new ArrayList<>();

		for (List<BlockPos> shell : shellLayers(radius)) {
			List<BlockPos> layer = new ArrayList<>();

			for (BlockPos offset : shell) {
				BlockPos target = center.offset(offset);

				// Незагруженный чанк не трогаем: getBlockState подгрузил бы его синхронно.
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

	/**
	 * Геометрия куба: слои Чебышёва от центра наружу, без всяких проверок мира. Смещения
	 * симметричны по всем трём осям, поэтому фигура — ровный куб со стороной {@code 2·radius+1}
	 * (центр в него не входит: его ломает сама игра).
	 *
	 * <p>Слои считаются здесь, а не в {@link #plan}, чтобы форму можно было проверить отдельно
	 * от мира (см. временную команду {@code /marketdiag drillshape}).
	 */
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

	/** Расстояние Чебышёва: сколько «слоёв» от центра до блока. */
	private static int chebyshev(int dx, int dy, int dz) {
		return Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
	}

	/**
	 * Можно ли сломать этот блок буром. Воздух и жидкости пропускаем: их нельзя «добыть»
	 * инструментом, а {@code destroyBlock} по воде просто убрал бы её. Неразрушимые блоки
	 * (бедрок, барьер) тоже пропускаем — инструмент их не берёт. Дальше решает
	 * {@link MiningSupport#canBreak}: тип инструмента (кирка — камень и родня, лопата — земля
	 * и песок, топор — дерево) и его уровень (каменная кирка не берёт алмазную руду).
	 */
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
