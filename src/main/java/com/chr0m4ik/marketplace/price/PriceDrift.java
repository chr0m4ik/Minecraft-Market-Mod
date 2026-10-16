package com.chr0m4ik.marketplace.price;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Микро-дрейф цен: фоновая «жизнь» базовой цены в пределах ±5%.
 *
 * <p>Смысл: у каждого предмета базовая цена чуть дышит — иногда её выгоднее продать,
 * иногда дешевле. Оповещений игроку нет, это просто фон.
 *
 * <p>Как считается. Множитель — чистая функция от трёх вещей:
 * <ul>
 *     <li>id предмета (из него берутся сдвиги фаз);</li>
 *     <li>зерна мира (чтобы миры отличались друг от друга);</li>
 *     <li>игрового времени в тиках.</li>
 * </ul>
 * Состояние нигде не хранится: ничего не нужно писать в сейв, а клиент и сервер получают
 * одно и то же число. Чтобы формулы совпали, сервер раз в секунду присылает клиенту своё
 * игровое время и зерно мира (пакет {@code MarketplaceStatusPayload}) — клиент считает дрейф
 * по серверному времени, а не по своему.
 *
 * <p>Считается лениво — только когда у предмета спрашивают цену. Никаких пересчётов
 * «для всех предметов каждый тик».
 *
 * <p><b>Настройка.</b> Всё, что заказчик может захотеть поправить, собрано в константах
 * {@link #AMPLITUDE} и {@link Wave}: добавь или убери строку в {@link #WAVES} — и характер
 * дрейфа изменится. Сумма весов волн нормализуется, поэтому итог всегда в пределах амплитуды,
 * сколько бы волн ни было.
 */
public final class PriceDrift {
	/**
	 * Амплитуда дрейфа: доля от базовой цены. 0.05 — это «база минус 5% … база плюс 5%».
	 * Именно на неё умножается итог, поэтому больше амплитуды значение быть не может.
	 */
	public static final double AMPLITUDE = 0.05;

	/**
	 * Одна волна дрейфа.
	 *
	 * @param weight  вес волны: чем больше, тем сильнее она влияет на итог
	 * @param period  период в игровых тиках (20 тиков = 1 секунда, 24 000 тиков = игровые сутки)
	 * @param phaseSalt соль для сдвига фазы: разные соли — разные предметы «живут» по-разному
	 */
	private record Wave(double weight, int period, int phaseSalt) {
	}

	/**
	 * Набор волн. Комбинация разных периодов и есть «разнообразие формул»:
	 * медленная волна на игровые сутки, средняя на четверть суток (6 часов) и быстрые
	 * всплески на секунды. Сдвиг фазы у каждой волны свой, поэтому у предметов
	 * не бывает одинаковых графиков, и при этом каждый остаётся в пределах амплитуды.
	 */
	private static final Wave[] WAVES = {
			// Медленная волна: полный круг за игровые сутки (20 реальных минут).
			new Wave(0.50, 24_000, 0x9E37),
			// Средняя волна: полный круг за 6 игровых часов (5 реальных минут).
			new Wave(0.30, 6_000, 0x85EB),
			// Быстрые всплески: полный круг за минуту.
			new Wave(0.20, 1_200, 0xC2B2),
	};

	/** Сумма весов волн — на неё делим, чтобы итог не вылезал за амплитуду. */
	private static final double TOTAL_WEIGHT = totalWeight();

	/** Зерно мира. Пока сервер его не прислал, дрейфа нет (множитель 1.0). */
	private static volatile long worldSeed;

	/** Игровое время в тиках, по которому считается дрейф. */
	private static volatile long driftTime;

	/** Есть ли уже данные для расчёта (зерно и время). */
	private static volatile boolean ready;

	private PriceDrift() {
		// Класс только со статическими методами.
	}

	/**
	 * Обновляет данные для расчёта. На сервере вызывается при запросе цены (зерно и время
	 * берутся из мира), на клиенте — когда пришёл пакет с сервера.
	 *
	 * @param seed зерно мира
	 * @param gameTime игровое время в тиках
	 */
	public static void update(long seed, long gameTime) {
		worldSeed = seed;
		driftTime = gameTime;
		ready = true;
	}

	/** Забыть данные (выход из мира на клиенте): дрейф снова становится нейтральным. */
	public static void clear() {
		ready = false;
		worldSeed = 0L;
		driftTime = 0L;
	}

	/** Готовы ли данные: если нет, множитель равен 1.0 и ничего не ломается. */
	public static boolean isReady() {
		return ready;
	}

	/**
	 * Множитель микро-дрейфа для предмета: от {@code 1 - }{@link #AMPLITUDE} до
	 * {@code 1 + }{@link #AMPLITUDE}. Пока данных о мире нет — ровно 1.0.
	 */
	public static double multiplier(Item item) {
		if (!ready) {
			return 1.0;
		}

		return 1.0 + drift(item, driftTime, worldSeed);
	}

	/**
	 * Тот же множитель, но для заданного момента времени и зерна — без изменения общего
	 * состояния. Нужен команде {@code /market drift <предмет> <тик>}: посмотреть цену
	 * на любой момент, не сбивая расчёт остальным.
	 */
	public static double multiplierAt(Item item, long gameTime, long seed) {
		return 1.0 + drift(item, gameTime, seed);
	}

	/**
	 * Отклонение цены от базы (доля): всегда в пределах ±{@link #AMPLITUDE}.
	 * Чистая функция — именно её сравнивают сервер и клиент.
	 *
	 * <p>Считаем только целыми 32-битными числами ({@code int} и переполнение как у {@code int}):
	 * так числа получаются короткими и предсказуемыми, а проверочный скрипт
	 * {@code tools/verify-drift.js} повторяет ту же арифметику и сверяет результат.
	 */
	private static double drift(Item item, long gameTime, long seed) {
		double sum = 0.0;

		for (Wave wave : WAVES) {
			if (wave.period() <= 0) {
				continue; // защита от нуля в настройках: волна просто не участвует
			}

			// Каждая волна идёт своим ходом: сдвиг фазы свой и у предмета, и у волны.
			int phase = (int) ((gameTime % wave.period()) + wave.phaseSalt() + itemPhase(item, wave, seed));
			sum += wave.weight() * Math.sin(2.0 * Math.PI * phase / wave.period());
		}

		return AMPLITUDE * sum / TOTAL_WEIGHT;
	}

	/**
	 * Сдвиг фазы для конкретного предмета в конкретной волне. Одинаковый на сервере и на клиенте:
	 * зависит только от id предмета, зерна мира и номера волны. В сумме с игровым временем
	 * это и даёт «свою жизнь» каждому предмету.
	 */
	private static int itemPhase(Item item, Wave wave, long seed) {
		int world = (int) seed;
		int mixed = world * 0x27D4EB2D + wave.phaseSalt();
		mixed ^= mixed >>> 15;
		mixed *= 0x2545F491;
		mixed ^= mixed >>> 13;

		return mixed + itemIdHash(item);
	}

	/** Сдвиг фазы для конкретного предмета: одинаковый на сервере и на клиенте. */
	private static int itemIdHash(Item item) {
		// Берём id предмета, а не сам объект: id одинаков на сервере и на клиенте.
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return id.toString().hashCode() * 0x27D4EB2D;
	}

	private static double totalWeight() {
		double total = 0.0;

		for (Wave wave : WAVES) {
			total += wave.weight();
		}

		return total <= 0.0 ? 1.0 : total;
	}

	/** Сколько волн настроено — для команды проверки. */
	public static int waveCount() {
		return WAVES.length;
	}

	/** Наибольший период волны в тиках — для команды проверки. */
	public static int longestPeriod() {
		int longest = 0;

		for (Wave wave : WAVES) {
			longest = Math.max(longest, wave.period());
		}

		return longest;
	}

	/** Наименьший период волны в тиках — для команды проверки. */
	public static int shortestPeriod() {
		int shortest = Integer.MAX_VALUE;

		for (Wave wave : WAVES) {
			shortest = Math.min(shortest, wave.period());
		}

		return shortest == Integer.MAX_VALUE ? 0 : shortest;
	}

	/** Игровое время, по которому посчитан последний дрейф. */
	public static long time() {
		return driftTime;
	}

	/** Зерно мира, по которому посчитан дрейф. */
	public static long seed() {
		return worldSeed;
	}
}
