package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public final class TraderPrices {

	public static final double DISCOUNT_PER_STACK = 0.005;

	public static final double MAX_DISCOUNT = 0.20;

	public static final double BONUS_PER_STACK = 0.001;

	public static final double MAX_BONUS = 0.10;

	private static final TraderPrices EMPTY = new TraderPrices(Map.of(), 0.0);

	private final Map<Item, Double> sold;
	private final double totalSold;

	private TraderPrices(Map<Item, Double> sold, double totalSold) {
		this.sold = sold;
		this.totalSold = totalSold;
	}

	public static TraderPrices empty() {
		return EMPTY;
	}

	public static TraderPrices of(Map<Item, Double> sold) {
		if (sold.isEmpty()) {
			return EMPTY;
		}

		double total = 0.0;

		for (double stacks : sold.values()) {
			total += stacks;
		}

		return new TraderPrices(Map.copyOf(sold), total);
	}

	public double soldStacks(Item item) {
		return sold.getOrDefault(item, 0.0);
	}

	public double bonus(Item item) {
		double others = Math.max(0.0, this.totalSold - soldStacks(item));
		return Math.min(MAX_BONUS, BONUS_PER_STACK * others);
	}

	public double price(Item item) {
		return ItemPrices.priceOf(item) * (1.0 + bonus(item));
	}

	public double price(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double base = ItemPrices.priceOf(stack);
		double itemsOnly = ItemPrices.unitPriceOf(stack) * stack.getCount();
		return base + itemsOnly * bonus(stack.getItem());
	}

	public double payout(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double normal = price(stack) * EconomyConfig.traderMultiplier();
		double perItem = ItemPrices.unitPriceOf(stack) * DISCOUNT_PER_STACK * soldStacks(stack.getItem());
		double discount = Math.min(perItem * stack.getCount(), normal * MAX_DISCOUNT);
		return Math.max(0.0, normal - discount);
	}

	public boolean isEmpty() {
		return sold.isEmpty();
	}

	public Map<Item, Double> soldMap() {
		return sold;
	}
}
