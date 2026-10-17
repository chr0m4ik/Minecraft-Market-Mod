package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public record UrgentQuest(String itemId, int count, double reward, long remaining) {

    public UrgentQuest withRemaining(long ticks) {
        return new UrgentQuest(this.itemId, this.count, this.reward, Math.max(0L, ticks));
    }

    public Optional<Item> item() {
        try {
            return BuiltInRegistries.ITEM.getOptional(Identifier.parse(this.itemId));
        } catch (RuntimeException exception) {
            // MarketplaceMod.LOGGER.warn("В срочном заказе некорректный id предмета: {}", this.itemId);
            return Optional.empty();
        }
    }

    public ItemStack icon() {
        return item().map(value -> new ItemStack(value, Math.max(1,
                Math.min(this.count, Math.max(1, value.getDefaultMaxStackSize()))))).orElse(ItemStack.EMPTY);
    }

    public int stacks() {
        return Math.max(1, (this.count + 63) / 64);
    }
}
