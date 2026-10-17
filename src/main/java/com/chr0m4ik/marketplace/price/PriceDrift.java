package com.chr0m4ik.marketplace.price;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PriceDrift {

	public static double amplitude() {
		return com.chr0m4ik.marketplace.economy.EconomyConfig.driftAmplitude();
	}

	private record Wave(double weight, int period, int phaseSalt) {
	}

	private static final Wave[] WAVES = {

			new Wave(0.50, 24_000, 0x9E37),

			new Wave(0.30, 8_000, 0x85EB),

			new Wave(0.20, 2_400, 0xC2B2),
	};

	private static final long TICKS_PER_SECOND = 20L;

	private static final double TOTAL_WEIGHT = totalWeight();

	private static volatile long worldSeed;

	private static volatile long driftTime;

	private static volatile boolean ready;

	private static final Map<Item, Double> CACHE = new ConcurrentHashMap<>();

	private PriceDrift() {
		// Класс только со статическими методами.
	}

	public static void update(long seed, long gameTime) {
		long quantized = quantize(gameTime);

		if (ready && (worldSeed != seed || driftTime != quantized)) {
			CACHE.clear();
		}

		worldSeed = seed;
		driftTime = quantized;
		ready = true;
	}

	private static long quantize(long gameTime) {
		return Math.floorDiv(gameTime, TICKS_PER_SECOND) * TICKS_PER_SECOND;
	}

	public static void clear() {
		ready = false;
		worldSeed = 0L;
		driftTime = 0L;
		CACHE.clear();
	}

	public static boolean isReady() {
		return ready;
	}

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

	public static double multiplierAt(Item item, long gameTime, long seed) {
		return 1.0 + drift(item, quantize(gameTime), seed);
	}

	private static double drift(Item item, long gameTime, long seed) {
		double sum = 0.0;

		for (Wave wave : WAVES) {
			if (wave.period() <= 0) {
				continue;
			}

			int phase = (int) ((gameTime % wave.period()) + wave.phaseSalt() + itemPhase(item, wave, seed));
			sum += wave.weight() * Math.sin(2.0 * Math.PI * phase / wave.period());
		}

		return amplitude() * sum / TOTAL_WEIGHT;
	}

	private static int itemPhase(Item item, Wave wave, long seed) {
		int world = (int) seed;
		int mixed = world * 0x27D4EB2D + wave.phaseSalt();
		mixed ^= mixed >>> 15;
		mixed *= 0x2545F491;
		mixed ^= mixed >>> 13;

		return mixed + itemIdHash(item);
	}

	private static int itemIdHash(Item item) {

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

	public static long time() {
		return driftTime;
	}

	public static long seed() {
		return worldSeed;
	}
}
