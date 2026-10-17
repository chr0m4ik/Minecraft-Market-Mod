package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Задания торговца: заказы на сдачу предметов.
 *
 * <p><b>Как это работает.</b> Каждый игровой день торговец выдаёт игроку {@value #COUNT} заказов
 * вида «принеси N таких-то предметов». Предметы, их количество и надбавка берутся из пула
 * {@link QuestGroups} ({@code data/marketplace/quests.json}): редкие предметы просят понемногу,
 * но платят за единицу щедрее — от ×1.1 до ×1.9 базовой цены. Обычная сдача торговцу даёт
 * {@code trader_multiplier} (0.75), поэтому заказы выгоднее обычной продажи.
 *
 * <p><b>Почему это не «печать денег».</b> Надбавка заказа ограничена сверху (см. {@link QuestGroups}:
 * не больше ×2.0), а купить тот же предмет в магазине стоит {@code buy_multiplier} (2.2) базовой
 * цены. Поэтому «купил в магазине — сдал в заказ» всегда в убыток. Плюс заказ выполняется один раз
 * за игровой день.
 *
 * <p><b>Где живёт.</b> Заказы и номер дня лежат в сейве мира ({@link MarketplaceState}), поэтому
 * перезапуск игры их не сбрасывает, а у каждого игрока в кооперативе свои заказы.
 */
public final class TraderQuests {
    /** Сколько заказов выдаётся игроку на игровой день. */
    public static final int COUNT = QuestGroups.PER_DAY;

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
     * выдаёт новые. Вызывается при открытии окна заданий.
     */
    public static List<TraderQuest> forPlayer(MinecraftServer server, UUID playerId) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        long day = GameTime.day(server);

        if (state.questDay(playerId) != day || state.quests(playerId).size() != COUNT) {
            List<TraderQuest> rolled = QuestGroups.rollDaily(server.overworld().getRandom(), COUNT);

            if (rolled.isEmpty()) {
                // Прайс или файл заданий не загрузился — заказов нет. Молчать нельзя: окно было бы пустым.
                MarketplaceMod.LOGGER.warn("Заказы торговца: не удалось подобрать предметы (проверьте quests.json)");
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

        int have = QuestItems.countIn(player.getInventory(), item.get());

        if (have < quest.count()) {
            return new Delivery(Status.NOT_ENOUGH, quest, quest.count() - have, 0.0);
        }

        QuestItems.removeFrom(player.getInventory(), item.get(), quest.count());

        double paid = rewardOf(quest);
        MarketplaceEconomy.addBalance(server, playerId, paid);

        List<TraderQuest> updated = new ArrayList<>(quests);
        updated.set(index, quest.markDone());
        state.setQuests(playerId, updated);

        return new Delivery(Status.DELIVERED, quest, 0, paid);
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
