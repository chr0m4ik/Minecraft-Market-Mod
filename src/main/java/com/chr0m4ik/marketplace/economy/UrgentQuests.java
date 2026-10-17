package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.event.MarketEvents;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Срочный заказ — событие «принеси много таких-то предметов, пока не вышло время».
 *
 * <p><b>Как идёт.</b> Раз в {@code days_between} игровых дней (настройка в
 * {@code data/marketplace/quests.json}) на несколько минут активного времени появляется заказ:
 * в чат всем игрокам пишется предмет, количество и награда. Время считается по активному времени
 * мира (как у крупных ивентов): без игроков оно стоит, поэтому заказ не «сгорает» на паузе.
 *
 * <p><b>Кто получает.</b> Заказ один на мир, и сдать его может только первый успевший игрок —
 * после этого событие заканчивается для всех.
 *
 * <p><b>Сдача.</b> Кнопка сдачи — изумруд с блеском в окне заданий торговца; она видна только
 * пока заказ идёт. Предметы забираются из инвентаря, награда была зафиксирована в момент появления
 * заказа, поэтому дрейф и крупные ивенты её не меняют.
 *
 * <p><b>Защита от «купил — сдал».</b> Награда — {@code reward_multiplier} (3.0) базовой цены, что
 * дороже магазинной покупки (2.2). Поэтому, пока заказ идёт, этот предмет нельзя купить в магазине
 * ({@link #isShoppingBlocked}); иначе игрок получал бы гарантированную прибыль без риска.
 */
public final class UrgentQuests {
    /** Чем закончилась попытка сдать срочный заказ. */
    public enum Status {
        /** Предметы забраны, награда начислена, событие завершено. */
        DELIVERED,
        /** Сейчас срочного заказа нет. */
        NO_QUEST,
        /** В инвентаре не хватает предметов. */
        NOT_ENOUGH,
        /** Заказ повреждён (предмета больше нет в игре) — событие сброшено. */
        BROKEN
    }

    /** Результат сдачи срочного заказа. */
    public record Delivery(Status status, UrgentQuest quest, int missing, double paid) {
    }

    private UrgentQuests() {
        // Класс только со статическими методами.
    }

    /** Подписка на тик сервера: отсчёт активного времени и запуск новых заказов. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(UrgentQuests::tick);
    }

    /** Текущий срочный заказ (пусто — заказа нет). */
    public static Optional<UrgentQuest> current(MinecraftServer server) {
        return Optional.ofNullable(MarketplaceEconomy.state(server).urgentQuest());
    }

    /** Сколько секунд осталось у текущего заказа (0 — заказа нет). */
    public static long remainingSeconds(MinecraftServer server) {
        UrgentQuest quest = MarketplaceEconomy.state(server).urgentQuest();
        return quest == null ? 0L : quest.remaining() / 20L;
    }

    /**
     * Этот предмет сейчас нельзя покупать в магазине: он нужен для срочного заказа.
     * Так «купить в магазине и сдать заказ» не приносит денег.
     */
    public static boolean isShoppingBlocked(MinecraftServer server, Item item) {
        UrgentQuest quest = MarketplaceEconomy.state(server).urgentQuest();
        return quest != null && quest.itemId().equals(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /** Один тик сервера: отсчитывает время заказа и, если его нет, пытается начать новый. */
    private static void tick(MinecraftServer server) {
        if (server.overworld() == null) {
            return;
        }

        MarketplaceState state = MarketplaceEconomy.state(server);

        if (server.getPlayerList().getPlayers().isEmpty()) {
            // Без игроков активное время стоит: заказ не истекает и новый не появляется.
            return;
        }

        UrgentQuest active = state.urgentQuest();

        if (active != null) {
            long left = active.remaining() - 1L;

            if (left > 0L) {
                state.setUrgentQuest(active.withRemaining(left));
            } else {
                expire(server, state, active);
            }

            return;
        }

        long day = GameTime.day(server);

        if (state.urgentLastDay() == Long.MIN_VALUE) {
            // Первый день мира: отсчитываем от него, чтобы заказ не падал сразу на старте.
            state.setUrgentLastDay(day);
            return;
        }

        if (day - state.urgentLastDay() < QuestGroups.urgent().daysBetween()) {
            return;
        }

        startNow(server).ifPresent(started -> MarketplaceMod.LOGGER.info(
                "Срочный заказ: {} × {} за {}, длительность {} с активного времени",
                started.itemId(), started.count(), started.reward(), MarketEvents.seconds(started.remaining())));
    }

    /**
     * Начинает новый срочный заказ (в том числе вручную командой). Награда фиксируется сразу.
     *
     * @return запущенный заказ или пусто, если подходящих предметов не нашлось
     */
    public static Optional<UrgentQuest> startNow(MinecraftServer server) {
        MarketplaceState state = MarketplaceEconomy.state(server);

        if (state.urgentQuest() != null) {
            return Optional.empty();
        }

        Optional<UrgentQuest> rolled = roll(server.overworld().getRandom());

        if (rolled.isEmpty()) {
            MarketplaceMod.LOGGER.warn("Срочный заказ: не удалось подобрать предмет (пустой прайс?)");
            return Optional.empty();
        }

        UrgentQuest quest = rolled.get();
        state.setUrgentQuest(quest);
        state.setUrgentLastDay(GameTime.day(server));
        announceStart(server, quest);
        return Optional.of(quest);
    }

    /** Отменяет текущий срочный заказ (команда или сброс битой записи). */
    public static Optional<UrgentQuest> stop(MinecraftServer server, boolean announce) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        UrgentQuest quest = state.urgentQuest();

        if (quest == null) {
            return Optional.empty();
        }

        state.clearUrgentQuest();

        if (announce) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.translatable("marketplace.urgent.stopped")
                        .withStyle(ChatFormatting.GRAY));
            }
        }

        return Optional.of(quest);
    }

    /** Сдать срочный заказ: предметы из инвентаря, деньги на счёт, событие завершается. */
    public static Delivery deliver(MinecraftServer server, ServerPlayer player) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        UrgentQuest quest = state.urgentQuest();

        if (quest == null) {
            return new Delivery(Status.NO_QUEST, null, 0, 0.0);
        }

        Optional<Item> item = quest.item();

        if (item.isEmpty()) {
            // Молча оставлять нельзя: заказ ссылается на несуществующий предмет. Сбрасываем событие.
            MarketplaceMod.LOGGER.error("Срочный заказ ссылается на неизвестный предмет {} — заказ сброшен",
                    quest.itemId());
            state.clearUrgentQuest();
            return new Delivery(Status.BROKEN, quest, 0, 0.0);
        }

        int have = QuestItems.countIn(player.getInventory(), item.get());

        if (have < quest.count()) {
            return new Delivery(Status.NOT_ENOUGH, quest, quest.count() - have, 0.0);
        }

        QuestItems.removeFrom(player.getInventory(), item.get(), quest.count());

        double paid = MoneyFormat.round(quest.reward());
        MarketplaceEconomy.addBalance(server, player.getUUID(), paid);
        state.clearUrgentQuest();

        player.sendSystemMessage(Component.translatable("marketplace.urgent.delivered",
                quest.icon().getHoverName(),
                Component.literal(String.valueOf(quest.count())),
                Component.literal(MoneyFormat.format(paid))).withStyle(ChatFormatting.GREEN));

        announceDone(server, player, quest);
        MarketplaceMod.LOGGER.info("Срочный заказ выполнен игроком {}: {} × {} за {}",
                player.getScoreboardName(), quest.itemId(), quest.count(), paid);

        return new Delivery(Status.DELIVERED, quest, 0, paid);
    }

    /** Случайный срочный заказ: предмет до {@code max_item_price}, количество в стаках, награда ×N. */
    private static Optional<UrgentQuest> roll(RandomSource random) {
        QuestGroups.UrgentSettings settings = QuestGroups.urgent();

        if (settings.maxReward() <= 0.0 || settings.rewardMultiplier() <= 1.0) {
            return Optional.empty();
        }

        int stacks = settings.stacksMin()
                + random.nextInt(Math.max(1, settings.stacksMax() - settings.stacksMin() + 1));
        int count = stacks * 64;

        // Предмет подбираем так, чтобы награда не превысила потолок даже без обрезки.
        double priceByReward = settings.maxReward() / (count * settings.rewardMultiplier());
        double priceLimit = Math.min(settings.maxItemPrice(), priceByReward);

        List<Item> pool = new ArrayList<>();

        for (Item item : ItemPrices.buyableItems()) {
            double price = ItemPrices.priceOf(item);

            if (price > 0.0 && price <= priceLimit) {
                pool.add(item);
            }
        }

        if (pool.isEmpty()) {
            return Optional.empty();
        }

        Item item = pool.get(random.nextInt(pool.size()));
        double reward = Math.min(settings.maxReward(),
                MoneyFormat.round(ItemPrices.priceOf(new ItemStack(item)) * count * settings.rewardMultiplier()));
        long duration = MarketEvents.ticks(settings.secondsMin()
                + random.nextInt(Math.max(1, settings.secondsMax() - settings.secondsMin() + 1)));

        return Optional.of(new UrgentQuest(BuiltInRegistries.ITEM.getKey(item).toString(), count,
                reward, duration));
    }

    /** Истёк срок: заказ убирается, всем игрокам приходит строка. */
    private static void expire(MinecraftServer server, MarketplaceState state, UrgentQuest quest) {
        state.clearUrgentQuest();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.translatable("marketplace.urgent.expired",
                    quest.icon().getHoverName(),
                    Component.literal(String.valueOf(quest.count()))).withStyle(ChatFormatting.RED));
        }

        MarketplaceMod.LOGGER.info("Срочный заказ истёк: {} × {}", quest.itemId(), quest.count());
    }

    /** Объявление о новом заказе: шапка, предмет с количеством, награда, время и подсказка. */
    private static void announceStart(MinecraftServer server, UrgentQuest quest) {
        Component itemName = quest.icon().getHoverName();
        Component amount = Component.literal(quest.count() + " (" + quest.stacks() + " стаков)");

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.translatable("marketplace.urgent.header")
                    .withStyle(ChatFormatting.GOLD));
            player.sendSystemMessage(Component.translatable("marketplace.urgent.line", itemName, amount)
                    .withStyle(ChatFormatting.YELLOW));
            player.sendSystemMessage(Component.translatable("marketplace.urgent.reward",
                    Component.literal(MoneyFormat.format(quest.reward()))).withStyle(ChatFormatting.GREEN));
            player.sendSystemMessage(Component.translatable("marketplace.urgent.time",
                    MarketEvents.duration(quest.remaining())).withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.translatable("marketplace.urgent.hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Сообщение всем: заказ выполнил такой-то игрок. */
    private static void announceDone(MinecraftServer server, ServerPlayer winner, UrgentQuest quest) {
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        UUID winnerId = winner.getUUID();

        for (ServerPlayer player : players) {
            if (player.getUUID().equals(winnerId)) {
                // Победителю уже сказали сумму — повторять не нужно.
                continue;
            }

            player.sendSystemMessage(Component.translatable("marketplace.urgent.done",
                    Component.literal(winner.getScoreboardName()),
                    quest.icon().getHoverName(),
                    Component.literal(String.valueOf(quest.count()))).withStyle(ChatFormatting.GOLD));
        }
    }
}
