package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;

import java.util.UUID;

/**
 * Виртуальный эндер-сундук: тот же самый сундук игрока, что и у блока, но открывается командой
 * {@code /ec} или {@code /enderchest} из любого места.
 *
 * <p><b>Один и тот же инвентарь.</b> Команда открывает обычный эндер-сундук игрока
 * ({@link net.minecraft.world.inventory.PlayerEnderChestContainer}) через ванильный
 * {@link ChestMenu#threeRows}, поэтому содержимое полностью совпадает с блоком эндер-сундука:
 * положил в блоке — видно в команде, и наоборот. Инвентарь, как и в ванильной игре, свой у каждого
 * игрока.
 *
 * <p><b>Покупка.</b> Первое открытие через команду показывает окно покупки: система стоит
 * {@value #PRICE} (1B). Покупка запоминается в сейве мира по UUID игрока, поэтому второй раз
 * команда сразу открывает сундук. У каждого игрока покупка своя — за других она не платит.
 * Блок эндер-сундука в мире при этом работает как обычно и бесплатно: покупка — это плата
 * за доступ на расстоянии.
 */
public final class VirtualEnderChest {
    /** Цена виртуальной системы: 1 000 000 000$ (решение заказчика). */
    public static final double PRICE = 1_000_000_000.0;

    /**
     * Чем закончилась покупка.
     *
     * @param bought куплен ли (в том числе если он уже был куплен раньше);
     * @param paid   сколько списали (0 — уже был куплен или креатив)
     */
    public record BuyResult(boolean bought, double paid) {
    }

    private VirtualEnderChest() {
        // Класс только со статическими методами.
    }

    /** Куплена ли система этому игроку. */
    public static boolean owns(MinecraftServer server, UUID playerId) {
        return MarketplaceEconomy.state(server).ownsEnderChest(playerId);
    }

    /**
     * Покупает систему игроку. В креативе покупка бесплатная (как и остальные покупки мода),
     * но доступ всё равно запоминается.
     */
    public static BuyResult buy(MinecraftServer server, ServerPlayer player) {
        MarketplaceState state = MarketplaceEconomy.state(server);
        UUID playerId = player.getUUID();

        if (state.ownsEnderChest(playerId)) {
            return new BuyResult(true, 0.0);
        }

        if (!player.isCreative() && !MarketplaceEconomy.trySpend(server, playerId, PRICE)) {
            return new BuyResult(false, 0.0);
        }

        state.grantEnderChest(playerId);

        double paid = player.isCreative() ? 0.0 : PRICE;
        MarketplaceMod.LOGGER.info("Игрок {} купил виртуальный эндер-сундук за {}",
                player.getScoreboardName(), MoneyFormat.format(paid));

        return new BuyResult(true, paid);
    }

    /**
     * Открывает игроку его настоящий эндер-сундук. Меню — ванильное, поэтому работают все обычные
     * действия со слотами, а содержимое совпадает с блоком.
     */
    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, owner) -> ChestMenu.threeRows(syncId, inventory,
                        owner.getEnderChestInventory()),
                Component.translatable("container.enderchest")));
    }
}
