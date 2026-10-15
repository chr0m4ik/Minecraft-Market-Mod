package com.chr0m4ik.marketplace.economy;

import java.util.Locale;

/**
 * Приводит суммы денег к красивому виду и округляет их до копеек.
 *
 * <p>Правила показа (из AGENTS.md §5.1):
 * <ul>
 *     <li>до 10 000 — полное число с копейками: {@code 0.00$}, {@code 9999.99$};</li>
 *     <li>дальше — сокращение с одной цифрой после точки: {@code 10.0K$}, {@code 1.5M$},
 *     {@code 2.0B$}, {@code 3.0T$}, {@code 4.0Q$}.</li>
 * </ul>
 */
public final class MoneyFormat {
	/** До этой суммы деньги показываются полностью, без сокращений. */
	private static final double FULL_LIMIT = 10_000.0;

	/** Множители для сокращений: тысячи, миллионы, миллиарды, триллионы, квадриллионы. */
	private static final double[] TIERS = {
			1_000.0,
			1_000_000.0,
			1_000_000_000.0,
			1_000_000_000_000.0,
			1_000_000_000_000_000.0
	};

	/** Буквы для тех же множителей, в том же порядке. */
	private static final String[] SUFFIXES = {"K", "M", "B", "T", "Q"};

	private MoneyFormat() {
		// Класс только со статическими методами, создавать его объекты не нужно.
	}

	/** Округляет сумму до копеек (2 знака после точки). */
	public static double round(double amount) {
		return Math.round(amount * 100.0) / 100.0;
	}

	/** Превращает сумму в строку для показа игроку. */
	public static String format(double amount) {
		double value = Math.abs(amount);

		if (value < FULL_LIMIT) {
			return String.format(Locale.ROOT, "%.2f$", amount);
		}

		// Идём от самого большого множителя к самому маленькому
		// и берём первый, который «влезает» в сумму.
		for (int i = TIERS.length - 1; i >= 0; i--) {
			double scaled = value / TIERS[i];

			if (scaled < 1.0) {
				continue;
			}

			double rounded = Math.round(scaled * 10.0) / 10.0;
			// Особый случай: 999 950 при делении на 1000 даёт 999.95, что после округления
			// превратилось бы в «1000.0K$». В таком случае поднимаемся на множитель выше
			// и показываем «1.0M$».
			int tier = (rounded >= 1000.0 && i + 1 < TIERS.length) ? i + 1 : i;
			double shown = Math.round((value / TIERS[tier]) * 10.0) / 10.0;
			String sign = amount < 0.0 ? "-" : "";

			return sign + String.format(Locale.ROOT, "%.1f", shown) + SUFFIXES[tier] + "$";
		}

		return String.format(Locale.ROOT, "%.2f$", amount);
	}
}
