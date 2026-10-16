package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Зачарование «Магнит»: добытые предметы уходят <b>прямо в инвентарь</b> игрока и не появляются
 * в мире даже на миг.
 *
 * <p>За уровни I и II в инвентарь уходит до 256 и до 1024 предметов за одно нажатие; всё, что сверх
 * этого (и всё, что не влезло в инвентарь), падает на землю как обычно. Прочность магнит не тратит:
 * он ничего не ломает, а только перенаправляет дроп.
 *
 * <h2>Как перехватывается дроп</h2>
 * <p>Ваниль выбрасывает дроп из {@code Block.playerDestroy} → {@code Block.dropResources} →
 * {@code Block.popResource(Level, BlockPos, ItemStack)} — именно там создаётся {@code ItemEntity}.
 * Миксин {@code BlockMixin} встаёт в начало {@code popResource} и отдаёт предмет
 * {@link #absorb(Level, ItemStack)} <b>до</b> создания сущности: что магнит забрал, то в мире не
 * появится вовсе (не «подберётся через тик», а не появится), остаток остаётся в стеке и падает
 * обычным путём.
 *
 * <h2>Откуда берётся игрок</h2>
 * <p>У {@code popResource} игрока нет — только позиция и предмет. Поэтому миксин
 * {@code ServerPlayerGameModeMixin} запоминает игрока на входе в {@code ServerPlayerGameMode.destroyBlock}
 * (полный ванильный цикл ломания блока, через него же ломают «Жилковый майнер» и «Бур»)
 * и очищает на выходе. Это даёт ещё одно важное свойство: магнит видит <b>всю</b> цепочку ломания —
 * и блок, который сломал игрок, и все блоки, которые за него сломали наши зачарования.
 *
 * <h2>Лимит за нажатие</h2>
 * <p>Запас магнита — это счётчик предметов, сброшенный на новом нажатии игрока. Нажатием считается
 * вызов {@code destroyBlock} с нулевой вложенностью, то есть начатый самим игроком: вложенные
 * вызовы «Жилкового майнера» идут внутри него, а слои «Бура» ломаются хоть и своим вызовом каждый
 * тик, но под флагом {@link DrillHandler#isActive()} — значит весь куб идёт в один запас, а не
 * получает новый на каждый слой.
 */
public final class MagnetHandler {
	/**
	 * Ключ нашего зачарования. {@code Holder} в статике не держим: реестр зачарований в 26.3
	 * динамический и пересоздаётся при {@code /reload} — устаревший держатель перестал бы
	 * находить зачарование.
	 */
	private static final ResourceKey<Enchantment> MAGNET =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("magnet"));

	/** Сколько предметов за одно нажатие уходит в инвентарь: уровень 1 — 256, уровень 2 — 1024. */
	private static final int[] LIMIT_BY_LEVEL = { 0, 256, 1024 };

	/**
	 * Кто сейчас ломает блок и чем. Заполняется на входе в {@code destroyBlock} и очищается на
	 * выходе, поэтому дроп вне ломания блока игроком (взрывы, сущности, воронки) не трогаем.
	 */
	private static ServerPlayer breaker;

	/** Инструмент, которым игрок начал это нажатие (по нему считаем уровень магнита). */
	private static ItemStack breakerTool = ItemStack.EMPTY;

	/** Вложенность {@code destroyBlock}: 0 — снаружи, 1 и больше — ломаем мы сами. */
	private static int breakDepth;

	/** Остаток магнита у игрока: сколько предметов ещё можно убрать в инвентарь. */
	private static final class Session {
		private int remaining;
	}

	/**
	 * Запасы по игрокам. Ключ слабый: после выхода игрока запись исчезает сама, чистить её не надо.
	 * Весь доступ идёт из главного потока сервера, поэтому обычной карты достаточно.
	 */
	private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();

	private MagnetHandler() {
		// Класс только со статическими методами.
	}

	/** Подключает обработчик. Вызывается один раз при загрузке мода. */
	public static void register() {
		MarketplaceMod.LOGGER.info("Магнит: дроп уходит в инвентарь, предел 256 предметов на I уровне "
				+ "и 1024 на II");
	}

	// ---------------------------------------------------------------------------------------------
	// Ломание блока: кто и чем

	/** Игрок начал (или продолжил вложенно) ломать блок. Вызывается из миксина {@code destroyBlock}. */
	public static void beginBreak(ServerPlayer player) {
		if (breakDepth == 0) {
			breaker = player;
			breakerTool = player.getMainHandItem();

			// Новое нажатие игрока: запас магнита восстанавливается. Слои «Бура» ломаются своими
			// вызовами destroyBlock, но под его флагом — для них запас продолжается, иначе куб
			// получал бы новые 256/1024 предметов на каждый слой.
			if (!DrillHandler.isActive()) {
				startAction(player);
			}
		}

		breakDepth++;
	}

	/** Игрок закончил ломать блок (в том числе неудачно). Вызывается из миксина {@code destroyBlock}. */
	public static void endBreak() {
		breakDepth--;

		if (breakDepth <= 0) {
			breakDepth = 0;
			breaker = null;
			breakerTool = ItemStack.EMPTY;
		}
	}

	/** Сбрасывает запас магнита игрока на полный для его уровня. */
	private static void startAction(ServerPlayer player) {
		session(player).remaining = limit(player, player.getMainHandItem());
	}

	/** Запас магнита игрока; если его ещё нет — создаём полным. */
	private static Session session(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player, key -> {
			Session created = new Session();
			created.remaining = limit(player, player.getMainHandItem());
			return created;
		});
	}

	/** Предел предметов у этого инструмента: 0 — магнита на нём нет (или уровень чужой). */
	private static int limit(ServerPlayer player, ItemStack tool) {
		int level = MiningSupport.level(player, MAGNET, tool);

		if (level <= 0) {
			return 0;
		}

		return LIMIT_BY_LEVEL[Math.clamp(level, 1, LIMIT_BY_LEVEL.length - 1)];
	}

	// ---------------------------------------------------------------------------------------------
	// Перехват дропа

	/**
	 * Забирает предмет в инвентарь игрока, пока у него остался запас магнита.
	 *
	 * <p>Вызывается из миксина в начале {@code Block.popResource}, <b>до</b> создания сущности
	 * предмета. Стек не копируется, а урезается: сколько магнит забрал, столько из него и ушло,
	 * и остаток ваниль тут же выбросит обычным путём — предмет не теряется и не дублируется.
	 */
	public static void absorb(Level level, ItemStack itemStack) {
		ServerPlayer player = breaker;

		if (player == null || itemStack.isEmpty() || !(level instanceof ServerLevel)) {
			return;
		}

		int limit = limit(player, breakerTool);

		if (limit <= 0) {
			return;   // на инструменте нет магнита — дроп обычный
		}

		Session session = session(player);
		int taken = Math.min(session.remaining, itemStack.getCount());

		if (taken <= 0) {
			return;   // запас на это нажатие исчерпан — всё остальное падает на землю
		}

		session.remaining -= taken;

		// Кладём в инвентарь ровно то, что забрали. Что не влезло — игра выбрасывает под ногами
		// игрока (Inventory.placeItemBackInInventory), поэтому при полном инвентаре ничего не
		// пропадает: предмет просто падает на землю.
		player.getInventory().placeItemBackInInventory(itemStack.split(taken), Prediction.SERVER_ONLY);
	}
}
