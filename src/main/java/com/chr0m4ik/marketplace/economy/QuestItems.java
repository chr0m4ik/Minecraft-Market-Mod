package com.chr0m4ik.marketplace.economy;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class QuestItems {
    private QuestItems() {
        // Класс только со статическими методами.
    }

    static int countIn(Inventory inventory, Item item) {
        int total = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (stack.is(item)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    static void removeFrom(Inventory inventory, Item item, int count) {
        int left = count;

        for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (!stack.is(item)) {
                continue;
            }

            int taken = Math.min(stack.getCount(), left);
            left -= taken;
            stack.shrink(taken);

            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            } else {
                inventory.setChanged();
            }
        }
    }
}
