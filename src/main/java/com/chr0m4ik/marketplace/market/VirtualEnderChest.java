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

public final class VirtualEnderChest {

    public static final double PRICE = 1_000_000_000.0;

    public record BuyResult(boolean bought, double paid) {
    }

    private VirtualEnderChest() {
        // Класс только со статическими методами.
    }

    public static boolean owns(MinecraftServer server, UUID playerId) {
        return MarketplaceEconomy.state(server).ownsEnderChest(playerId);
    }

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
        // MarketplaceMod.LOGGER.info("Игрок {} купил виртуальный эндер-сундук за {}",
                // player.getScoreboardName(), MoneyFormat.format(paid));

        return new BuyResult(true, paid);
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, owner) -> ChestMenu.threeRows(syncId, inventory,
                        owner.getEnderChestInventory()),
                Component.translatable("container.enderchest")));
    }
}
