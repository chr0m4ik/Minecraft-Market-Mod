package com.chr0m4ik.marketplace.price;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Микро-дрейф цен: фоновая «жизнь» базовой цены в пределах ±3%.
 *
 * <p>Смысл: у каждого предмета базовая цена чуть дышит — иногда её выгоднее продать,
 * иногда дешевле. Оповещений игроку нет, это просто фон.
 *
 * <p><b>К чему применяется.</b> Множитель берётся один раз — от предмета самого стака
 * (в {@link ItemPrices#priceOf(net.minecraft.world.item.ItemStack)}). Зачарования дрейфу не
 * подвержены. Содержимое контейнеров считается отдельными предметами (шалкер, бочка, мешок),
 * поэтому каждый предмет внутри «дышит» своим собственным множителем, а не множителем
 * контейнера: у шлякера и у лежащей в нём булавы графики дрейфа разные.
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
 * «для всех предметов каждый тик». Множитель обновляется раз в секунду: время округляется
 * до целой секунды, а результат на эту секунду кладётся в кэш, поэтому цена не дёргается
 * между кадрами (подсказки в окне считаются каждый кадр).
 *
 * <p><b>Настройка.</b> Амплитуда дрейфа лежит в {@code data/marketplace/economy.json}
 * ({@code drift_amplitude}) — заказчик может менять её без пересборки. Характер дрейфа задаётся
 * волнами {@link Wave}: добавь или убери строку в {@link #WAVES} — и он изменится. Сумма весов
 * волн нормализуется, поэтому итог всегда в пределах амплитуды, сколько бы волн ни было.
 */
public final class PriceDrift {
	/**
	 * Амплитуда дрейфа: доля от базовой цены, например 0.03 — это «база минус 3% … база плюс 3%».
	 * Значение берётся из {@code data/marketplace/economy.json} (поле {@code drift_amplitude}) —
	 * см. {@link com.chr0m4ik.marketplace.economy.EconomyConfig}. Именно на неё умножается итог,
	 * поэтому больше амплитуды значение быть не может.
	 */
	public static double amplitude() {
		return com.chr0m4ik.marketplace.economy.EconomyConfig.driftAmplitude();
	}

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
	 * Набор волн. Комбинация разных периодов и есть «разнообразие формул»: медленная волна на
	 * игровые сутки, средняя на треть суток (8 часов) и самая быстрая на два игровых часа.
	 * Сдвиг фазы у каждой волны свой, поэтому у предметов не бывает одинаковых графиков,
	 * и при этом каждый остаётся в пределах амплитуды. Сумма весов равна 1.
	 *
	 * <p>Периоды подобраны так, чтобы на дешёвых предметах цена не «дёргалась»: раньше самая
	 * быстрая волна шла круг за 1200 тиков (минута), и ±3% от копеечной цены выглядели как
	 * скачки. Теперь самая быстрая волна — 2400 тиков (2 минуты), средняя — 8000 (6,7 минуты).
	 */
	private static final Wave[] WAVES = {
			// Медленная волна: полный круг за игровые сутки (20 реальных минут).
			new Wave(0.50, 24_000, 0x9E37),
			// Средняя волна: полный круг за 8 000 тиков (6 минут 40 секунд).
			new Wave(0.30, 8_000, 0x85EB),
			// Самая быстрая волна: полный круг за 2 400 тиков (2 минуты).
			new Wave(0.20, 2_400, 0xC2B2),
	};

	/** Сколько тиков в игровой секунде: дрейф пересчитывается раз в секунду (см. {@link #update}). */
	private static final long TICKS_PER_SECOND = 20L;

	/** Сумма весов волн — на неё делим, чтобы итог не вылезал за амплитуду. */
	private static final double TOTAL_WEIGHT = totalWeight();

	/** Зерно мира. Пока сервер его не прислал, дрейфа нет (множитель 1.0). */
	private static volatile long worldSeed;

	/** Игровое время в тиках, по которому считается дрейф (округлено до секунды — см. update). */
	private static volatile long driftTime;

	/** Есть ли уже данные для расчёта (зерно и время). */
	private static volatile boolean ready;

	/**
	 * Кэш множителей на текущую секунду. Нужен, потому что цену предмета спрашивают очень часто
	 * (подсказки в окне считаются каждый кадр), а множитель внутри секунды не меняется.
	 * Клиент и сервер в одиночной игре делят эту статику, поэтому карта — потокобезопасная.
	 */
	private static final Map<Item, Double> CACHE = new ConcurrentHashMap<>();

	private PriceDrift() {
		// Класс только со статическими методами.
	}

	/**
	 * Обновляет данные для расчёта. На сервере вызывается каждый тик (зерно и время берутся
	 * из мира), на клиенте — когда пришёл пакет с сервера. Время округляется до целой секунды:
	 * внутри секунды множитель не меняется, поэтому цена не дёргается между кадрами.
	 *
	 * @param seed зерно мира
	 * @param gameTime игровое время в тиках
	 */
	public static void update(long seed, long gameTime) {
		long quantized = quantize(gameTime);

		if (ready && (worldSeed != seed || driftTime != quantized)) {
			CACHE.clear();
		}

		worldSeed = seed;
		driftTime = quantized;
		ready = true;
	}

	/** Округляет игровое время вниз до целой секунды: дрейф пересчитывается раз в 20 тиков. */
	private static long quantize(long gameTime) {
		return Math.floorDiv(gameTime, TICKS_PER_SECOND) * TICKS_PER_SECOND;
	}

	/** Забыть данные (выход из мира на клиенте): дрейф снова становится нейтральным. */
	public static void clear() {
		ready = false;
		worldSeed = 0L;
		driftTime = 0L;
		CACHE.clear();
	}

	/** Готовы ли данные: если нет, множитель равен 1.0 и ничего не ломается. */
	public static boolean isReady() {
		return ready;
	}

	/**
	 * Множитель микро-дрейфа для предмета: от {@code 1 - }{@link #amplitude()} до
	 * {@code 1 + }{@link #amplitude()}. Пока данных о мире нет — ровно 1.0.
	 */
	public static double multiplier(Item item) {
		if (!ready) {
			return 1.0;
		}

		Double cached = CACHE.get(item);

		if (cached != null) {
			return cached;
		}

		double value = 1.0 + drift(item, driftTime, worldSeed);
		CACHE.put(item, value);
		return value;
	}

	/**
	 * Тот же множитель, но для заданного момента времени и зерна — без изменения общего
	 * состояния. Нужен команде {@code /market drift <предмет> <тик>}: посмотреть цену
	 * на любой момент, не сбивая расчёт остальным. Время округляется так же, как в игре.
	 */
	public static double multiplierAt(Item item, long gameTime, long seed) {
		return 1.0 + drift(item, quantize(gameTime), seed);
	}

	/**
	 * Отклонение цены от базы (доля): всегда в пределах ±{@link #amplitude()}.
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

		return amplitude() * sum / TOTAL_WEIGHT;
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
