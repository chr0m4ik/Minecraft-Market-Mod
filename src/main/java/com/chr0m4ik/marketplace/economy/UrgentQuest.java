package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Срочный заказ: «пока идёт событие — принеси много таких-то предметов, заплачу втрое».
 *
 * <p>В отличие от обычных дневных заказов, срочный заказ — событие на несколько минут активного
 * времени (см. {@link UrgentQuests}). Награда фиксируется в момент появления, поэтому микро-дрейф
 * и крупные ивенты её уже не меняют: игрок видит в чате ровно ту сумму, которую получит.
 *
 * @param itemId    id предмета, который нужно принести;
 * @param count     сколько единиц нужно (обычно несколько стаков);
 * @param reward    сколько заплатят за весь заказ (зафиксировано при появлении);
 * @param remaining сколько тиков активного времени осталось
 */
public record UrgentQuest(String itemId, int count, double reward, long remaining) {
    /** Тот же заказ с новым остатком времени. */
    public UrgentQuest withRemaining(long ticks) {
        return new UrgentQuest(this.itemId, this.count, this.reward, Math.max(0L, ticks));
    }

    /** Предмет заказа. Пусто — если id больше не существует в игре. */
    public Optional<Item> item() {
        try {
            return BuiltInRegistries.ITEM.getOptional(Identifier.parse(this.itemId));
        } catch (RuntimeException exception) {
            MarketplaceMod.LOGGER.warn("В срочном заказе некорректный id предмета: {}", this.itemId);
            return Optional.empty();
        }
    }

    /** Иконка-предмет заказа (для подсказки в окне заданий). */
    public ItemStack icon() {
        return item().map(value -> new ItemStack(value, Math.max(1,
                Math.min(this.count, Math.max(1, value.getDefaultMaxStackSize()))))).orElse(ItemStack.EMPTY);
    }

    /** Сколько это в стаках — целое число вверх, для текста игроку. */
    public int stacks() {
        return Math.max(1, (this.count + 63) / 64);
    }
}
