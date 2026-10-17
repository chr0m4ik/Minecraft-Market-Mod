package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Заказ торговца: «принеси столько-то таких предметов — заплачу больше обычного».
 *
 * <p>Заказ хранится в сейве мира строками: id предмета, количество, надбавка к цене и признак
 * «выполнен». Заказы выдаются на игровой день ({@link TraderQuests}), поэтому выполненные до
 * конца дня остаются на месте с пометкой «выполнено» — так за один день нельзя сдать бесконечно
 * много, а на следующий день приходят новые.
 *
 * @param itemId id предмета, который просят принести (например, {@code minecraft:diamond});
 * @param count  сколько штук нужно;
 * @param bonus  во сколько раз больше базовой цены заплатит торговец (1.15 … 1.25);
 * @param done   заказ уже выполнен в текущем игровом дне
 */
public record TraderQuest(String itemId, int count, double bonus, boolean done) {
    /** Этот же заказ, но выполненный. */
    public TraderQuest markDone() {
        return new TraderQuest(this.itemId, this.count, this.bonus, true);
    }

    /**
     * Предмет заказа. Пусто — если в сейве лежит id, которого больше нет в игре
     * (например, мод с предметом удалили): такой заказ просто не показывается.
     */
    public Optional<Item> item() {
        try {
            return BuiltInRegistries.ITEM.getOptional(Identifier.parse(this.itemId));
        } catch (RuntimeException exception) {
            // Битую запись сейва не прячем: пишем в лог, что именно не так.
            MarketplaceMod.LOGGER.warn("В заказе торговца некорректный id предмета, запись пропущена: {}",
                    this.itemId);
            return Optional.empty();
        }
    }

    /** Предмет-иконка заказа: то, что просят принести (количество — не больше стака). */
    public ItemStack icon() {
        return item()
                .map(value -> new ItemStack(value, Math.max(1,
                        Math.min(this.count, Math.max(1, value.getDefaultMaxStackSize())))))
                .orElse(ItemStack.EMPTY);
    }

    /** Заказ можно показать и выполнить (id найден, количество и надбавка разумные). */
    public boolean isValid() {
        return this.count > 0 && this.bonus > 0.0 && item().isPresent();
    }
}
