package com.chr0m4ik.marketplace.market;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

public record CustomKit(String name, UUID author, String authorName, boolean shulkers, List<Entry> items) {

    public enum Source {

        INVENTORY,

        HEAD,

        CHEST,

        LEGS,

        FEET,

        OFFHAND
    }

    public record Entry(ItemStack stack, Source source) {
    }

    public CustomKit {
        items = List.copyOf(items);
    }

    public int size() {
        return this.items.size();
    }
}
