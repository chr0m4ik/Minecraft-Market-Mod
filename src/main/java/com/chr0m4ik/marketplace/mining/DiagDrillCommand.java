package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.Diag;
import com.chr0m4ik.marketplace.MarketplaceMod;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ВРЕМЕННО: проверка «Бура» числами — форма куба и подходящие блоки.
 *
 * <p>Зачем: заказчик сказал «копает не ровный куб» и «кирка копает землю и дерево», а словами
 * это не проверишь. Команды считают ровно то, что происходит:
 * <ul>
 *     <li>{@code /marketdiag drillshape <уровень>} — сколько блоков в каждом слое куба и правда
 *     ли это ровный куб (центр, симметрия, полнота). Мир не нужен;</li>
 *     <li>{@code /marketdiag drilltools} — таблица «инструмент × блок»: что бур считает подходящим
 *     (тип инструмента по ванильным тегам «чем добывается» плюс уровень инструмента). Мир не нужен;</li>
 *     <li>{@code /marketdiag drillrun <уровень> stone|mixed <инструмент>} — настоящий опыт в мире:
 *     строится каменный куб вокруг выбранного блока, блок ломается, и через пару секунд считается,
 *     что осталось на месте. Для этого нужен игрок (мир и инструмент), поэтому команду удобно
 *     запускать из игры.</li>
 * </ul>
 *
 * <p>Удалить вместе с вызовами после проверки.
 */
public final class DiagDrillCommand {
	/** Ключ зачарования «Бур». */
	private static final ResourceKey<Enchantment> VEIN_DRILL =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("vein_drill"));

	/** Сколько тиков ждать после ломания центрального блока (слои ломаются по одному за тик). */
	private static final int WAIT_TICKS = 15;

	/** Инструменты для таблицы «инструмент × блок». */
	private static final Map<String, Item> TOOLS = new LinkedHashMap<>();

	/** Блоки для таблицы: камень и родня, земля, дерево. */
	private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();

	static {
		TOOLS.put("wooden_pickaxe", Items.WOODEN_PICKAXE);
		TOOLS.put("stone_pickaxe", Items.STONE_PICKAXE);
		TOOLS.put("iron_pickaxe", Items.IRON_PICKAXE);
		TOOLS.put("diamond_pickaxe", Items.DIAMOND_PICKAXE);
		TOOLS.put("netherite_pickaxe", Items.NETHERITE_PICKAXE);
		TOOLS.put("diamond_shovel", Items.DIAMOND_SHOVEL);
		TOOLS.put("diamond_axe", Items.DIAMOND_AXE);
		TOOLS.put("diamond_hoe", Items.DIAMOND_HOE);

		BLOCKS.put("stone", Blocks.STONE);
		BLOCKS.put("deepslate", Blocks.DEEPSLATE);
		BLOCKS.put("cobblestone", Blocks.COBBLESTONE);
		BLOCKS.put("coal_ore", Blocks.COAL_ORE);
		BLOCKS.put("iron_ore", Blocks.IRON_ORE);
		BLOCKS.put("diamond_ore", Blocks.DIAMOND_ORE);
		BLOCKS.put("deepslate_diamond_ore", Blocks.DEEPSLATE_DIAMOND_ORE);
		BLOCKS.put("obsidian", Blocks.OBSIDIAN);
		BLOCKS.put("dirt", Blocks.DIRT);
		BLOCKS.put("sand", Blocks.SAND);
		BLOCKS.put("gravel", Blocks.GRAVEL);
		BLOCKS.put("oak_log", Blocks.OAK_LOG);
		BLOCKS.put("oak_planks", Blocks.OAK_PLANKS);
		BLOCKS.put("oak_leaves", Blocks.OAK_LEAVES);
		BLOCKS.put("glass", Blocks.GLASS);
	}

	/** Почему опыт не запустился. */
	private static final SimpleCommandExceptionType NO_PLAYER =
			new SimpleCommandExceptionType(Component.literal("нужен игрок: команда строит куб рядом с ним"));
	private static final SimpleCommandExceptionType NO_SPACE =
			new SimpleCommandExceptionType(Component.literal("не нашёл свободного места для куба над игроком"));
	private static final SimpleCommandExceptionType NO_ENCHANT =
			new SimpleCommandExceptionType(Component.literal("зачарование marketplace:vein_drill не найдено"));

	/** Идущий опыт: что построили и когда считать результат. */
	private static final class Test {
		private final ServerLevel level;
		private final BlockPos center;
		private final int radius;
		private final BlockPos from;
		private final BlockPos to;
		private final ServerPlayer player;
		private final GameType oldMode;
		private final ItemStack oldHand;
		private final Map<String, Integer> placed = new TreeMap<>();
		private int ticksLeft = WAIT_TICKS;

		private Test(ServerLevel level, BlockPos center, int radius, BlockPos from, BlockPos to,
				ServerPlayer player, GameType oldMode, ItemStack oldHand) {
			this.level = level;
			this.center = center;
			this.radius = radius;
			this.from = from;
			this.to = to;
			this.player = player;
			this.oldMode = oldMode;
			this.oldHand = oldHand;
		}
	}

	private static Test test;

	private DiagDrillCommand() {
		// Класс только со статическими методами.
	}

	/** Подключает команды проверки. Вызывается один раз при загрузке мода. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> tickTest());

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
				Commands.literal("marketdiag")
						.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
						.then(Commands.literal("drillshape")
								.executes(context -> shape(context, 1))
								.then(Commands.argument("level", IntegerArgumentType.integer(1, 3))
										.executes(context -> shape(context,
												IntegerArgumentType.getInteger(context, "level")))))
						.then(Commands.literal("drilltools")
								.executes(DiagDrillCommand::tools))
						.then(Commands.literal("drillrun")
								.then(Commands.argument("level", IntegerArgumentType.integer(1, 3))
										.then(Commands.argument("mode", StringArgumentType.word())
												.executes(context -> run(context,
														IntegerArgumentType.getInteger(context, "level"),
														StringArgumentType.getString(context, "mode"), "pickaxe"))
												.then(Commands.argument("tool", StringArgumentType.word())
														.executes(context -> run(context,
																IntegerArgumentType.getInteger(context, "level"),
																StringArgumentType.getString(context, "mode"),
																StringArgumentType.getString(context, "tool")))))))));
	}

	// ---------------------------------------------------------------------------------------------
	// Форма куба

	/** Сколько блоков в каждом слое куба и правда ли он ровный. Мир не нужен. */
	private static int shape(CommandContext<CommandSourceStack> context, int level) {
		List<List<BlockPos>> layers = DrillHandler.shellLayers(level);
		Set<BlockPos> all = new HashSet<>();
		int total = 0;

		for (int index = 0; index < layers.size(); index++) {
			List<BlockPos> layer = layers.get(index);
			Diag.log("проверка формы бура {}: слой {} — {} блоков", level, index + 1, layer.size());
			total += layer.size();
			all.addAll(layer);
		}

		int side = 2 * level + 1;
		int expected = side * side * side - 1;
		boolean full = true;

		for (int dx = -level; dx <= level; dx++) {
			for (int dy = -level; dy <= level; dy++) {
				for (int dz = -level; dz <= level; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;   // центр ломает сама игра
					}

					if (!all.contains(new BlockPos(dx, dy, dz))) {
						full = false;
					}
				}
			}
		}

		Diag.log("проверка формы бура {}: куб {}×{}×{}, слоёв {}, блоков {} (ожидается {}), "
						+ "все смещения куба на месте: {}",
				level, side, side, side, layers.size(), total, expected, full && all.size() == expected);

		return total;
	}

	// ---------------------------------------------------------------------------------------------
	// Какие блоки подходят инструменту

	/** Таблица «инструмент × блок»: что бур считает подходящим. Мир не нужен. */
	private static int tools(CommandContext<CommandSourceStack> context) {
		for (Map.Entry<String, Item> tool : TOOLS.entrySet()) {
			ItemStack stack = new ItemStack(tool.getValue());
			TagKey<Block> tag = MiningSupport.mineableTag(stack);
			StringBuilder line = new StringBuilder();

			for (Map.Entry<String, Block> block : BLOCKS.entrySet()) {
				BlockState state = block.getValue().defaultBlockState();
				line.append(block.getKey()).append('=')
						.append(MiningSupport.canBreak(stack, state) ? "да" : "нет").append(", ");
			}

			Diag.log("фильтр бура: {} (тег {}): {}", tool.getKey(),
					tag == null ? "нет" : "#" + tag.location(), line);
		}

		return TOOLS.size();
	}

	// ---------------------------------------------------------------------------------------------
	// Настоящий опыт в мире

	private static int run(CommandContext<CommandSourceStack> context, int level, String mode, String toolName)
			throws CommandSyntaxException {
		if (test != null) {
			throw new SimpleCommandExceptionType(Component.literal("предыдущий опыт ещё идёт, подожди")).create();
		}

		ServerPlayer player = context.getSource().getPlayerOrException();
		ServerLevel world = player.level();
		int radius = level;
		BlockPos center = findSpot(world, player.blockPosition(), radius);

		if (center == null) {
			throw NO_SPACE.create();
		}

		Map<String, Block> mix = mixedBlocks();
		ItemStack tool = toolFor(toolName, level, player);

		if (tool.isEmpty()) {
			throw NO_ENCHANT.create();
		}

		// Куб: вокруг центра радиус, плюс ещё один слой камня, чтобы увидеть, не залез ли бур дальше.
		int outer = radius + 1;
		BlockPos from = center.offset(-outer, -outer, -outer);
		BlockPos to = center.offset(outer, outer, outer);
		Test started = new Test(world, center, radius, from, to, player,
				player.gameMode(), player.getMainHandItem().copy());

		// Строим: внешний слой — камень, внутренний куб — камень или смесь (по режиму).
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			boolean inner = inside(pos, center, radius);
			Block block = inner ? blockFor(mode, mix, pos, center, toolName) : Blocks.STONE;
			world.setBlock(pos, block.defaultBlockState(), 3);
			started.placed.merge(blockName(block), 1, Integer::sum);
		}

		// В креативе прочность не тратится и нет дропа: опыт не должен ломать инструмент
		// и заваливать мир камнем.
		player.setGameMode(GameType.CREATIVE);
		player.setItemInHand(InteractionHand.MAIN_HAND, tool);
		Diag.log("опыт бура {} ({}), инструмент {}: куб {}×{}×{} построен вокруг {}, сломано будет не больше {} блоков; "
						+ "поставлено: {}",
				level, mode, toolName, outer * 2 + 1, outer * 2 + 1, outer * 2 + 1, center,
				(2 * radius + 1) * (2 * radius + 1) * (2 * radius + 1), started.placed);

		// Ломаем центральный блок — дальше бур работает сам, по слою за тик.
		boolean broken = player.gameMode.destroyBlock(center);
		Diag.log("опыт бура: центральный блок {} сломан игроком: {}", center, broken);
		test = started;

		return 1;
	}

	/** Ищем над игроком место, где весь куб (с запасным слоем) стоит в воздухе. */
	private static BlockPos findSpot(ServerLevel world, BlockPos playerPos, int radius) {
		int outer = radius + 1;

		for (int dy = 8; dy <= 60; dy += 2) {
			BlockPos center = playerPos.offset(0, dy, 0);
			boolean free = true;

			for (BlockPos pos : BlockPos.betweenClosed(center.offset(-outer, -outer, -outer),
					center.offset(outer, outer, outer))) {
				if (!world.getBlockState(pos).isAir()) {
					free = false;
					break;
				}
			}

			if (free) {
				return center;
			}
		}

		return null;
	}

	private static boolean inside(BlockPos pos, BlockPos center, int radius) {
		return Math.abs(pos.getX() - center.getX()) <= radius
				&& Math.abs(pos.getY() - center.getY()) <= radius
				&& Math.abs(pos.getZ() - center.getZ()) <= radius;
	}

	/**
	 * Какой блок поставить в точку. В смешанном режиме блоки идут столбиками (камень, земля,
	 * песок, дерево), но <b>в центре всегда тот блок, который инструмент умеет добывать</b>:
	 * бур запускается только от подходящего сломанного блока, иначе опыт ничего не покажет.
	 */
	private static Block blockFor(String mode, Map<String, Block> mix, BlockPos pos, BlockPos center, String toolName) {
		if (pos.equals(center)) {
			return switch (toolName) {
				case "shovel" -> Blocks.DIRT;
				case "axe" -> Blocks.OAK_LOG;
				default -> Blocks.STONE;
			};
		}

		if (!"mixed".equals(mode)) {
			return Blocks.STONE;
		}

		// Смесь столбиками: камень, земля, песок, дерево — видно, что бур берёт только своё.
		List<Block> values = new ArrayList<>(mix.values());
		int index = Math.floorMod(pos.getX() + pos.getZ(), values.size());
		return values.get(index);
	}

	/** Что подмешивать в опыт: кроме камня — земля, песок и дерево. */
	private static Map<String, Block> mixedBlocks() {
		Map<String, Block> mix = new LinkedHashMap<>();
		mix.put("stone", Blocks.STONE);
		mix.put("dirt", Blocks.DIRT);
		mix.put("sand", Blocks.SAND);
		mix.put("oak_log", Blocks.OAK_LOG);
		return mix;
	}

	/** Инструмент с нужным уровнем бура. */
	private static ItemStack toolFor(String toolName, int level, ServerPlayer player) {
		Item item = switch (toolName) {
			case "shovel" -> Items.NETHERITE_SHOVEL;
			case "axe" -> Items.NETHERITE_AXE;
			default -> Items.NETHERITE_PICKAXE;
		};
		ItemStack stack = new ItemStack(item);
		var holder = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(VEIN_DRILL);

		if (holder.isEmpty()) {
			return ItemStack.EMPTY;
		}

		stack.enchant(holder.get(), level);
		return stack;
	}

	private static String blockName(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).getPath();
	}

	/** Через несколько тиков считаем, что осталось на месте, и убираем за собой. */
	private static void tickTest() {
		if (test == null) {
			return;
		}

		if (--test.ticksLeft > 0) {
			return;
		}

		Test finished = test;
		test = null;
		int broken = 0;
		int leftInside = 0;
		int outerLeft = 0;
		Map<String, Integer> left = new TreeMap<>();

		for (BlockPos pos : BlockPos.betweenClosed(finished.from, finished.to)) {
			BlockState state = finished.level.getBlockState(pos);

			if (inside(pos, finished.center, finished.radius)) {
				if (state.isAir()) {
					broken++;
				} else {
					leftInside++;
					left.merge(blockName(state.getBlock()), 1, Integer::sum);
				}
			} else if (!state.isAir()) {
				// Внешний слой вокруг куба: он не должен быть тронут — иначе бур залез за куб.
				outerLeft++;
			}
		}

		int inner = (2 * finished.radius + 1) * (2 * finished.radius + 1) * (2 * finished.radius + 1);
		int outerExpected = (2 * finished.radius + 3) * (2 * finished.radius + 3) * (2 * finished.radius + 3) - inner;
		Diag.log("опыт бура радиус {}: внутри куба сломано {} из {} (осталось {}), внешний слой цел: {} ({} из {})",
				finished.radius, broken, inner, leftInside, outerLeft == outerExpected, outerLeft, outerExpected);
		Diag.log("опыт бура: поставлено {}, осталось {}", finished.placed, left);

		// Убираем за собой: и куб, и инструмент, и режим игры.
		for (BlockPos pos : BlockPos.betweenClosed(finished.from, finished.to)) {
			finished.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}

		finished.player.setItemInHand(InteractionHand.MAIN_HAND, finished.oldHand);
		finished.player.setGameMode(finished.oldMode);
		Diag.log("опыт бура: площадка убрана, инструмент и режим игры возвращены");
	}
}
