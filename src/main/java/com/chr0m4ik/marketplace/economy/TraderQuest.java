package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public record TraderQuest(String itemId, int count, double bonus, boolean done, int delivered) {

	public TraderQuest(String itemId, int count, double bonus) {
		this(itemId, count, bonus, false, 0);
	}

	public TraderQuest markDone() {
		return new TraderQuest(this.itemId, this.count, this.bonus, true, this.count);
	}

	public TraderQuest withDelivered(int value) {
		int clamped = Math.max(0, Math.min(this.count, value));
		return new TraderQuest(this.itemId, this.count, this.bonus, this.done, clamped);
	}

	public int remaining() {
		return Math.max(0, this.count - this.delivered);
	}

	public boolean complete() {
		return this.done || this.delivered >= this.count;
	}

	public Optional<Item> item() {
		try {
			return BuiltInRegistries.ITEM.getOptional(Identifier.parse(this.itemId));
		} catch (RuntimeException exception) {

			// MarketplaceMod.LOGGER.warn("В заказе торговца некорректный id предмета, запись пропущена: {}",
					// this.itemId);
			return Optional.empty();
		}
	}

	public ItemStack icon() {
		return item()
				.map(value -> new ItemStack(value, Math.max(1,
						Math.min(this.count, Math.max(1, value.getDefaultMaxStackSize())))))
				.orElse(ItemStack.EMPTY);
	}

}
