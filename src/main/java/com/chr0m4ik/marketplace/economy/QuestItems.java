package com.chr0m4ik.marketplace.economy;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Сколько предметов у игрока в инвентаре и как их забрать — общее для обычных заказов
 * ({@link TraderQuests}) и срочных ({@link UrgentQuests}).
 *
 * <p>Сравниваем по предмету, без учёта компонентов: заказ не требует «именно этот зачарованный
 * меч», но и не даёт доплаты за компоненты — цена считается по базовой.
 */
final class QuestItems {
    private QuestItems() {
        // Класс только со статическими методами.
    }

    /** Сколько таких предметов лежит в инвентаре (основные слоты и хотбар). */
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

    /** Убирает нужное количество предметов. Вызывать только после проверки {@link #countIn}. */
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
