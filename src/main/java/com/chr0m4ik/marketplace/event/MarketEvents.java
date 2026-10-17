package com.chr0m4ik.marketplace.event;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.Locale;
import java.util.Optional;

public final class MarketEvents {

	public enum Direction {
		RISE("marketplace.event.direction.rise"),
		FALL("marketplace.event.direction.fall"),
		ANY("marketplace.event.direction.any");

		private final String titleKey;

		Direction(String titleKey) {
			this.titleKey = titleKey;
		}

		public Component title() {
			return Component.translatable(this.titleKey);
		}
	}

	public enum Name {

		SALE("sale", Direction.ANY, "marketplace.event.sale"),

		CRASH("crash", Direction.FALL, "marketplace.event.crash"),

		HYPE("hype", Direction.RISE, "marketplace.event.hype"),

		SHORTAGE("shortage", Direction.ANY, "marketplace.event.shortage"),

		BLACKDAY("blackday", Direction.FALL, "marketplace.event.blackday"),

		GOLDRUSH("goldrush", Direction.RISE, "marketplace.event.goldrush"),

		RESELLERS("resellers", Direction.RISE, "marketplace.event.resellers"),

		CRISIS("crisis", Direction.FALL, "marketplace.event.crisis");

		private final String id;
		private final Direction direction;
		private final String titleKey;

		Name(String id, Direction direction, String titleKey) {
			this.id = id;
			this.direction = direction;
			this.titleKey = titleKey;
		}

		public String id() {
			return this.id;
		}

		public Direction direction() {
			return this.direction;
		}

		public String titleKey() {
			return this.titleKey;
		}

		public Component title() {
			return Component.translatable(this.titleKey);
		}

		public static Optional<Name> byId(String id) {
			for (Name name : values()) {
				if (name.id.equalsIgnoreCase(id)) {
					return Optional.of(name);
				}
			}

			return Optional.empty();
		}

		public static Name random(RandomSource random) {
			Name[] all = values();
			return all[random.nextInt(all.length)];
		}

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

	public static String percentText(double multiplier) {
		return String.format(Locale.ROOT, "%+.1f%%", (multiplier - 1.0) * 100.0);
	}

	public static Component duration(long ticks) {
		long seconds = Math.max(0L, ticks) / 20L;
		long minutes = seconds / 60L;
		long rest = seconds % 60L;

		if (minutes <= 0L) {
			return Component.translatable("marketplace.time.seconds", literal(rest));
		}

		return Component.translatable("marketplace.time.minutes", literal(minutes), literal(rest));
	}

	public static long seconds(long ticks) {
		return Math.max(0L, ticks) / 20L;
	}

	public static long ticks(double seconds) {
		return Math.max(1L, Math.round(seconds * 20.0));
	}

	private static Component literal(long value) {
		return Component.literal(String.valueOf(value));
	}
}
