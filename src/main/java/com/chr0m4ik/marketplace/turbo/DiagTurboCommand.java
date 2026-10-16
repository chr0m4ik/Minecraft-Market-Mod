package com.chr0m4ik.marketplace.turbo;

import com.chr0m4ik.marketplace.Diag;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.market.MarketService;
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.market.SpecialCatalog;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * ВРЕМЕННО: проверка «Турбо печки» числами — печка, поломка, наковальня и магазин.
 *
 * <p>Заказчику нужны числа, а без игрока их не снять: RCON не умеет ни открывать наковальню,
 * ни ломать блок, ни покупать в магазине. Поэтому команды делают это сами на сервере
 * (для наковальни и магазина — через {@code FakePlayer} из Fabric API):
 * <ul>
 *     <li>{@code /marketdiag turbocook} — ставит в ряд ванильную печь и четыре турбо-печки,
 *     кладёт в каждую уголь и железную руду и считает тики до готового слитка. Заодно печатает
 *     {@code speed_multiplier} и {@code cooking_total_time} из NBT каждой печки;</li>
 *     <li>{@code /marketdiag turbobreak} — ставит четыре печки (1–4 уровня) с содержимым и ломает их
 *     киркой как игрок: печатает, что выпало и с каким уровнем;</li>
 *     <li>{@code /marketdiag turboanvil} — перебирает все 16 пар печек в наковальне: уровень
 *     результата, цена в уровнях, списание опыта и расход входов;</li>
 *     <li>{@code /marketdiag turboshop} — что лежит в разделе «Особые предметы», по какой цене
 *     и что остаётся в инвентаре после покупки каждого уровня.</li>
 * </ul>
 *
 * <p>Удалить вместе с вызовом после проверки — как остальную диагностику мода.
 */
public final class DiagTurboCommand {
	/** Насколько высоко над точкой возрождения строим опыт: там точно нет построек. */
	private static final int TEST_HEIGHT = 60;

	/** Сколько тиков ждём самую медленную печь, прежде чем сдаться (ванильная — 200). */
	private static final int MAX_TICKS = 400;

	/** Сколько печек в опыте: ванильная и четыре уровня турбо. */
	private static final int COOK_FURNACES = TurboFurnace.MAX_LEVEL + 1;

	/** Идущий опыт по времени плавки. */
	private static final class CookTest {
		private final ServerLevel level;
		private final List<BlockPos> positions = new ArrayList<>();
		private final List<String> names = new ArrayList<>();
		private final List<Integer> finished = new ArrayList<>();
		private int ticks;

		private CookTest(ServerLevel level) {
			this.level = level;
		}
	}
	private static CookTest cook;

	private DiagTurboCommand() {
		// Класс только со статическими методами.
	}

	/** Подключает команды проверки. Вызывается один раз при загрузке мода. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> tickCook());

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
				Commands.literal("marketdiag")
						.requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)))
						.then(Commands.literal("turbocook").executes(DiagTurboCommand::cook))
						.then(Commands.literal("turbobreak").executes(DiagTurboCommand::breakTest))
						.then(Commands.literal("turboanvil").executes(DiagTurboCommand::anvil))
						.then(Commands.literal("turboshop").executes(DiagTurboCommand::shop))
						.then(Commands.literal("turbocreative").executes(DiagTurboCommand::creative))
						.then(Commands.literal("turbogive").executes(DiagTurboCommand::give))));
	}

	/** Проверяет, что печка попала в креативное меню (вкладка «Функциональные блоки»). */
	private static int creative(CommandContext<CommandSourceStack> context) {
		// На сервере вкладки пустые, пока их не перестроят: тот же вызов делает и каталог магазина.
		CreativeModeTabs.tryRebuildTabContents(FeatureFlags.VANILLA_SET, true, context.getSource().registryAccess());
		int found = 0;

		for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
			List<ItemStack> displays = List.copyOf(tab.getDisplayItems());

			for (int index = 0; index < displays.size(); index++) {
				if (TurboUpgrade.isTurbo(displays.get(index))) {
					found++;
					Diag.log("креатив: печка «{}» есть во вкладке «{}» на месте {} из {}",
							displays.get(index).getHoverName().getString(), tab.getDisplayName().getString(),
							index, displays.size());
				}
			}
		}

		if (found == 0) {
			Diag.log("креатив: ВНИМАНИЕ — печки нет ни в одной вкладке креативного меню");
		}

		return found;
	}

	/** Выдаёт игроку четыре печки — ровно такие, какие продаёт магазин (для снимков клиента). */
	private static int give(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();

		if (player == null) {
			return 0;
		}

		for (int level = 1; level <= TurboFurnace.MAX_LEVEL; level++) {
			player.getInventory().placeItemBackInInventory(furnace(level), Prediction.SERVER_ONLY);
		}

		Diag.log("выдал игроку {} печек: уровни 1..{}", TurboFurnace.MAX_LEVEL, TurboFurnace.MAX_LEVEL);

		return TurboFurnace.MAX_LEVEL;
	}

	// ---------------------------------------------------------------------------------------------
	// Время плавки

	private static int cook(CommandContext<CommandSourceStack> context) {
		ServerLevel level = context.getSource().getServer().overworld();
		BlockPos base = testBase(level);
		CookTest started = new CookTest(level);

		// Без игрока чанк может не тикать: тогда печка не плавит вообще. Загружаем его принудительно.
		force(level, base, true);
		force(level, base.offset(COOK_FURNACES, 0, 0), true);

		// Площадка: воздух, чтобы печки стояли ровно и ничего не мешало.
		for (int dx = -1; dx <= COOK_FURNACES + 1; dx++) {
			for (int dy = -1; dy <= 2; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}

		for (int index = 0; index < COOK_FURNACES; index++) {
			BlockPos pos = base.offset(index, 0, 0);
			boolean turbo = index > 0;
			level.setBlock(pos, (turbo ? TurboFurnace.BLOCK : Blocks.FURNACE).defaultBlockState(), 3);
			BlockEntity entity = level.getBlockEntity(pos);
			String name = turbo ? "турбо " + index : "ванильная";

			if (turbo && entity instanceof TurboFurnaceBlockEntity furnace) {
				furnace.setLevel(index);
			}

			if (entity instanceof AbstractFurnaceBlockEntity furnace) {
				// Сначала руда, потом топливо: печка зажигается на следующем тике, и множитель
				// скорости подставляется в этот же момент (см. TurboFurnaceSpeedMixin).
				furnace.setItem(0, new ItemStack(Items.IRON_ORE));
				furnace.setItem(1, new ItemStack(Items.COAL));
			}

			started.positions.add(pos);
			started.names.add(name);
			started.finished.add(-1);
		}

		cook = started;
		Diag.log("опыт печки: поставлено {} печек в ряд от {}, в каждой уголь и железная руда; "
				+ "считаем тики до готового слитка", COOK_FURNACES, base);

		return COOK_FURNACES;
	}

	/** Раз в тик: у кого появился слиток — тому и время. Когда готовы все, печатаем таблицу. */
	private static void tickCook() {
		if (cook == null) {
			return;
		}

		cook.ticks++;
		int ready = 0;

		for (int index = 0; index < cook.positions.size(); index++) {
			if (cook.finished.get(index) >= 0) {
				ready++;
				continue;
			}

			if (!(cook.level.getBlockEntity(cook.positions.get(index)) instanceof AbstractFurnaceBlockEntity furnace)
					|| furnace.getItem(2).isEmpty()) {
				continue;
			}

			cook.finished.set(index, cook.ticks);
			ready++;
		}

		if (ready < cook.positions.size() && cook.ticks < MAX_TICKS) {
			return;
		}

		CookTest finished = cook;
		cook = null;

		for (int index = 0; index < finished.positions.size(); index++) {
			BlockPos pos = finished.positions.get(index);
			BlockEntity entity = finished.level.getBlockEntity(pos);
			String numbers = "нет печки";

			if (entity instanceof AbstractFurnaceBlockEntity furnace) {
				CompoundTag tag = furnace.saveCustomOnly(finished.level.registryAccess());
				numbers = "speed_multiplier=" + tag.getFloatOr("speed_multiplier", 1.0F)
						+ ", cooking_total_time=" + tag.getIntOr("cooking_total_time", 0)
						+ ", lit_time_remaining=" + tag.getIntOr("lit_time_remaining", 0)
						+ ", cooking_time_spent=" + tag.getIntOr("cooking_time_spent", 0)
						+ ", turbo_level=" + tag.getIntOr(TurboUpgrade.LEVEL_TAG, 0)
						+ ", руда=" + furnace.getItem(0).getCount()
						+ ", топливо=" + furnace.getItem(1).getCount()
						+ ", слитки=" + furnace.getItem(2).getCount()
						+ ", чанк тикает=" + finished.level.shouldTickBlocksAt(pos)
						+ ", сущности тикают=" + finished.level.isPositionEntityTicking(pos);
			}

			Diag.log("опыт печки: {} — слиток на тике {} (ожидание 200 / множитель), {}",
					finished.names.get(index), finished.finished.get(index), numbers);
		}

		// Убираем за собой: содержимое в руки не берём, печки сносим.
		for (BlockPos pos : finished.positions) {
			if (finished.level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
				furnace.clearContent();
			}

			finished.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}

		force(finished.level, finished.positions.get(0), false);
		force(finished.level, finished.positions.get(finished.positions.size() - 1), false);
		Diag.log("опыт печки: площадка убрана");
	}

	/** Принудительная загрузка чанка, в котором стоит блок. */
	private static void force(ServerLevel level, BlockPos pos, boolean forced) {
		level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, forced);
	}

	// ---------------------------------------------------------------------------------------------
	// Поломка и уровень в выпавшем предмете

	private static int breakTest(CommandContext<CommandSourceStack> context) {
		ServerLevel level = context.getSource().getServer().overworld();
		FakePlayer player = FakePlayer.get(level);
		player.setGameMode(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
		BlockPos base = testBase(level).offset(0, 0, 3);

		for (int value = 1; value <= TurboFurnace.MAX_LEVEL; value++) {
			BlockPos pos = base.offset(value * 2, 0, 0);
			level.setBlock(pos, TurboFurnace.BLOCK.defaultBlockState(), 3);
			BlockEntity entity = level.getBlockEntity(pos);

			if (entity instanceof TurboFurnaceBlockEntity furnace) {
				furnace.setLevel(value);
				// Содержимое: должно выпасть отдельными предметами.
				furnace.setItem(0, new ItemStack(Items.IRON_ORE, 3));
				furnace.setItem(1, new ItemStack(Items.COAL, 2));
			}

			// Ломаем так же, как игрок: полный ванильный путь, включая loot table.
			boolean broken = player.gameMode.destroyBlock(pos);
			List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0));
			StringBuilder line = new StringBuilder();

			for (ItemEntity drop : drops) {
				ItemStack stack = drop.getItem();
				line.append(Diag.itemId(stack)).append(" x").append(stack.getCount())
						.append(" уровень=").append(TurboUpgrade.levelOf(stack))
						.append(" «").append(stack.getHoverName().getString()).append("»; ");
				drop.discard();
			}

			Diag.log("поломка печки {} уровня: сломано {}, выпало: {}", value, broken,
					line.isEmpty() ? "ничего" : line.toString());
		}

		return TurboFurnace.MAX_LEVEL;
	}

	// ---------------------------------------------------------------------------------------------
	// Наковальня

	private static int anvil(CommandContext<CommandSourceStack> context) {
		MinecraftServer server = context.getSource().getServer();
		ServerLevel level = server.overworld();
		FakePlayer player = FakePlayer.get(level);
		player.setGameMode(GameType.SURVIVAL);
		ContainerLevelAccess access = ContainerLevelAccess.create(level, testBase(level));

		for (int first = 1; first <= TurboFurnace.MAX_LEVEL; first++) {
			for (int second = 1; second <= TurboFurnace.MAX_LEVEL; second++) {
				AnvilMenu menu = new AnvilMenu(0, player.getInventory(), access);
				menu.getSlot(AnvilMenu.INPUT_SLOT).set(furnace(first));
				// Второй вход — стак из трёх печек: видно, что расходуется ровно одна.
				menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(withCount(furnace(second), 3));
				menu.createResult();

				ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
				int cost = menu.getCost();

				if (result.isEmpty()) {
					Diag.log("наковальня: {} + {} → результата нет (цена {})", first, second, cost);
					continue;
				}

				int resultLevel = TurboUpgrade.levelOf(result);
				player.experienceLevel = cost + 5;
				boolean pickupEnough = menu.getSlot(AnvilMenu.RESULT_SLOT).mayPickup(player);
				player.experienceLevel = Math.max(0, cost - 1);
				boolean pickupTooFew = menu.getSlot(AnvilMenu.RESULT_SLOT).mayPickup(player);
				player.experienceLevel = cost + 5;
				int before = player.experienceLevel;
				menu.getSlot(AnvilMenu.RESULT_SLOT).onTake(player, result.copy());

				Diag.log("наковальня: {} + {} → уровень {}, цена {} (взять с {} уровнями: {}, с {}: {}); "
								+ "опыт {} → {}, входы после взятия: первый {}, второй {}",
						first, second, resultLevel, cost, cost + 5, pickupEnough, cost - 1, pickupTooFew,
						before, player.experienceLevel,
						menu.getSlot(AnvilMenu.INPUT_SLOT).getItem().getCount(),
						menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().getCount());
			}
		}

		return TurboFurnace.MAX_LEVEL * TurboFurnace.MAX_LEVEL;
	}

	// ---------------------------------------------------------------------------------------------
	// Магазин

	private static int shop(CommandContext<CommandSourceStack> context) {
		MinecraftServer server = context.getSource().getServer();
		ShopCatalog.refresh(context.getSource().registryAccess());
		List<ItemStack> items = ShopCatalog.items(ShopCatalog.Category.SPECIAL);
		Diag.log("магазин: в разделе «Особые предметы» {} товаров", items.size());

		for (ItemStack stack : items) {
			OptionalDouble price = SpecialCatalog.priceOf(stack);
			Diag.log("магазин: {} «{}» цена за штуку {}", Diag.itemId(stack),
					stack.getHoverName().getString(), price.isPresent() ? price.getAsDouble() : "нет");
		}

		FakePlayer player = FakePlayer.get(server.overworld());
		player.setGameMode(GameType.SURVIVAL);

		for (ItemStack stack : items) {
			if (!TurboUpgrade.isTurbo(stack)) {
				continue;
			}

			int level = TurboUpgrade.levelOf(stack);
			MarketplaceEconomy.setBalance(server, player.getUUID(), 10_000_000_000.0);
			double before = MarketplaceEconomy.getBalance(server, player.getUUID());
			player.getInventory().clearContent();
			MarketService.ShopState state =
					new MarketService.ShopState(ShopCatalog.Category.SPECIAL.ordinal(), 0, stack, 0, false);
			boolean bought = MarketService.buy(server, player, state);
			double after = MarketplaceEconomy.getBalance(server, player.getUUID());
			StringBuilder received = new StringBuilder();

			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				ItemStack got = player.getInventory().getItem(slot);

				if (!got.isEmpty()) {
					received.append(Diag.itemId(got)).append(" x").append(got.getCount())
							.append(" уровень=").append(TurboUpgrade.levelOf(got)).append("; ");
				}
			}

			Diag.log("магазин: покупка уровня {} — куплено {}, списано {}, в инвентаре: {}",
					level, bought, before - after, received);
		}

		return items.size();
	}

	// ---------------------------------------------------------------------------------------------
	// Мелочи

	/** Печка нужного уровня — как её выдаёт магазин. */
	private static ItemStack furnace(int level) {
		ItemStack stack = new ItemStack(TurboFurnace.ITEM);
		TurboUpgrade.setLevel(stack, level);
		TurboUpgrade.decorate(stack, level);

		return stack;
	}

	private static ItemStack withCount(ItemStack stack, int count) {
		ItemStack copy = stack.copy();
		copy.setCount(count);

		return copy;
	}

	/** Площадка для опытов: высоко над точкой возрождения мира (её чанки загружены всегда). */
	private static BlockPos testBase(ServerLevel level) {
		return level.getRespawnData().pos().offset(0, TEST_HEIGHT, 0);
	}
}
