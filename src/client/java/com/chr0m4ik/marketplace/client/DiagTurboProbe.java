package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.Diag;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * ВРЕМЕННО: снимки «Турбо печки» для проверки глазами — предмет в инвентаре и блок в мире.
 *
 * <p>Заказчику нужно увидеть две вещи, которые словами не проверишь: что печка в инвентаре
 * блестит как зачарованная и что поставленный блок выглядит как ванильная печь. Проба сама
 * входит в мир, выдаёт себе четыре печки (командой {@code /marketdiag turbogive}), строит в
 * воздухе площадку с рядом печек (ванильная + четыре уровня), снимает три снимка и
 * останавливает игру: {@code turbo-1-world.png}, {@code turbo-2-inventory.png},
 * {@code turbo-3-furnace.png}. Снимки кладутся в {@code run/screenshots/}.
 *
 * <p><b>Осторожно с падением.</b> Площадка стоит высоко в воздухе, поэтому опыт ведётся так,
 * чтобы игрок не разбился: сначала включается креатив (в нём падение не убивает), площадка
 * строится и игрок ставится на её пол, и только когда игра подтвердила, что он <b>стоит на
 * земле</b>, проба идёт дальше. Пол в конце <b>не убирается</b>, иначе следующий прогон
 * начинался бы с падения и экрана смерти вместо снимков; убирается только ряд печек.
 * Если игрок всё-таки умер, проба возрождает его и начинает шаг заново.
 *
 * <p>Включается только переменной окружения {@code MARKETPLACE_TURBO_PROBE=1}: обычные запуски
 * игры проба не трогает. Удалить целиком вместе с записью в {@code fabric.mod.json}.
 */
public final class DiagTurboProbe implements ClientModInitializer {
	/** Мир, в котором снимаем: у него включены читы (иначе команды не сработают). */
	private static final String WORLD = "New World (1)";

	/** Пауза между шагами: сервер должен успеть выполнить команду, а клиент — построить чанки. */
	private static final int STEP_TICKS = 20;

	/** Удлинённая пауза: вход в мир, телепорт и перестройка чанков. */
	private static final int LONG_TICKS = 40;

	/** Сколько раз шаг может повториться, прежде чем проба признает, что застряла. */
	private static final int MAX_REPEATS = 100;

	/** Насколько высоко строим площадку: там точно чистое небо. */
	private static final int PLATFORM_Y = 200;

	/**
	 * Площадки, оставшиеся от прошлых прогонов пробы (координаты видны в логе): их надо убрать,
	 * иначе игрок находит в мире забытые ряды печек. Один раз чистим и больше не сорим.
	 */
	private static final String[] OLD_SPOTS = {
			"fill 2050 197 -3442 2066 206 -3426 minecraft:air",
			"fill 1128 197 -3422 1150 206 -3400 minecraft:air",
	};

	private static boolean enabled;
	private static int step;
	private static int ticks;
	private static int repeats;
	private static boolean worldRequested;
	private static BlockPos base;
	private static boolean itemsGiven;
	private static boolean litterCleaned;
	private static boolean finished;

	/** Fabric создаёт точку входа сам, поэтому конструктор обязан быть публичным и без аргументов. */
	public DiagTurboProbe() {
	}

	@Override
	public void onInitializeClient() {
		enabled = "1".equals(System.getenv("MARKETPLACE_TURBO_PROBE"));

		if (!enabled) {
			return;
		}

		Diag.log("проба печки включена (MARKETPLACE_TURBO_PROBE=1), сборка: {}", Diag.buildInfo());
		ClientTickEvents.END_CLIENT_TICK.register(DiagTurboProbe::tick);
	}

	private static void tick(Minecraft client) {
		if (finished) {
			// Даём игре записать снимки на диск и закрываемся, чтобы окно не мешало.
			if (--ticks <= 0) {
				Diag.log("проба печки: закрываю игру");
				client.stop();
			}

			return;
		}

		// Мир ещё не открыт: шаг 1 как раз этим и занимается, поэтому ждём только на остальных шагах.
		if (client.player == null && step != 0) {
			return;
		}

		// Смерть ломает всё остальное (экран смерти перехватывает и клики, и клавиши), поэтому
		// возрождаемся и начинаем с постройки площадки заново.
		if (client.player != null && client.player.isDeadOrDying()) {
			Diag.log("проба печки: игрок мёртв на шаге {} — возрождаюсь и начинаю заново", step);
			client.player.respawn();
			step = 1;
			ticks = 0;
			repeats = 0;
			base = null;
			itemsGiven = false;
			return;
		}

		if (ticks++ < delay()) {
			return;
		}

		ticks = 0;

		if (run(client)) {
			step++;
			repeats = 0;
		} else if (++repeats > MAX_REPEATS) {
			Diag.log("ВНИМАНИЕ: проба печки застряла на шаге {} — прекращаю", step);
			finish(client);
		}
	}

	/** Пауза перед текущим шагом. */
	private static int delay() {
		// Перед снимком мира ждём дольше: чанки должны достроиться, а игрок — встать на пол.
		if (step == 2) {
			return LONG_TICKS * 4;
		}

		return step >= 3 ? LONG_TICKS : STEP_TICKS;
	}

	/** Один шаг пробы. {@code false} — повторить его через паузу. */
	private static boolean run(Minecraft client) {
		switch (step) {
			case 0 -> {
				Diag.log("проба печки: шаг 1 — вхожу в мир «{}»", WORLD);
				return enterWorld(client, WORLD);
			}
			case 1 -> {
				Diag.log("проба печки: шаг 2 — выдаю печки и строю ряд");
				return build(client);
			}
			case 2 -> {
				Diag.log("проба печки: шаг 3 — снимок мира (HUD скрыт)");
				grab(client, "turbo-1-world.png");
				showHud(client, true);
				return true;
			}
			case 3 -> {
				Diag.log("проба печки: шаг 4 — перехожу в выживание, чтобы инвентарь был обычный");
				command(client, "gamemode survival @s");
				return client.player.onGround();
			}
			case 4 -> {
				Diag.log("проба печки: шаг 5 — открываю инвентарь клавишей, как это делает игрок");
				// Не setScreen, а настоящее нажатие клавиши инвентаря: так экран выбирает сама игра.
				KeyMapping.click(client.options.keyInventory.getDefaultKey());
				return true;
			}
			case 5 -> {
				Diag.log("проба печки: шаг 6 — снимок инвентаря (открыто {})",
						client.gui.screen() == null ? "ничего" : client.gui.screen().getClass().getSimpleName());
				dumpInventory(client);
				grab(client, "turbo-2-inventory.png");
				return true;
			}
			case 6 -> {
				Diag.log("проба печки: шаг 7 — закрываю инвентарь");
				client.gui.setScreen(null);
				return true;
			}
			case 7 -> {
				Diag.log("проба печки: шаг 8 — правый клик по печке");
				return openFurnace(client);
			}
			case 8 -> {
				Diag.log("проба печки: шаг 9 — снимок окна печки (открыто {})",
						client.gui.screen() == null ? "ничего" : client.gui.screen().getClass().getSimpleName());
				grab(client, "turbo-3-furnace.png");
				return client.gui.screen() instanceof AbstractFurnaceScreen;
			}
			default -> {
				return cleanup(client);
			}
		}
	}

	/** Вход в мир: если мир уже открыт — готово, иначе просим открыть его один раз. */
	private static boolean enterWorld(Minecraft client, String worldId) {
		if (client.level != null && client.player != null) {
			Diag.log("проба печки: мир открыт, играю за {}", client.player.getName().getString());
			return true;
		}

		if (worldRequested) {
			return false;
		}

		worldRequested = true;
		Diag.log("проба печки: мир не открыт — открываю «{}»", worldId);
		client.createWorldOpenFlows().openWorld(worldId, () -> Diag.log("не удалось открыть мир «{}»", worldId));
		return false;
	}

	/**
	 * Выдаём печки, строим площадку в воздухе и ставим ряд: ванильная печь и четыре уровня турбо.
	 * Шаг повторяется, пока игра не подтвердит, что игрок стоит на полу площадки: только тогда
	 * снимок будет снят с живого игрока, а не с падающего.
	 */
	private static boolean build(Minecraft client) {
		// Креатив: пока строим, падение не должно убивать.
		command(client, "gamemode creative @s");

		if (base == null) {
			BlockPos player = client.player.blockPosition();
			base = new BlockPos(player.getX(), PLATFORM_Y, player.getZ());
		}

		if (!itemsGiven) {
			command(client, "marketdiag turbogive");
			itemsGiven = true;
		}

		if (!litterCleaned) {
			for (String spot : OLD_SPOTS) {
				command(client, spot);
			}

			litterCleaned = true;
		}

		// Пол и чистый воздух вокруг: снимок не должен упираться в рельеф.
		command(client, "fill " + (base.getX() - 5) + " " + (base.getY() - 1) + " " + (base.getZ() - 2)
				+ " " + (base.getX() + 5) + " " + (base.getY() - 1) + " " + (base.getZ() + 6) + " minecraft:stone");
		command(client, "fill " + (base.getX() - 5) + " " + base.getY() + " " + (base.getZ() - 2)
				+ " " + (base.getX() + 5) + " " + (base.getY() + 3) + " " + (base.getZ() + 6) + " minecraft:air");

		// Ряд в трёх блоках перед игроком: слева ванильная печь, дальше четыре уровня турбо.
		int z = base.getZ() + 3;

		for (int index = -2; index <= 2; index++) {
			int x = base.getX() + index;
			boolean turbo = index > -2;
			command(client, "setblock " + x + " " + base.getY() + " " + z
					+ (turbo ? " marketplace:turbo_furnace" : " minecraft:furnace"));

			if (turbo) {
				command(client, "data merge block " + x + " " + base.getY() + " " + z
						+ " {turbo_level:" + (index + 3) + "}");
			}
		}

		// Ставим игрока в центр площадки и ждём, пока он встанет на пол.
		command(client, "tp @s " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " 0 12");

		if (!client.player.onGround()) {
			Diag.log("проба печки: ещё не стою на полу ({}) — повторяю", client.player.position());
			return false;
		}

		// Снимок мира — без HUD и чата (как по F1): на нём должны быть только печки.
		showHud(client, false);
		Diag.log("проба печки: ряд поставлен в z={}, игрок стоит в {}", z, client.player.position());
		return true;
	}

	/** Показать или скрыть HUD так же, как это делает F1. */
	private static void showHud(Minecraft client, boolean visible) {
		if (client.gui.hud.isHidden() == visible) {
			client.gui.hud.toggle();
		}
	}

	/** Что лежит в инвентаре: идентификатор, уровень, название и флаг блеска. */
	private static void dumpInventory(Minecraft client) {
		for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = client.player.getInventory().getItem(slot);

			if (stack.isEmpty()) {
				continue;
			}

			Diag.log("проба печки: слот {} — {} x{} «{}», блеск зачарования: {}", slot, Diag.itemId(stack),
					stack.getCount(), stack.getHoverName().getString(),
					stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
		}
	}

	/** Открываем печку так, как это делает правый клик мышью. */
	private static boolean openFurnace(Minecraft client) {
		if (client.gui.screen() != null) {
			client.gui.setScreen(null);
			return false;
		}

		// В руке не должно быть блока: иначе правый клик поставит печку рядом, а не откроет окно.
		client.player.getInventory().setSelectedSlot(0);
		BlockPos pos = new BlockPos(base.getX() - 1, base.getY(), base.getZ() + 3);
		AbstractContainerMenu before = client.player.containerMenu;
		client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
		Diag.log("проба печки: правый клик по {} рукой с {}, окно было {}, стало {}",
				pos, Diag.itemId(client.player.getMainHandItem()), before.getClass().getSimpleName(),
				client.player.containerMenu.getClass().getSimpleName());
		return client.player.containerMenu instanceof FurnaceMenu;
	}

	/**
	 * Убираем за собой: ряд печек и режим игры возвращаем как был. Пол площадки <b>оставляем</b>:
	 * без него игрок в выживании падает с высоты и следующий прогон начинается с экрана смерти.
	 */
	private static boolean cleanup(Minecraft client) {
		command(client, "fill " + (base.getX() - 2) + " " + base.getY() + " " + (base.getZ() + 3)
				+ " " + (base.getX() + 2) + " " + base.getY() + " " + (base.getZ() + 3) + " minecraft:air");
		command(client, "gamemode creative @s");
		Diag.log("проба печки: ряд убран, пол площадки оставлен, снимки в run/screenshots");
		finish(client);
		return true;
	}

	private static void command(Minecraft client, String command) {
		if (client.player != null) {
			client.player.connection.sendCommand(command);
		}
	}

	private static void grab(Minecraft client, String name) {
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		Screenshot.grab(client.gameDirectory, name, target, 1,
				message -> Diag.log("снимок экрана {}: {}", name, message.getString()));
	}

	private static void finish(Minecraft client) {
		Diag.log("проба печки закончена");
		finished = true;
		ticks = STEP_TICKS * 3;
	}
}
