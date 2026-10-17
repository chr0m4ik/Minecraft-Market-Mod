package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.price.ItemPrices;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Свои сеты игроков: создание, удаление, цена, иконка и выдача.
 *
 * <p><b>Что это.</b> Игрок собирает набор (инвентарь, хотбар, экипировка, левая рука), вводит
 * {@code /market kit new <название> <true|false>} — и набор появляется в магазине в разделе
 * «Свои сеты», видный всем игрокам мира. Предметы у автора остаются: это снимок, а не продажа.
 *
 * <p><b>Цена.</b> Как у обычных наборов ({@link KitCatalog}): сумма цен содержимого ×
 * {@code custom_kit_markup} (по умолчанию 1.2, настройка в {@code economy.json}) плюс цена
 * шлякеров, если сет выдаётся ими. Значит, купить набор дешевле, чем те же предметы по одному
 * в магазине (там множитель 2.2), — это осознанная скидка за объём, как у готовых наборов.
 *
 * <p><b>Выдача.</b> {@code true} — сет приходит шлякерами (1 шлякер на каждые 27 предметов);
 * {@code false} — предметы сразу раскладываются: броня надевается, левая рука заполняется,
 * остальное идёт в инвентарь (что не влезет — падает под игроком).
 */
public final class CustomKits {
    /** Сколько своих сетов может быть в мире. */
    public static final int LIMIT = 30;

    /** Предельная длина названия. */
    public static final int MAX_NAME = 32;

    /** Предметов в одном шлякере (9×3). */
    private static final int CELLS_PER_SHULKER = 27;

    /** Метка своего сета в {@code custom_data}: по ней магазин узнаёт набор при покупке. */
    private static final String KIT_TAG = "marketplace:custom_kit";

    /**
     * Цвета шлякеров-иконок. Цвет выбирается по названию сета, чтобы иконки различались,
     * а порядок совпадал с ванильным порядком цветов.
     */
    private static final String[] COLORS = {"white", "light_gray", "gray", "black", "brown", "red",
            "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"};

    /** Чем закончилось создание сета. */
    public enum CreateStatus {
        /** Сет создан. */
        CREATED,
        /** Сет с таким названием уже был у этого же игрока — обновлён. */
        REPLACED,
        /** В инвентаре и экипировке нет предметов. */
        EMPTY,
        /** Название пустое или слишком длинное. */
        BAD_NAME,
        /** Такое название уже занято другим игроком. */
        NAME_TAKEN,
        /** В мире уже максимум сетов. */
        LIMIT
    }

    /** Результат создания: статус и готовый сет (если создан). */
    public record CreateResult(CreateStatus status, CustomKit kit) {
    }

    /** Чем закончилось удаление. */
    public enum DeleteStatus {
        /** Сет удалён. */
        DELETED,
        /** Сета с таким названием нет. */
        MISSING,
        /** Сет чужой, а прав оператора нет. */
        NOT_OWNER
    }

    private CustomKits() {
        // Класс только со статическими методами.
    }

    /** Все свои сеты мира в порядке создания. */
    public static List<CustomKit> all(MarketplaceState state) {
        return List.copyOf(state.customKits().values());
    }

    /** Сет по названию. */
    public static Optional<CustomKit> byName(MarketplaceState state, String name) {
        return Optional.ofNullable(state.customKits().get(name));
    }

    /**
     * Создаёт (или обновляет свой) сет из текущего состояния игрока.
     *
     * @param rawName  название из команды (обрезаем пробелы по краям);
     * @param shulkers выдавать шлякерами ({@code true}) или сразу на тело
     */
    public static CreateResult create(MinecraftServer server, ServerPlayer player, String rawName,
            boolean shulkers) {
        String name = rawName == null ? "" : rawName.trim();

        if (name.isEmpty() || name.length() > MAX_NAME) {
            return new CreateResult(CreateStatus.BAD_NAME, null);
        }

        MarketplaceState state = MarketplaceEconomy.state(server);
        CustomKit existing = state.customKits().get(name);

        if (existing != null && !existing.author().equals(player.getUUID())) {
            return new CreateResult(CreateStatus.NAME_TAKEN, null);
        }

        List<CustomKit.Entry> items = snapshot(player);

        if (items.isEmpty()) {
            return new CreateResult(CreateStatus.EMPTY, null);
        }

        if (existing == null && state.customKits().size() >= LIMIT) {
            return new CreateResult(CreateStatus.LIMIT, null);
        }

        CustomKit kit = new CustomKit(name, player.getUUID(), player.getScoreboardName(), shulkers, items);
        state.putCustomKit(kit);

        MarketplaceMod.LOGGER.info("Свой сет «{}» {} игроком {}: {} предметов, цена {}, шлякеры: {}",
                name, existing == null ? "создан" : "обновлён", player.getScoreboardName(), kit.size(),
                MoneyFormat.format(priceOf(kit)), shulkers);

        return new CreateResult(existing == null ? CreateStatus.CREATED : CreateStatus.REPLACED, kit);
    }

    /**
     * Удаляет сет. Чужой сет может удалить только оператор.
     *
     * @param requester UUID игрока или {@code null}, если команда пришла из консоли (считается оператором)
     */
    public static DeleteStatus delete(MarketplaceState state, UUID requester, boolean operator, String name) {
        CustomKit kit = state.customKits().get(name);

        if (kit == null) {
            return DeleteStatus.MISSING;
        }

        boolean own = requester != null && kit.author().equals(requester);

        if (!own && !operator) {
            return DeleteStatus.NOT_OWNER;
        }

        state.removeCustomKit(name);
        MarketplaceMod.LOGGER.info("Свой сет «{}» удалён", name);
        return DeleteStatus.DELETED;
    }

    /** Снимок игрока: инвентарь и хотбар, затем экипировка и левая рука. */
    private static List<CustomKit.Entry> snapshot(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<CustomKit.Entry> items = new ArrayList<>();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (!stack.isEmpty()) {
                items.add(new CustomKit.Entry(stack.copy(), sourceOf(slot)));
            }
        }

        return items;
    }

    /**
     * Откуда взят предмет по номеру слота инвентаря. Экипировка в игровом инвентаре лежит
     * за основными слотами, и игра сама знает соответствие — берём его из
     * {@link Inventory#EQUIPMENT_SLOT_MAPPING}.
     */
    private static CustomKit.Source sourceOf(int slot) {
        EquipmentSlot equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);

        if (equipment == null) {
            return CustomKit.Source.INVENTORY;
        }

        return switch (equipment) {
            case HEAD -> CustomKit.Source.HEAD;
            case CHEST -> CustomKit.Source.CHEST;
            case LEGS -> CustomKit.Source.LEGS;
            case FEET -> CustomKit.Source.FEET;
            case OFFHAND -> CustomKit.Source.OFFHAND;
            default -> CustomKit.Source.INVENTORY;
        };
    }

    /** Номер слота инвентаря для слота экипировки ({@code -1} — такого нет). */
    private static int slotOf(EquipmentSlot equipment) {
        for (Int2ObjectMap.Entry<EquipmentSlot> entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
            if (entry.getValue() == equipment) {
                return entry.getIntKey();
            }
        }

        return -1;
    }

    /** Сколько шлякеров нужно, чтобы выдать сет (0 — сет выдаётся без шлякеров). */
    public static int shulkerCount(CustomKit kit) {
        return kit.shulkers() ? Math.max(1, (kit.size() + CELLS_PER_SHULKER - 1) / CELLS_PER_SHULKER) : 0;
    }

    /**
     * Цена сета: содержимое × {@code custom_kit_markup} плюс цена шлякеров, которыми он выдаётся.
     * Микро-дрейф и крупные ивенты учитываются — как у обычных наборов.
     */
    public static double priceOf(CustomKit kit) {
        double contents = 0.0;

        for (CustomKit.Entry entry : kit.items()) {
            contents += ItemPrices.priceOf(entry.stack());
        }

        double total = contents * EconomyConfig.customKitMarkup();

        if (kit.shulkers()) {
            total += shulkerCount(kit) * ItemPrices.priceOf(new ItemStack(Items.SHULKER_BOX));
        }

        return MoneyFormat.round(total);
    }

    /** Иконка сета для магазина: цветной шлякер с названием и описанием. */
    public static ItemStack iconOf(CustomKit kit) {
        ItemStack icon = shulkerOf(kit);
        icon.set(DataComponents.CUSTOM_NAME,
                Component.literal(kit.name()).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.AQUA)));
        icon.set(DataComponents.LORE, new ItemLore(loreOf(kit)));
        mark(icon, kit);
        return icon;
    }

    /** Цветной шлякер по названию сета: одинаковое название — всегда один и тот же цвет. */
    private static ItemStack shulkerOf(CustomKit kit) {
        int index = Math.floorMod(kit.name().hashCode(), COLORS.length);
        Identifier id = Identifier.parse("minecraft:" + COLORS[index] + "_shulker_box");

        return BuiltInRegistries.ITEM.getOptional(id)
                .map(ItemStack::new)
                .orElseGet(() -> new ItemStack(Items.SHULKER_BOX));
    }

    /** Описание сета: цена, автор, способ выдачи и начало содержимого. */
    private static List<Component> loreOf(CustomKit kit) {
        List<Component> lines = new ArrayList<>();
        lines.add(plain(Component.translatable("marketplace.kit.custom.price",
                MoneyFormat.format(priceOf(kit))).withStyle(ChatFormatting.GOLD)));
        lines.add(plain(Component.translatable("marketplace.kit.custom.author", kit.authorName())
                .withStyle(ChatFormatting.GRAY)));
        lines.add(plain(Component.translatable(kit.shulkers()
                ? "marketplace.kit.custom.shulkers" : "marketplace.kit.custom.body",
                Component.literal(String.valueOf(shulkerCount(kit)))).withStyle(ChatFormatting.LIGHT_PURPLE)));

        int shown = Math.min(kit.size(), 3);

        for (int index = 0; index < shown; index++) {
            ItemStack stack = kit.items().get(index).stack();
            lines.add(plain(Component.literal(" • ").append(stack.getHoverName())
                    .append(Component.literal(" ×" + stack.getCount()))
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }

        if (kit.size() > shown) {
            lines.add(plain(Component.translatable("marketplace.kit.custom.more",
                    Component.literal(String.valueOf(kit.size() - shown))).withStyle(ChatFormatting.DARK_GRAY)));
        }

        return lines;
    }

    /** Помечает иконку названием сета: по метке магазин понимает, что покупают набор. */
    private static void mark(ItemStack stack, CustomKit kit) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KIT_TAG, kit.name());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Название своего сета, которым помечен предмет (пустая строка — это не свой сет). */
    public static String nameOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);

        if (data == null) {
            return "";
        }

        return data.copyTag().getStringOr(KIT_TAG, "");
    }

    /** Свой сет, которым помечен предмет (на сервере: у клиента содержимого сетов нет). */
    public static Optional<CustomKit> kitOf(MarketplaceState state, ItemStack stack) {
        String name = nameOf(stack);
        return name.isEmpty() ? Optional.empty() : byName(state, name);
    }

    /**
     * Выдаёт сет игроку: шлякерами (по 27 предметов) или сразу на тело — броня надевается,
     * левая рука заполняется, остальное кладётся в инвентарь.
     */
    public static void deliver(ServerPlayer player, CustomKit kit) {
        if (kit.shulkers()) {
            deliverInShulkers(player, kit);
            return;
        }

        for (CustomKit.Entry entry : kit.items()) {
            equipOrGive(player, entry);
        }
    }

    /** Выдача шлякерами: предметы раскладываются по 27 в каждый шлякер. */
    private static void deliverInShulkers(ServerPlayer player, CustomKit kit) {
        List<ItemStack> stacks = new ArrayList<>(kit.size());

        for (CustomKit.Entry entry : kit.items()) {
            stacks.add(entry.stack());
        }

        int boxes = shulkerCount(kit);

        for (int box = 0; box < boxes; box++) {
            List<ItemStack> cells = new ArrayList<>(CELLS_PER_SHULKER);

            for (int cell = 0; cell < CELLS_PER_SHULKER; cell++) {
                int index = box * CELLS_PER_SHULKER + cell;
                cells.add(index < stacks.size() ? stacks.get(index).copy() : ItemStack.EMPTY);
            }

            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(cells));
            player.getInventory().placeItemBackInInventory(shulker, Prediction.SERVER_ONLY);
        }
    }

    /**
     * Выдача «на тело»: предмет из экипировки возвращается в свой слот, если тот свободен,
     * иначе — в инвентарь. Обычные предметы просто кладутся в инвентарь.
     */
    private static void equipOrGive(ServerPlayer player, CustomKit.Entry entry) {
        EquipmentSlot equipment = switch (entry.source()) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case OFFHAND -> EquipmentSlot.OFFHAND;
            case INVENTORY -> null;
        };

        if (equipment != null) {
            int slot = slotOf(equipment);

            if (slot >= 0 && player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().setItem(slot, entry.stack().copy());
                return;
            }
        }

        player.getInventory().placeItemBackInInventory(entry.stack().copy(), Prediction.SERVER_ONLY);
    }

    private static MutableComponent plain(MutableComponent text) {
        return text.withStyle(Style.EMPTY.withItalic(false));
    }
}
