package com.chr0m4ik.marketplace.market;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Сет, созданный игроком: снимок его инвентаря, хотбара, экипировки и левой руки.
 *
 * <p>Такой сет попадает в магазин в раздел «Свои сеты» и виден всем игрокам мира.
 * Предметы у автора не забираются — создание сета это снимок, а не продажа.
 *
 * @param name       название сета (уникальное в мире, до {@value CustomKits#MAX_NAME} символов);
 * @param author     UUID создателя;
 * @param authorName ник создателя (для подсказки и списка);
 * @param shulkers   выдавать шлякерами ({@code true}) или сразу надеть и положить в инвентарь;
 * @param items      предметы снимка; у каждого запомнен, откуда он взят (инвентарь или экипировка)
 */
public record CustomKit(String name, UUID author, String authorName, boolean shulkers, List<Entry> items) {
    /** Откуда взят предмет: обычный слот инвентаря или слот экипировки. */
    public enum Source {
        /** Инвентарь или хотбар. */
        INVENTORY,
        /** Шлем. */
        HEAD,
        /** Нагрудник. */
        CHEST,
        /** Поножи. */
        LEGS,
        /** Ботинки. */
        FEET,
        /** Левая рука. */
        OFFHAND
    }

    /** Предмет снимка вместе с местом, откуда он взят. */
    public record Entry(ItemStack stack, Source source) {
    }

    public CustomKit {
        items = List.copyOf(items);
    }

    /** Сколько предметов в сете. */
    public int size() {
        return this.items.size();
    }
}
