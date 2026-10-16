package com.chr0m4ik.marketplace.event;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.Locale;
import java.util.Optional;

/**
 * Словарь названий крупных ивентов и мелкие помощники для текста.
 *
 * <p><b>Как добавить название.</b> Заказчику достаточно вписать строку в {@link Name}: id
 * (латиницей, для команды {@code /market event start <id>}), характер ({@link Direction}) и ключ
 * перевода вида {@code marketplace.event.*} в оба языковых файла. Больше нигде названия не
 * перечисляются: случайный выбор, команда {@code /market event list} и уведомления берут их отсюда.
 *
 * <p><b>Характер</b> влияет только на направление цены: «ажиотаж» и «золотая лихорадка» могут
 * цену только поднять, «обвал рынка», «чёрный день» и «кризис» — только опустить, а «распродажа»,
 * «дефицит» и «перекупщики» — в любую сторону (бросок монеты при запуске ивента).
 */
public final class MarketEvents {
	/** Куда ивент двигает цену. */
	public enum Direction {
		RISE("marketplace.event.direction.rise"),
		FALL("marketplace.event.direction.fall"),
		ANY("marketplace.event.direction.any");

		private final String titleKey;

		Direction(String titleKey) {
			this.titleKey = titleKey;
		}

		/** Подпись для команды: «растёт», «падает», «в любую сторону». */
		public Component title() {
			return Component.translatable(this.titleKey);
		}
	}

	/** Названия ивентов. Название — только текст и характер, на числа оно не влияет. */
	public enum Name {
		/** Распродажа: цены могут и упасть (скидки), и подрасти. */
		SALE("sale", Direction.ANY, "marketplace.event.sale"),
		/** Обвал рынка: цена падает. */
		CRASH("crash", Direction.FALL, "marketplace.event.crash"),
		/** Ажиотаж: цена растёт. */
		HYPE("hype", Direction.RISE, "marketplace.event.hype"),
		/** Дефицит: товара мало — цена может пойти в любую сторону. */
		SHORTAGE("shortage", Direction.ANY, "marketplace.event.shortage"),
		/** Чёрный день: цена падает. */
		BLACKDAY("blackday", Direction.FALL, "marketplace.event.blackday"),
		/** Золотая лихорадка: цена растёт. */
		GOLDRUSH("goldrush", Direction.RISE, "marketplace.event.goldrush"),
		/** Перекупщики: скупают товар — цена растёт. */
		RESELLERS("resellers", Direction.RISE, "marketplace.event.resellers"),
		/** Кризис: цена падает. */
		CRISIS("crisis", Direction.FALL, "marketplace.event.crisis");

		private final String id;
		private final Direction direction;
		private final String titleKey;

		Name(String id, Direction direction, String titleKey) {
			this.id = id;
			this.direction = direction;
			this.titleKey = titleKey;
		}

		/** id для команды: {@code /market event start sale}. */
		public String id() {
			return this.id;
		}

		/** Характер: что название делает с ценой. */
		public Direction direction() {
			return this.direction;
		}

		/** Ключ перевода названия. */
		public String titleKey() {
			return this.titleKey;
		}

		/** Название для чата, title на экране и подсказок: переводится на язык игрока. */
		public Component title() {
			return Component.translatable(this.titleKey);
		}

		/** Название по id из команды. Регистр не важен. */
		public static Optional<Name> byId(String id) {
			for (Name name : values()) {
				if (name.id.equalsIgnoreCase(id)) {
					return Optional.of(name);
				}
			}

			return Optional.empty();
		}

		/** Случайное название. */
		public static Name random(RandomSource random) {
			Name[] all = values();
			return all[random.nextInt(all.length)];
		}

		/** Все id через запятую — для подсказки, если в команде опечатка. */
		public static String ids() {
			StringBuilder text = new StringBuilder();

			for (Name name : values()) {
				if (text.length() > 0) {
					text.append(", ");
				}

				text.append(name.id);
			}

			return text.toString();
		}
	}

	private MarketEvents() {
		// Класс только со статическими методами.
	}

	/** Отклонение цены в процентах со знаком: «+12.4%». */
	public static String percentText(double multiplier) {
		return String.format(Locale.ROOT, "%+.1f%%", (multiplier - 1.0) * 100.0);
	}

	/**
	 * Длительность человеческим языком: «45 с» или «4 мин 12 с». Текст переводимый —
	 * сервер собирает компонент, а каждый игрок видит его на своём языке.
	 */
	public static Component duration(long ticks) {
		long seconds = Math.max(0L, ticks) / 20L;
		long minutes = seconds / 60L;
		long rest = seconds % 60L;

		if (minutes <= 0L) {
			return Component.translatable("marketplace.time.seconds", literal(rest));
		}

		return Component.translatable("marketplace.time.minutes", literal(minutes), literal(rest));
	}

	/** Тики в секунды (в секунде 20 тиков). */
	public static long seconds(long ticks) {
		return Math.max(0L, ticks) / 20L;
	}

	/** Секунды в тики: не меньше одного тика, иначе ивент закончился бы мгновенно. */
	public static long ticks(double seconds) {
		return Math.max(1L, Math.round(seconds * 20.0));
	}

	private static Component literal(long value) {
		return Component.literal(String.valueOf(value));
	}
}
