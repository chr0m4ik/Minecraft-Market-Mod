package com.chr0m4ik.marketplace.client;

import com.chr0m4ik.marketplace.Diag;
import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.network.OpenMarketplacePayload;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ВРЕМЕННО: проба «как играет заказчик» — от нажатия клавиши {@code I} до открытого списка товаров.
 *
 * <p>Зачем: прежняя проверка дёргала каталог напрямую и не повторяла путь игрока. Здесь
 * повторяется ровно он: клиент просит открыть рынок (то же, что клавиша {@code I}), затем
 * мышь бьёт по иконке «Купить» и по иконке раздела — сначала настоящим кликом по экрану
 * ({@link net.minecraft.client.MouseHandler#onButton}), а если экран клик не принял,
 * дополнительно отправляется тот же пакет, что шлёт мышь ({@code handleContainerInput}).
 * После каждого шага в лог пишется, что реально лежит в слотах открытого окна на клиенте
 * и что на сервере.
 *
 * <p>Сценарии проверки (по одному прогону, заказчик не нужен): свежий запуск, раздел
 * «Книги зачарований» (наших книг там быть не должно), {@code /reload}, смена языка ru↔en,
 * новый мир, перезаход в мир. Плюс опыт с буром, который был здесь раньше.
 *
 * <p>Включается только переменной окружения {@code MARKETPLACE_PROBE=1}, чтобы обычные запуски
 * игры не трогать. Удалить целиком после того, как причина будет найдена.
 */
public final class DiagShopProbe {
	/** Мир заказчика: в нём и повторяем путь. */
	private static final String WORLD = "New World (1)";

	/** Мир, который проба создаёт сама: «новый мир» из списка сценариев. */
	private static final String FRESH_WORLD = "probe-fresh-diag";

	/** Имя нового мира, как его видит игрок. */
	private static final String FRESH_WORLD_NAME = "Проба: свежий мир";

	/** Язык, который обычно стоит у нас в тестовом клиенте (в него же возвращаем после проверки). */
	private static final String BASE_LANGUAGE = "en_us";

	/** Клик по иконке «Особые предметы»: строка 1, столбец 2 сетки разделов (3×7) — индекс 1. */
	private static final int SPECIAL_ICON_SLOT = 11;

	/** Клик по иконке «Книги зачарований»: строка 2, столбец 2 сетки разделов — индекс 8. */
	private static final int BOOKS_ICON_SLOT = 20;

	/** Сколько тиков ждать между шагами: сервер должен успеть открыть окно и прислать слоты. */
	private static final int STEP_TICKS = 20;

	/** Удлинённая пауза: после /reload, смены языка и входа в мир нужно больше времени. */
	private static final int LONG_TICKS = 45;

	/** Сколько раз подряд шаг может повториться, прежде чем проба признает, что застряла. */
	private static final int MAX_REPEATS = 60;

	/** Шаг пробы: {@code true} — шаг выполнен, {@code false} — повторить его через паузу. */
	private interface Action {
		boolean run(Minecraft client);
	}

	/** Один шаг пробы: что сделать и сколько тиков подождать перед этим. */
	private record Step(String title, int delay, Action action) {
	}

	private static final List<Step> STEPS = new ArrayList<>();

	private static boolean enabled;
	private static int ticks;
	private static int step;
	private static int repeats;
	private static int loggedStep = -1;
	private static boolean worldRequested;
	private static boolean finished;

	/** Что стояло в настройках до пробы: игру нельзя оставлять с чужими настройками. */
	private static Boolean pauseOnLostFocusBefore;

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
		buildSteps();
		ClientTickEvents.END_CLIENT_TICK.register(DiagShopProbe::tick);
	}

	/** Весь путь заказчика по шагам: свежий запуск → /reload → язык → новый мир → перезаход. */
	private static void buildSteps() {
		STEPS.clear();

		// 1. Как играет заказчик: свежий запуск, клавиша I, «Купить», «Особые предметы».
		addRepeat("войти в мир «" + WORLD + "»", STEP_TICKS, client -> enterWorld(client, WORLD));
		addOpenShop("свежий запуск");
		add("состав сетки разделов (что видит игрок)", STEP_TICKS, DiagShopProbe::dumpGrid);
		addSection("Особые предметы", SPECIAL_ICON_SLOT, "probe-1-special-fresh.png", true);

		// 2. «Книги зачарований»: наших книг там быть не должно — они продаются в «Особых предметах».
		addBackToGrid();
		addSection("Книги зачарований", BOOKS_ICON_SLOT, "probe-2-books.png", false);

		// 3. /reload: окно магазина от перечитки данных закрывается, поэтому открываем его заново
		//    и проверяем, что раздел собрался так же (раньше проба этого не делала и «теряла» окно).
		add("команда /reload", LONG_TICKS, DiagShopProbe::reload);
		add("что открыто сразу после /reload", LONG_TICKS,
				client -> checkSection(client, "после /reload", null, false));
		addOpenShop("после /reload");
		add("состав сетки разделов (после /reload)", STEP_TICKS, DiagShopProbe::dumpGrid);
		addSection("Особые предметы", SPECIAL_ICON_SLOT, "probe-3-special-reload.png", true);
		addBackToGrid();

		// 4. Язык: в русском и в английском раздел должен выглядеть одинаково (меняются только названия).
		//    Смена языка перечитывает ресурсы клиента — окно тоже закрывается, открываем заново.
		add("переключить язык на ru_ru", LONG_TICKS, client -> switchLanguage(client, "ru_ru"));
		addOpenShop("язык ru_ru");
		add("состав сетки разделов (ru_ru)", STEP_TICKS, DiagShopProbe::dumpGrid);
		addSection("Особые предметы", SPECIAL_ICON_SLOT, "probe-4-special-ru.png", true);
		addBackToGrid();
		add("вернуть язык " + BASE_LANGUAGE, LONG_TICKS, client -> switchLanguage(client, BASE_LANGUAGE));
		addOpenShop("язык " + BASE_LANGUAGE);
		add("состав сетки разделов (обратно " + BASE_LANGUAGE + ")", STEP_TICKS, DiagShopProbe::dumpGrid);
		addSection("Особые предметы", SPECIAL_ICON_SLOT, "probe-4b-special-en.png", true);
		addBackToGrid();

		// 5. Новый мир: раздел должен собраться и в только что созданном мире.
		add("выйти в главное меню", LONG_TICKS, DiagShopProbe::quitToTitle);
		addRepeat("дождаться закрытия мира", STEP_TICKS, DiagShopProbe::waitForNoWorld);
		add("создать новый мир «" + FRESH_WORLD + "»", LONG_TICKS, DiagShopProbe::createFreshWorld);
		addRepeat("дождаться нового мира", STEP_TICKS, client -> waitForWorld(client, FRESH_WORLD));
		addOpenShop("новый мир");
		addSection("Особые предметы", SPECIAL_ICON_SLOT, "probe-5-special-newworld.png", true);
		addBackToGrid();

		// 6. Перезаход в мир заказчика: выйти в меню и зайти снова.
		add("выйти в главное меню (из нового мира)", LONG_TICKS, DiagShopProbe::quitToTitle);
		addRepeat("дождаться закрытия мира", STEP_TICKS, DiagShopProbe::waitForNoWorld);
		addRepeat("открыть мир «" + WORLD + "» заново", LONG_TICKS, client -> enterWorld(client, WORLD));
		addRepeat("дождаться мира заказчика", STEP_TICKS, client -> waitForWorld(client, WORLD));
		addOpenShop("после перезахода");
		addSection("Особые предметы", SPECIAL_ICON_SLOT, "probe-6-special-reenter.png", true);
		addBackToGrid();

		// 7. Опыт с буром — он был в пробе раньше, оставляем как есть.
		add("опыт бура: 3 stone", LONG_TICKS, client -> drillRun(client, "drillrun 3 stone pickaxe"));
		add("опыт бура: 2 stone", LONG_TICKS, client -> drillRun(client, "drillrun 2 stone pickaxe"));
		add("опыт бура: 3 mixed pickaxe", LONG_TICKS, client -> drillRun(client, "drillrun 3 mixed pickaxe"));
		add("опыт бура: 3 mixed shovel", LONG_TICKS, client -> drillRun(client, "drillrun 3 mixed shovel"));

		// 8. Конец: сохраняем настройки и закрываем игру.
		add("проба закончена", STEP_TICKS, DiagShopProbe::finish);
	}

	/** Добавляет одноразовый шаг в конец списка. */
	private static void add(String title, int wait, Consumer<Minecraft> action) {
		STEPS.add(new Step(title, wait, client -> {
			action.accept(client);
			return true;
		}));
	}

	/** Добавляет шаг, который повторяется, пока не скажет, что готов (вход в мир, ожидание загрузки). */
	private static void addRepeat(String title, int wait, Action action) {
		STEPS.add(new Step(title, wait, action));
	}

	/**
	 * Открывает магазин с самого начала: просьба открыть рынок (как клавиша {@code I}), «Купить».
	 * Нужно перед каждым сценарием: {@code /reload} и смена языка закрывают окно магазина,
	 * а пауза по Esc — тем более.
	 */
	private static void addOpenShop(String label) {
		add("открыть рынок (" + label + ")", STEP_TICKS, DiagShopProbe::requestMarket);
		add("клик по «Купить» (" + label + ")", STEP_TICKS,
				client -> mouseClick(client, MarketplaceMenu.MAIN_SLOT_BUY, "«Купить»"));
		add("проверка окна «Купить» (" + label + ")", STEP_TICKS,
				client -> verifyWindow(client, WindowKind.BUY, MarketplaceMenu.MAIN_SLOT_BUY, "«Купить»"));
	}

	/**
	 * Открывает раздел магазина из сетки разделов: клик по иконке, проверка, что окно сменилось,
	 * затем содержимое раздела (с подведением итога и снимком экрана).
	 */
	private static void addSection(String title, int slot, String screenshot, boolean dumpSlots) {
		add("клик по иконке «" + title + "»", STEP_TICKS,
				client -> mouseClick(client, slot, "иконка «" + title + "»"));
		add("проверка окна списка: «" + title + "»", STEP_TICKS,
				client -> verifyWindow(client, WindowKind.BUY_LIST, slot, "раздел «" + title + "»"));
		add("содержимое раздела «" + title + "»", LONG_TICKS,
				client -> checkSection(client, title, screenshot, dumpSlots));
	}

	/** Возврат из списка товаров к сетке разделов (барьер в системной строке). */
	private static void addBackToGrid() {
		add("выход к сетке разделов", STEP_TICKS,
				client -> mouseClick(client, MarketplaceMenu.BUY_LIST_EXIT, "барьер «выход»"));
		add("проверка окна разделов", STEP_TICKS,
				client -> verifyWindow(client, WindowKind.BUY, MarketplaceMenu.BUY_LIST_EXIT, "барьер «выход»"));
	}

	private static void tick(Minecraft client) {
		// Проба гоняет игру сама по шагам, а окно игры легко теряет фокус (мы работаем
		// на этой же машине) — в одиночной игре это ставит её на паузу и закрывает окно
		// магазина. Поэтому пауза по потере фокуса на время пробы выключена, а в конце
		// настройка возвращается как была.
		if (pauseOnLostFocusBefore == null) {
			pauseOnLostFocusBefore = client.options.pauseOnLostFocus;
			client.options.pauseOnLostFocus = false;
		}

		if (finished) {
			// Даём игре время записать настройки и закрываемся, чтобы окно не мешало.
			ticks++;

			if (ticks > STEP_TICKS * 2) {
				Diag.log("закрываю игру");
				client.stop();
			}

			return;
		}

		ticks++;

		if (step >= STEPS.size()) {
			finish(client);
			return;
		}

		Step current = STEPS.get(step);

		if (ticks < current.delay()) {
			return;
		}

		ticks = 0;

		if (loggedStep != step) {
			loggedStep = step;
			Diag.log("шаг {}/{}: {}", step + 1, STEPS.size(), current.title());
		}

		boolean done = current.action().run(client);

		if (done) {
			// Шаг выполнен — идём дальше.
			step++;
			repeats = 0;
			return;
		}

		// Шаг попросил повториться (ждём мир, окно и так далее).
		repeats++;

		if (repeats > MAX_REPEATS) {
			Diag.log("ВНИМАНИЕ: шаг «{}» не завершился за {} попыток — прекращаю пробу",
					current.title(), MAX_REPEATS);
			finish(client);
		}
	}

	/** Опыт с буром: строит куб вокруг игрока и считает, что сломано. */
	private static void drillRun(Minecraft client, String arguments) {
		Diag.log("опыт бура: отправляю /marketdiag {}", arguments);
		client.player.connection.sendCommand("marketdiag " + arguments);
	}

	/** Шаг: то же, что нажатие клавиши I, — просим сервер открыть рынок. */
	private static void requestMarket(Minecraft client) {
		// Если открыт чужой экран (пауза по Esc, меню), он закрывает наше окно и мешает кликам:
		// закрываем его и работаем дальше так, будто игрок просто открыл рынок заново.
		Screen screen = client.gui.screen();

		if (screen != null && !(screen instanceof MarketplaceScreen)) {
			Diag.log("закрываю чужой экран {} (пауза или меню)", screen.getClass().getSimpleName());
			client.gui.setScreen(null);
		}

		Diag.log("прошу сервер открыть рынок (как клавиша I)");

		if (ClientPlayNetworking.canSend(OpenMarketplacePayload.TYPE)) {
			ClientPlayNetworking.send(new OpenMarketplacePayload());
		} else {
			Diag.log("  сервер не принимает пакет открытия рынка — проба не сработает");
		}
	}

	/** Входит в мир (открывает его сам, если мы в главном меню) и ждёт загрузки. */
	private static boolean enterWorld(Minecraft client, String worldId) {
		if (client.level != null && client.player != null) {
			worldRequested = false;
			return true;
		}

		if (worldRequested) {
			// Ждём загрузку: мир уже заказан, открывать его второй раз нельзя.
			return false;
		}

		worldRequested = true;
		Diag.log("мир не открыт — открываю «{}»", worldId);
		client.createWorldOpenFlows().openWorld(worldId, () -> Diag.log("не удалось открыть мир «{}»", worldId));
		return false;
	}

	/** Ждёт, пока мир откроется (шаг повторяется). */
	private static boolean waitForWorld(Minecraft client, String what) {
		if (client.level == null || client.player == null) {
			return false;
		}

		worldRequested = false;
		Diag.log("мир «{}» открыт, играю за {}", what, client.player.getName().getString());
		return true;
	}

	/** Выход в главное меню со штатным сохранением мира. */
	private static void quitToTitle(Minecraft client) {
		Diag.log("выхожу в главное меню (с сохранением мира)");
		client.disconnectWithSavingScreen();
	}

	/** Ждёт, пока мир закроется (шаг повторяется). */
	private static boolean waitForNoWorld(Minecraft client) {
		if (client.level != null || client.player != null) {
			return false;
		}

		worldRequested = false;
		Diag.log("мир закрыт, я в главном меню");
		return true;
	}

	/** Создаёт новый мир: он нужен, чтобы проверить раздел там, где данных мода ещё нет. */
	private static void createFreshWorld(Minecraft client) {
		Diag.log("создаю новый мир «{}» (папка {})", FRESH_WORLD_NAME, FRESH_WORLD);
		Screen parent = client.gui.screen();
		client.createWorldOpenFlows().createFreshLevel(FRESH_WORLD,
				new LevelSettings(FRESH_WORLD_NAME, GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, true,
						WorldDataConfiguration.DEFAULT),
				WorldOptions.defaultWithRandomSeed(),
				WorldPresets::createNormalWorldDimensions,
				parent == null ? new TitleScreen() : parent);
	}

	/** Команда /reload — как её делает игрок. */
	private static void reload(Minecraft client) {
		Diag.log("отправляю /reload");
		client.player.connection.sendCommand("reload");
	}

	/** Переключение языка игры: так проверяется, что раздел не зависит от локализации. */
	private static void switchLanguage(Minecraft client, String code) {
		Diag.log("переключаю язык на {} (был {})", code, client.options.languageCode);
		client.options.languageCode = code;
		client.getLanguageManager().setSelected(code);
		client.reloadResourcePacks();
	}

	/**
	 * Клик мышью по слоту открытого экрана — <b>через тот же путь, что у настоящей мыши</b>:
	 * {@code MouseHandler.onMove} ставит курсор в центр слота, {@code MouseHandler.onButton}
	 * нажимает и отпускает левую кнопку. Координаты считаются так, как их видит игрок: окно
	 * стоит по центру экрана, слот лежит внутри окна.
	 */
	private static void mouseClick(Minecraft client, int slotIndex, String what) {
		Screen screen = client.gui.screen();
		AbstractContainerMenu menu = client.player.containerMenu;
		Diag.log("клик по {} (слот {}), окно {}, экран {}", what, slotIndex,
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
		// Кнопки мыши в 26.3 нумеруются с единицы: 1 — левая, 2 — средняя, 3 — правая.
		client.mouseHandler.onButton(window.handle(), new MouseButtonInfo(1, 0), 1);
		client.mouseHandler.onButton(window.handle(), new MouseButtonInfo(1, 0), 0);
	}

	/**
	 * Проверяем, сменилось ли окно после клика. Если мышь клик не провела (например, слот отказался
	 * его отдавать), отправляем пакет клика напрямую — так видно, на каком шаге ломается путь игрока:
	 * на клике мышью или на стороне сервера.
	 */
	private static void verifyWindow(Minecraft client, WindowKind expected, int slotIndex, String what) {
		AbstractContainerMenu menu = client.player.containerMenu;
		Diag.log("после клика по {} открыто {}", what, kindOf(menu));

		if (menu instanceof MarketplaceMenu ours && ours.getKind() == expected) {
			Diag.log("  окно сменилось как надо — путь игрока работает");
			return;
		}

		Diag.log("  ВНИМАНИЕ: клик мышью не привёл к окну {} — шлю пакет клика напрямую", expected);
		sendClick(client, menu, slotIndex);
	}

	/** Пакет клика — ровно тот, который отправляет мышь. Только по нашему окну: чужой контейнер
	 *  (например, инвентарь после закрытия магазина) трогать нельзя — так можно утащить предметы. */
	private static void sendClick(Minecraft client, AbstractContainerMenu menu, int slotIndex) {
		if (!(menu instanceof MarketplaceMenu)) {
			Diag.log("  окно не наше ({}) — пакет клика не отправляю", kindOf(menu));
			return;
		}

		client.gameMode.handleContainerInput(menu.containerId, slotIndex, 0, ContainerInput.PICKUP, client.player);
	}

	/** Что игрок видит в сетке разделов: названия иконок и число товаров в описании. */
	private static void dumpGrid(Minecraft client) {
		AbstractContainerMenu menu = client.player.containerMenu;
		Diag.log("сетка разделов: окно {}", kindOf(menu));

		if (!(menu instanceof MarketplaceMenu ours) || ours.getKind() != WindowKind.BUY) {
			Diag.log("  ВНИМАНИЕ: открыто не окно разделов — сетку не показать");
			return;
		}

		for (int i = 0; i < ours.getKind().containerSlots(); i++) {
			var stack = ours.getSlot(i).getItem();

			if (stack.isEmpty()) {
				continue;
			}

			Diag.log("  слот {}: {} «{}»{}", i, Diag.itemId(stack), stack.getHoverName().getString(),
					Diag.loreOf(stack));
		}
	}

	/** Итог по открытому разделу: что реально в слотах и что об этом пишет диагностика. */
	private static void checkSection(Minecraft client, String title, String screenshot, boolean dumpSlots) {
		AbstractContainerMenu menu = client.player.containerMenu;
		Diag.log("раздел «{}»: открыто {}", title, kindOf(menu));

		if (!(menu instanceof MarketplaceMenu ours) || ours.getKind() != WindowKind.BUY_LIST) {
			Diag.log("  ВНИМАНИЕ: открыто не окно списка товаров");
			return;
		}

		// Строка, которую заказчик присылает, если снова пусто: сколько предметов доехало до окна.
		Diag.logShopWindow("КЛИЕНТ", ours);

		if (dumpSlots) {
			Diag.dumpMenu("КЛИЕНТ: слоты окна «" + title + "»", menu);
		} else {
			// «Книги зачарований»: интересно только одно — нет ли там наших книг (их там быть не должно).
			int oursCount = 0;

			for (int i = 0; i < ours.tradeSlotCount(); i++) {
				if (Diag.isOurBook(ours.getTradeContainer().getItem(i))) {
					oursCount++;
				}
			}

			Diag.log("  наших книг в разделе «{}»: {} (в «Книгах зачарований» их быть не должно)",
					title, oursCount);
		}

		MinecraftServer server = client.getSingleplayerServer();

		if (server != null) {
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);

				if (player != null) {
					Diag.dumpMenu("СЕРВЕР: слоты того же окна", player.containerMenu);
				}
			});
		}

		if (screenshot != null) {
			Screenshot.grab(client.gameDirectory, screenshot, client.gameRenderer.mainRenderTarget(), 1,
					message -> Diag.log("снимок экрана: {}", message.getString()));
		}
	}

	/** Последний шаг: возвращаем настройки как были, сохраняем их и закрываем игру. */
	private static void finish(Minecraft client) {
		Diag.log("проба закончена");

		if (pauseOnLostFocusBefore != null) {
			client.options.pauseOnLostFocus = pauseOnLostFocusBefore;
		}

		client.options.save();
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
