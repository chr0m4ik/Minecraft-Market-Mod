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

public final class TraderQuests {

    public static final int COUNT = QuestGroups.PER_DAY;

    public enum Status {

        DELIVERED,

        PARTIAL,

        NOT_ENOUGH,

        ALREADY_DONE,

        BROKEN
    }

    public record Delivery(Status status, TraderQuest quest, int missing, double paid, int taken) {
    }

    private TraderQuests() {
        // Класс только со статическими методами.
    }

    public static List<TraderQuest> forPlayer(MinecraftServer server, UUID playerId) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        long day = GameTime.day(server);

        if (state.questDay(playerId) != day || state.quests(playerId).isEmpty()) {
            List<TraderQuest> rolled = QuestGroups.rollDaily(server.overworld().getRandom(), COUNT);

            if (rolled.isEmpty()) {

                // MarketplaceMod.LOGGER.warn("Заказы торговца: не удалось подобрать предметы (проверьте quests.json)");
                return List.of();
            }

            state.setQuests(playerId, rolled);
            state.setQuestDay(playerId, day);
            // MarketplaceMod.LOGGER.info("Заказы торговца на игровой день {}: {}", day, describe(rolled));
        }

        return state.quests(playerId);
    }

    public static double rewardOf(TraderQuest quest) {
        return quest.item()
                .map(item -> MoneyFormat.round(ItemPrices.priceOf(new ItemStack(item))
                        * quest.count() * quest.bonus()))
                .orElse(0.0);
    }

    public static Delivery deliver(MinecraftServer server, ServerPlayer player, int index) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        UUID playerId = player.getUUID();
        List<TraderQuest> quests = forPlayer(server, playerId);

        if (index < 0 || index >= quests.size()) {
            return new Delivery(Status.BROKEN, null, 0, 0.0, 0);
        }

        TraderQuest quest = quests.get(index);

        if (quest.complete()) {
            return new Delivery(Status.ALREADY_DONE, quest, 0, 0.0, 0);
        }

        Optional<Item> item = quest.item();

        if (item.isEmpty()) {
            return new Delivery(Status.BROKEN, quest, 0, 0.0, 0);
        }

        int have = QuestItems.countIn(player.getInventory(), item.get());

        if (have <= 0) {
            return new Delivery(Status.NOT_ENOUGH, quest, quest.remaining(), 0.0, 0);
        }

        int taken = Math.min(have, quest.remaining());
        QuestItems.removeFrom(player.getInventory(), item.get(), taken);

        TraderQuest updated = quest.withDelivered(quest.delivered() + taken);
        List<TraderQuest> list = new ArrayList<>(quests);

        if (updated.complete()) {
            double paid = rewardOf(quest);
            MarketplaceEconomy.addBalance(server, playerId, paid);
            list.set(index, updated.markDone());
            state.setQuests(playerId, list);
            return new Delivery(Status.DELIVERED, updated.markDone(), 0, paid, taken);
        }

        list.set(index, updated);
        state.setQuests(playerId, list);
        return new Delivery(Status.PARTIAL, updated, updated.remaining(), 0.0, taken);
    }

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
