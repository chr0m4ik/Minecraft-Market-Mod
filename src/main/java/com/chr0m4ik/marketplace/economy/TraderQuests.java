package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Задания торговца: заказы на сдачу предметов.
 *
 * <p><b>Как это работает.</b> Каждый игровой день торговец выдаёт игроку {@value #COUNT} заказа вида
 * «принеси N таких-то предметов». За выполнение он платит больше, чем за обычную сдачу: надбавка
 * {@value #CHEAP_BONUS}…{@value #RICH_BONUS} от базовой цены (обычная сдача даёт
 * {@code trader_multiplier} — 0.75 по умолчанию). Предметы берутся из инвентаря игрока.
 *
 * <p><b>Почему это не «печать денег».</b> Награда за заказ — не больше
 * {@value #RICH_BONUS} базовой цены, а купить тот же предмет в магазине стоит
 * {@code buy_multiplier} (2.2) базовой цены. Даже с микро-дрейфом и крупным ивентом
 * {@code 1.25 × 1.03 < 2.2 × 0.97}, поэтому «купил в магазине — сдал в заказ» всегда в убыток.
 * Дополнительно действует дневной предел: заказ выполняется один раз за игровой день.
 *
 * <p><b>Где живёт.</b> Заказы и номер дня лежат в сейве мира ({@link MarketplaceState}), поэтому
 * перезапуск игры их не сбрасывает, а у каждого игрока в кооперативе свои заказы.
 */
public final class TraderQuests {
    /** Сколько заказов выдаётся игроку на игровой день. */
    public static final int COUNT = 3;

    /** Надбавка за дешёвые предметы (цена меньше {@value #CHEAP_LIMIT}): их просят много. */
    private static final double CHEAP_BONUS = 1.15;

    /** Надбавка за средние предметы: просят несколько штук. */
    private static final double MID_BONUS = 1.20;

    /** Надбавка за дорогие предметы: просят считанные штуки, но платят щедрее. */
    private static final double RICH_BONUS = 1.25;

    /** Граница «дешёвый предмет», в базовых долларах. */
    private static final double CHEAP_LIMIT = 100.0;

    /** Граница «средний предмет», в базовых долларах. */
    private static final double MID_LIMIT = 10_000.0;

    /**
     * Дороже этого предметы в заказы не попадают: награда за них была бы слишком крупной,
     * а сами заказы — почти невыполнимыми.
     */
    private static final double MAX_ITEM_PRICE = 1_000_000.0;

    /** Чем закончилась попытка сдать заказ. */
    public enum Status {
        /** Предметы забраны, деньги начислены. */
        DELIVERED,
        /** В инвентаре не хватает предметов. */
        NOT_ENOUGH,
        /** Этот заказ уже выполнен сегодня. */
        ALREADY_DONE,
        /** Заказ повреждён (id предмета не найден в игре). */
        BROKEN
    }

    /**
     * Результат сдачи заказа.
     *
     * @param status  чем закончилось;
     * @param quest   заказ (может быть {@code null}, если заказа с таким номером нет);
     * @param missing сколько предметов не хватает (для {@link Status#NOT_ENOUGH});
     * @param paid    сколько заплатили (для {@link Status#DELIVERED})
     */
    public record Delivery(Status status, TraderQuest quest, int missing, double paid) {
    }

    private TraderQuests() {
        // Класс только со статическими методами.
    }

    /**
     * Заказы игрока на текущий игровой день. Если день сменился (или заказов ещё нет) —
     * выдаёт новые. Вызывается при открытии окон торговца и заданий.
     */
    public static List<TraderQuest> forPlayer(MinecraftServer server, UUID playerId) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        long day = GameTime.day(server);

        if (state.questDay(playerId) != day || state.quests(playerId).isEmpty()) {
            List<TraderQuest> rolled = roll(server.overworld().getRandom());

            if (rolled.isEmpty()) {
                // Прайс не загрузился — заказов нет. Молчать нельзя: иначе окно выглядит пустым без причины.
                MarketplaceMod.LOGGER.warn("Заказы торговца: не удалось подобрать предметы (пустой прайс?)");
                return List.of();
            }

            state.setQuests(playerId, rolled);
            state.setQuestDay(playerId, day);
            MarketplaceMod.LOGGER.info("Заказы торговца на игровой день {}: {}", day, describe(rolled));
        }

        return state.quests(playerId);
    }

    /** Сколько заплатит торговец за выполнение заказа (с учётом дрейфа и крупных ивентов). */
    public static double rewardOf(TraderQuest quest) {
        return quest.item()
                .map(item -> MoneyFormat.round(ItemPrices.priceOf(new ItemStack(item))
                        * quest.count() * quest.bonus()))
                .orElse(0.0);
    }

    /**
     * Сдать заказ: предметы забираются из инвентаря, деньги начисляются.
     * Заказ помечается выполненным до конца игрового дня.
     */
    public static Delivery deliver(MinecraftServer server, ServerPlayer player, int index) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        UUID playerId = player.getUUID();
        List<TraderQuest> quests = forPlayer(server, playerId);

        if (index < 0 || index >= quests.size()) {
            return new Delivery(Status.BROKEN, null, 0, 0.0);
        }

        TraderQuest quest = quests.get(index);

        if (quest.done()) {
            return new Delivery(Status.ALREADY_DONE, quest, 0, 0.0);
        }

        Optional<Item> item = quest.item();

        if (item.isEmpty()) {
            return new Delivery(Status.BROKEN, quest, 0, 0.0);
        }

        Inventory inventory = player.getInventory();
        int have = countIn(inventory, item.get());

        if (have < quest.count()) {
            return new Delivery(Status.NOT_ENOUGH, quest, quest.count() - have, 0.0);
        }

        removeFrom(inventory, item.get(), quest.count());

        double paid = rewardOf(quest);
        MarketplaceEconomy.addBalance(server, playerId, paid);

        List<TraderQuest> updated = new ArrayList<>(quests);
        updated.set(index, quest.markDone());
        state.setQuests(playerId, updated);

        return new Delivery(Status.DELIVERED, quest, 0, paid);
    }

    /** Сколько таких предметов лежит в инвентаре (совпадение по предмету, без учёта компонентов). */
    private static int countIn(Inventory inventory, Item item) {
        int total = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (stack.is(item)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    /** Убирает нужное количество предметов из инвентаря (вызывать только после проверки {@link #countIn}). */
    private static void removeFrom(Inventory inventory, Item item, int count) {
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

    /**
     * Подбирает новые заказы: разные предметы из прайса, которые можно получить в игре.
     * Чем дороже предмет, тем меньше просят — и тем выше надбавка.
     */
    private static List<TraderQuest> roll(RandomSource random) {
        List<Item> pool = new ArrayList<>();

        for (Item item : ItemPrices.buyableItems()) {
            double price = ItemPrices.priceOf(item);

            if (price > 0.0 && price < MAX_ITEM_PRICE) {
                pool.add(item);
            }
        }

        if (pool.size() < COUNT) {
            return List.of();
        }

        Set<Item> picked = new HashSet<>();
        List<TraderQuest> quests = new ArrayList<>(COUNT);

        while (quests.size() < COUNT && picked.size() < pool.size()) {
            Item item = pool.get(random.nextInt(pool.size()));

            if (!picked.add(item)) {
                continue;
            }

            double price = ItemPrices.priceOf(item);
            int maxStack = Math.max(1, item.getDefaultMaxStackSize());
            int min;
            int max;
            double bonus;

            if (price < CHEAP_LIMIT) {
                min = 32;
                max = 64;
                bonus = CHEAP_BONUS;
            } else if (price < MID_LIMIT) {
                min = 8;
                max = 32;
                bonus = MID_BONUS;
            } else {
                min = 1;
                max = 8;
                bonus = RICH_BONUS;
            }

            // Нестакающиеся предметы (меч, броня) просят поштучно — пачкой их и не бывает.
            int count = Math.min(min + random.nextInt(max - min + 1), maxStack);
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            quests.add(new TraderQuest(id, Math.max(1, count), bonus, false));
        }

        return List.copyOf(quests);
    }

    /** Заказы одной строкой для лога: «32 × minecraft:dirt (×1.15)». */
    private static String describe(List<TraderQuest> quests) {
        StringBuilder text = new StringBuilder();

        for (TraderQuest quest : quests) {
            if (text.length() > 0) {
                text.append(", ");
            }

            text.append(quest.count()).append(" × ").append(quest.itemId())
                    .append(" (×").append(quest.bonus()).append(')');
        }

        return text.toString();
    }
}
