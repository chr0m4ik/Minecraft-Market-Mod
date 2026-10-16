package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.Diag;
import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.network.OpenMarketplacePayload;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/**
 * ВРЕМЕННО: проба «как играет заказчик» — от нажатия клавиши {@code I} до открытого списка товаров.
 *
 * <p>Зачем: прежняя проверка дёргала каталог напрямую и не повторяла путь игрока. Здесь
 * повторяется ровно он: клиент просит открыть рынок (то же, что клавиша {@code I}), затем
 * мышь бьёт по иконке «Купить» и по иконке раздела «Особые предметы» — сначала настоящим кликом
 * по экрану ({@link Screen#mouseClicked}), а если экран клик не принял, дополнительно
 * отправляется тот же пакет, что шлёт мышь ({@code handleContainerInput}). После каждого шага
 * в лог пишется, что реально лежит в слотах открытого окна на клиенте и что на сервере.
 *
 * <p>Включается только переменной окружения {@code MARKETPLACE_PROBE=1}, чтобы обычные запуски
 * игры не трогать. Удалить целиком после того, как причина будет найдена.
 */
public final class DiagShopProbe {
	/** Мир заказчика: в нём и повторяем путь. */
	private static final String WORLD = "New World (1)";

	/** Клик по иконке «Особые предметы»: строка 1, столбец 2 сетки разделов (3×7). */
	private static final int SPECIAL_ICON_SLOT = 11;

	/** Сколько тиков ждать между шагами: сервер должен успеть открыть окно и прислать слоты. */
	private static final int STEP_TICKS = 40;

	/** Номер шага, на котором ждём смены окна после клика. */
	private static final int WAIT_AFTER_BUY = 3;
	private static final int WAIT_AFTER_SPECIAL = 5;

	private static boolean enabled;
	private static int ticks;
	private static int step;
	private static boolean worldRequested;
	private static boolean finished;

	private DiagShopProbe() {
		// Класс только со статическими методами.
	}

	/** Подключает пробу, если она включена переменной окружения. */
	public static void register() {
		enabled = "1".equals(System.getenv("MARKETPLACE_PROBE"));

		if (!enabled) {
			return;
		}

		Diag.log("проба включена (MARKETPLACE_PROBE=1), сборка: {}", Diag.buildInfo());
		ClientTickEvents.END_CLIENT_TICK.register(DiagShopProbe::tick);
	}

	private static void tick(Minecraft client) {
		ticks++;

		if (finished) {
			// Даём игре время записать снимок экрана и закрываемся, чтобы окно не мешало.
			if (ticks > STEP_TICKS * 2) {
				step = 1000;
				Diag.log("закрываю игру");
				client.stop();
			}

			return;
		}

		if (client.level == null || client.player == null || client.gameMode == null) {
			// Мир не открыт: просим клиент открыть мир заказчика сам, чтобы проба шла без кликов.
			if (!worldRequested && ticks > STEP_TICKS) {
				worldRequested = true;
				Diag.log("мир не открыт — открываю «{}»", WORLD);
				client.createWorldOpenFlows().openWorld(WORLD, () -> Diag.log("не удалось открыть мир «{}»", WORLD));
			}

			return;
		}

		if (step == 0) {
			// Мир открылся: отсчитываем шаги от этого момента.
			ticks = 0;
			step = 1;
			Diag.log("мир открыт, начинаю путь игрока (играет {})", client.player.getName().getString());
			return;
		}

		if (ticks < STEP_TICKS) {
			return;
		}

		ticks = 0;
		int current = step;
		step++;

		switch (current) {
			case 1 -> requestMarket(client);
			case 2 -> mouseClick(client, MarketplaceMenu.MAIN_SLOT_BUY, "«Купить»");
			case WAIT_AFTER_BUY -> checkWindow(client, WindowKind.BUY, MarketplaceMenu.MAIN_SLOT_BUY, "«Купить»");
			case 4 -> mouseClick(client, SPECIAL_ICON_SLOT, "иконка раздела «Особые предметы»");
			case WAIT_AFTER_SPECIAL -> checkWindow(client, WindowKind.BUY_LIST, SPECIAL_ICON_SLOT, "раздел «Особые предметы»");
			case 6 -> finishShop(client);
			case 7 -> drillRun(client, "drillrun 3 stone pickaxe");
			case 8 -> drillRun(client, "drillrun 2 stone pickaxe");
			case 9 -> drillRun(client, "drillrun 3 mixed pickaxe");
			case 10 -> drillRun(client, "drillrun 3 mixed shovel");
			case 11 -> finish(client);
			default -> step = 100;
		}
	}

	/** ВРЕМЕННО (диагностика проблем 2 и 3): опыт с буром в мире — строит куб и считает, что сломано. */
	private static void drillRun(Minecraft client, String arguments) {
		Diag.log("опыт бура: отправляю /marketdiag {}", arguments);
		client.player.connection.sendCommand("marketdiag " + arguments);
	}

	/** Шаг 1: то же, что нажатие клавиши I, — просим сервер открыть рынок. */
	private static void requestMarket(Minecraft client) {
		Diag.log("шаг 1: прошу сервер открыть рынок (как клавиша I)");

		if (ClientPlayNetworking.canSend(OpenMarketplacePayload.TYPE)) {
			ClientPlayNetworking.send(new OpenMarketplacePayload());
		} else {
			Diag.log("  сервер не принимает пакет открытия рынка — проба не сработает");
		}
	}

	/**
	 * Шаги 2 и 4: клик мышью по слоту открытого экрана — <b>через тот же путь, что у настоящей
	 * мыши</b>: {@code MouseHandler.onMove} ставит курсор в центр слота, {@code MouseHandler.onButton}
	 * нажимает и отпускает левую кнопку. Координаты считаются так, как их видит игрок: окно стоит
	 * по центру экрана, слот лежит внутри окна.
	 *
	 * <p>Так проверяется весь путь целиком, включая обработчик мыши и миксин курсора, а не только
	 * отправка пакета клика.
	 */
	private static void mouseClick(Minecraft client, int slotIndex, String what) {
		Screen screen = client.gui.screen();
		AbstractContainerMenu menu = client.player.containerMenu;
		Diag.log("шаг {}: клик по {} (слот {}), окно {}, экран {}", step - 1, what, slotIndex,
				kindOf(menu), screen == null ? "нет" : screen.getClass().getSimpleName());

		if (!(screen instanceof MarketplaceScreen ours) || slotIndex >= ours.getMenu().slots.size()) {
			Diag.log("  клик по экрану невозможен — шлю пакет клика напрямую");
			sendClick(client, menu, slotIndex);
			return;
		}

		int left = (screen.width - WindowKind.IMAGE_WIDTH) / 2;
		int top = (screen.height - ours.getMenu().getKind().imageHeight()) / 2;
		Slot slot = ours.getMenu().getSlot(slotIndex);
		double guiX = left + slot.x + 8;
		double guiY = top + slot.y + 8;

		// Настоящая мышь двигается и кликает в координатах окна игры, а экран считает в своих
		// (масштабированных) — переводим одно в другое так же, как это делает сама игра.
		Window window = client.getWindow();
		double scale = (double) window.getScreenWidth() / (double) screen.width;
		double rawX = guiX * scale;
		double rawY = guiY * scale;
		Diag.log("  слот на экране {}, {}; курсор окна {}, {} (масштаб {})", guiX, guiY, rawX, rawY, scale);

		client.mouseHandler.onMove(window.handle(), rawX, rawY, 0.0, 0.0);
		client.mouseHandler.onButton(window.handle(), new MouseButtonInfo(1, 0), 1);
		client.mouseHandler.onButton(window.handle(), new MouseButtonInfo(1, 0), 0);
	}

	/**
	 * Проверяем, сменилось ли окно после клика. Если мышь клик не провела (например, слот отказался
	 * его отдавать), отправляем пакет клика напрямую — так видно, на каком шаге ломается путь игрока:
	 * на клике мышью или на стороне сервера.
	 */
	private static void checkWindow(Minecraft client, WindowKind expected, int slotIndex, String what) {
		AbstractContainerMenu menu = client.player.containerMenu;
		Diag.log("шаг {}: после клика по {} открыто {}", step - 1, what, kindOf(menu));

		if (menu instanceof MarketplaceMenu ours && ours.getKind() == expected) {
			Diag.log("  окно сменилось как надо — путь игрока работает");
			return;
		}

		Diag.log("  ВНИМАНИЕ: клик мышью не привёл к окну {} — шлю пакет клика напрямую", expected);
		sendClick(client, menu, slotIndex);
	}

	/** Пакет клика — ровно тот, который отправляет мышь. */
	private static void sendClick(Minecraft client, AbstractContainerMenu menu, int slotIndex) {
		client.gameMode.handleContainerInput(menu.containerId, slotIndex, 0, ContainerInput.PICKUP, client.player);
	}

	/** Шаг 6: что в итоге лежит в слотах — на клиенте и на сервере, плюс снимок экрана. */
	private static void finishShop(Minecraft client) {
		AbstractContainerMenu menu = client.player.containerMenu;
		Diag.log("шаг 6: окно после клика по разделу — {}", kindOf(menu));
		Diag.log("  на экране: {}", client.gui.screen() == null ? "ничего" : client.gui.screen().getClass().getSimpleName());
		Diag.dumpMenu("КЛИЕНТ: слоты открытого окна", menu);

		MinecraftServer server = client.getSingleplayerServer();

		if (server != null) {
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);

				if (player != null) {
					Diag.dumpMenu("СЕРВЕР: слоты того же окна", player.containerMenu);
				}
			});
		}

		// Дополнительно: может ли клиент собрать список сам (в мультиплеере у него нет реестров мира).
		Diag.log("проверка: клиентский ShopCatalog.items(SPECIAL) = {}",
				ShopCatalog.items(ShopCatalog.Category.SPECIAL).size());

		Screenshot.grab(client.gameDirectory, "probe-special.png", client.gameRenderer.mainRenderTarget(), 1,
				message -> Diag.log("снимок экрана: {}", message.getString()));
	}

	/** Последний шаг: закрываем окно и игру, чтобы она не мешала. */
	private static void finish(Minecraft client) {
		Diag.log("проба закончена");
		finished = true;
		ticks = 0;
	}

	/** Вид нашего окна — по нему видно, какое окно реально открыто. */
	private static String kindOf(AbstractContainerMenu menu) {
		if (menu instanceof MarketplaceMenu ours) {
			return ours.getKind().name();
		}

		return menu.getClass().getSimpleName();
	}
}
