package com.chr0m4ik.marketplace.economy;

import java.util.Locale;

public final class MoneyFormat {

	private static final double FULL_LIMIT = 10_000.0;

	private static final double[] TIERS = {
			1_000.0,
			1_000_000.0,
			1_000_000_000.0,
			1_000_000_000_000.0,
			1_000_000_000_000_000.0
	};

	private static final String[] SUFFIXES = {"K", "M", "B", "T", "Q"};

	private MoneyFormat() {

	}

	public static double round(double amount) {
		return Math.round(amount * 100.0) / 100.0;
	}

	public static String format(double amount) {
		double value = Math.abs(amount);

		if (value < FULL_LIMIT) {
			return String.format(Locale.ROOT, "%.2f$", amount);
		}

		// и берём первый, который «влезает» в сумму.
		for (int i = TIERS.length - 1; i >= 0; i--) {
			double scaled = value / TIERS[i];

			if (scaled < 1.0) {
				continue;
			}

			double rounded = Math.round(scaled * 10.0) / 10.0;

			// и показываем «1.0M$».
			int tier = (rounded >= 1000.0 && i + 1 < TIERS.length) ? i + 1 : i;
			double shown = Math.round((value / TIERS[tier]) * 10.0) / 10.0;
			String sign = amount < 0.0 ? "-" : "";

			return sign + String.format(Locale.ROOT, "%.1f", shown) + SUFFIXES[tier] + "$";
		}

		return String.format(Locale.ROOT, "%.2f$", amount);
	}
}
