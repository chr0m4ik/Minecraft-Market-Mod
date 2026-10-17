package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class QuestGroups {

    private static final String FILE_PATH = "/data/marketplace/quests.json";

    public static final int PER_DAY = 7;

    private static final double MAX_BONUS = 2.0;

    private static final int MAX_COUNT = 4096;
    private static final int MAX_SECONDS = 3600;

    private record Group(String id, int weight, int countMin, int countMax, double bonusMin, double bonusMax,
            List<TagKey<Item>> tags, List<Item> items) {
    }

    public record UrgentSettings(int daysBetween, int secondsMin, int secondsMax, int stacksMin, int stacksMax,
            double maxItemPrice, double rewardMultiplier, double maxReward) {
    }

    private static final UrgentSettings DEFAULT_URGENT = new UrgentSettings(2, 180, 600, 2, 6,
            10_000_000.0, 3.0, 100_000_000.0);

    private static List<Group> groups = List.of();
    private static UrgentSettings urgent = DEFAULT_URGENT;

    private static final Map<String, List<Item>> POOLS = new HashMap<>();
    private static boolean loadAttempted;
    private static boolean loaded;

    private QuestGroups() {
        // Класс только со статическими методами.
    }

    public static UrgentSettings urgent() {
        ensureLoaded();
        return urgent;
    }

    public static synchronized void ensureLoaded() {
        if (loadAttempted) {
            return;
        }

        loadAttempted = true;

        try (InputStream stream = QuestGroups.class.getResourceAsStream(FILE_PATH)) {
            if (stream == null) {
                MarketplaceMod.LOGGER.error("Не найден файл заданий {} — заказы торговца будут пустыми", FILE_PATH);
                return;
            }

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                List<Group> parsed = readGroups(root.getAsJsonArray("groups"));

                if (parsed.isEmpty()) {
                    MarketplaceMod.LOGGER.error("В файле заданий нет ни одной группы с предметами — заказов не будет");
                    return;
                }

                groups = List.copyOf(parsed);
                urgent = readUrgent(root.getAsJsonObject("urgent"));
                loaded = true;

                // MarketplaceMod.LOGGER.info("Задания торговца загружены: групп {}, заказов в день {}, срочный —"
                                // + " раз в {} дн., {}–{} с, {}–{} стаков, награда ×{} (не больше {})",
                        // groups.size(), PER_DAY, urgent.daysBetween(), urgent.secondsMin(), urgent.secondsMax(),
                        // urgent.stacksMin(), urgent.stacksMax(), urgent.rewardMultiplier(), urgent.maxReward());
            }
        } catch (IOException | RuntimeException exception) {

            MarketplaceMod.LOGGER.error("Не удалось прочитать файл заданий {}", FILE_PATH, exception);
        }
    }

    private static List<Group> readGroups(JsonArray array) {
        List<Group> result = new ArrayList<>();

        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            String id = object.has("id") ? object.get("id").getAsString() : "?";
            int weight = Math.max(1, number(object, "weight", 1, id));
            int countMin = number(object, "count_min", 16, id);
            int countMax = number(object, "count_max", 64, id);

            if (countMin > countMax) {
                MarketplaceMod.LOGGER.error("Задания: в группе «{}» count_min ({}) больше count_max ({}) — меняю местами",
                        id, countMin, countMax);
                int swap = countMin;
                countMin = countMax;
                countMax = swap;
            }

            countMin = clamp(countMin, 1, MAX_COUNT);
            countMax = clamp(countMax, countMin, MAX_COUNT);

            double bonusMin = decimal(object, "bonus_min", 1.1, id);
            double bonusMax = decimal(object, "bonus_max", 1.2, id);

            if (bonusMin > bonusMax) {
                MarketplaceMod.LOGGER.error("Задания: в группе «{}» bonus_min ({}) больше bonus_max ({}) — меняю местами",
                        id, bonusMin, bonusMax);
                double swap = bonusMin;
                bonusMin = bonusMax;
                bonusMax = swap;
            }

            if (bonusMax > MAX_BONUS) {
                MarketplaceMod.LOGGER.error("ЗАДАНИЯ ПОЗВОЛЯЮТ ПЕЧАТАТЬ ДЕНЬГИ: в группе «{}» bonus_max = {},"
                                + " а допустимо не больше {} (покупка в магазине стоит ×{}). Беру {}",
                        id, num(bonusMax), num(MAX_BONUS), num(EconomyConfig.buyMultiplier()), num(MAX_BONUS));
                bonusMax = MAX_BONUS;
                bonusMin = Math.min(bonusMin, bonusMax);
            }

            List<TagKey<Item>> tags = readTags(object, id);
            List<Item> items = readItems(object, id);

            if (tags.isEmpty() && items.isEmpty()) {
                MarketplaceMod.LOGGER.error("Задания: у группы «{}» нет ни тегов, ни предметов — пропускаю", id);
                continue;
            }

            result.add(new Group(id, weight, countMin, countMax, bonusMin, bonusMax, tags, items));
        }

        return result;
    }

    private static List<TagKey<Item>> readTags(JsonObject object, String groupId) {
        List<TagKey<Item>> tags = new ArrayList<>();

        if (!object.has("tags")) {
            return tags;
        }

        for (JsonElement element : object.getAsJsonArray("tags")) {
            String value = element.getAsString();

            if (!value.startsWith("#")) {
                // MarketplaceMod.LOGGER.warn("Задания: в группе «{}» тег записан без «#», пропускаю: {}",
                        // groupId, value);
                continue;
            }

            try {
                tags.add(TagKey.create(Registries.ITEM, Identifier.parse(value.substring(1))));
            } catch (RuntimeException exception) {
                // MarketplaceMod.LOGGER.warn("Задания: в группе «{}» некорректный тег, пропускаю: {}",
                        // groupId, value);
            }
        }

        return tags;
    }

    private static List<Item> readItems(JsonObject object, String groupId) {
        List<Item> items = new ArrayList<>();

        if (!object.has("items")) {
            return items;
        }

        for (JsonElement element : object.getAsJsonArray("items")) {
            String id = element.getAsString();
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));

            if (item.isEmpty() || !ItemPrices.isBuyable(item.get())) {
                // MarketplaceMod.LOGGER.warn("Задания: в группе «{}» неизвестный или недоступный предмет,"
                        // + " пропускаю: {}", groupId, id);
                continue;
            }

            items.add(item.get());
        }

        return items;
    }

    private static UrgentSettings readUrgent(JsonObject object) {
        if (object == null) {
            // MarketplaceMod.LOGGER.warn("В файле заданий нет блока «urgent» — беру значения по умолчанию");
            return DEFAULT_URGENT;
        }

        int daysBetween = Math.max(1, number(object, "days_between", DEFAULT_URGENT.daysBetween(), "urgent"));
        int secondsMin = clamp(number(object, "seconds_min", (int) DEFAULT_URGENT.secondsMin(), "urgent"),
                1, MAX_SECONDS);
        int secondsMax = clamp(number(object, "seconds_max", (int) DEFAULT_URGENT.secondsMax(), "urgent"),
                secondsMin, MAX_SECONDS);
        int stacksMin = clamp(number(object, "stacks_min", DEFAULT_URGENT.stacksMin(), "urgent"), 1, 64);
        int stacksMax = clamp(number(object, "stacks_max", DEFAULT_URGENT.stacksMax(), "urgent"),
                stacksMin, 64);
        double maxItemPrice = Math.max(0.0, decimal(object, "max_item_price", DEFAULT_URGENT.maxItemPrice(), "urgent"));
        double multiplier = decimal(object, "reward_multiplier", DEFAULT_URGENT.rewardMultiplier(), "urgent");
        double maxReward = Math.max(0.0, decimal(object, "max_reward", DEFAULT_URGENT.maxReward(), "urgent"));

        if (multiplier <= 1.0) {
            MarketplaceMod.LOGGER.error("ЗАДАНИЯ: у срочного заказа reward_multiplier = {}, а должен быть больше 1."
                            + " Беру {}", num(multiplier), num(DEFAULT_URGENT.rewardMultiplier()));
            multiplier = DEFAULT_URGENT.rewardMultiplier();
        }

        return new UrgentSettings(daysBetween, secondsMin, secondsMax, stacksMin, stacksMax,
                maxItemPrice, multiplier, maxReward);
    }

    public static List<TraderQuest> rollDaily(RandomSource random, int count) {
        ensureLoaded();

        if (groups.isEmpty()) {
            return List.of();
        }

        List<TraderQuest> result = new ArrayList<>(count);
        Set<Item> used = new HashSet<>();
        int attempts = 0;
        int limit = count * 40;

        while (result.size() < count && attempts++ < limit) {
            Group group = pickGroup(random);

            if (group == null) {
                break;
            }

            List<Item> pool = poolOf(group);

            if (pool.isEmpty()) {
                continue;
            }

            Item item = pool.get(random.nextInt(pool.size()));

            if (!used.add(item)) {
                continue;
            }

            int amount = group.countMin() + random.nextInt(group.countMax() - group.countMin() + 1);
            double bonus = round2(group.bonusMin()
                    + random.nextDouble() * (group.bonusMax() - group.bonusMin()));

            result.add(new TraderQuest(BuiltInRegistries.ITEM.getKey(item).toString(), amount, bonus));
        }

        return List.copyOf(result);
    }

    private static List<Item> poolOf(Group group) {
        return POOLS.computeIfAbsent(group.id(), id -> {
            Set<Item> pool = new HashSet<>(group.items());

            if (!group.tags().isEmpty()) {
                for (Item item : ItemPrices.buyableItems()) {
                    for (TagKey<Item> tag : group.tags()) {

                        if (item.builtInRegistryHolder().is(tag)) {
                            pool.add(item);
                            break;
                        }
                    }
                }
            }

            if (pool.isEmpty()) {
                MarketplaceMod.LOGGER.error("Задания: группа «{}» осталась без предметов — проверьте теги и id", id);
                return List.of();
            }

            // MarketplaceMod.LOGGER.info("Задания: группа «{}» — предметов {}", id, pool.size());
            return List.copyOf(pool);
        });
    }

    private static Group pickGroup(RandomSource random) {
        int total = 0;

        for (Group group : groups) {
            total += group.weight();
        }

        if (total <= 0) {
            return null;
        }

        int roll = random.nextInt(total);

        for (Group group : groups) {
            roll -= group.weight();

            if (roll < 0) {
                return group;
            }
        }

        return groups.get(groups.size() - 1);
    }

    private static int number(JsonObject object, String key, int fallback, String where) {
        JsonElement element = object.get(key);

        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            // MarketplaceMod.LOGGER.warn("Задания: в «{}» нет числа «{}» — беру {}", where, key, fallback);
            return fallback;
        }

        return element.getAsInt();
    }

    private static double decimal(JsonObject object, String key, double fallback, String where) {
        JsonElement element = object.get(key);

        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            // MarketplaceMod.LOGGER.warn("Задания: в «{}» нет числа «{}» — беру {}", where, key, fallback);
            return fallback;
        }

        return element.getAsDouble();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String num(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

}
