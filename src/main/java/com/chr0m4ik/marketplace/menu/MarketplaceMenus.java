package com.chr0m4ik.marketplace.menu;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.MenuType;

import java.util.EnumMap;
import java.util.Map;

public final class MarketplaceMenus {

	private static final Map<WindowKind, MenuType<MarketplaceMenu>> TYPES = new EnumMap<>(WindowKind.class);

	public static final MenuType<MarketplaceMenu> MAIN = register(WindowKind.MAIN, "main");
	public static final MenuType<MarketplaceMenu> TRADER = register(WindowKind.TRADER, "trader");
	public static final MenuType<MarketplaceMenu> SELL = register(WindowKind.SELL, "sell");
	public static final MenuType<MarketplaceMenu> CONFIRM = register(WindowKind.CONFIRM, "confirm");
	public static final MenuType<MarketplaceMenu> BUY_SLOT = register(WindowKind.BUY_SLOT, "buy_slot");
	public static final MenuType<MarketplaceMenu> BUY = register(WindowKind.BUY, "buy");
	public static final MenuType<MarketplaceMenu> BUY_LIST = register(WindowKind.BUY_LIST, "buy_list");
	public static final MenuType<MarketplaceMenu> BUY_ITEM = register(WindowKind.BUY_ITEM, "buy_item");

	public static final MenuType<MarketplaceMenu> QUESTS = register(WindowKind.QUESTS, "quests");

	public static final MenuType<MarketplaceMenu> ENDER_CHEST = register(WindowKind.ENDER_CHEST, "ender_chest");

	private MarketplaceMenus() {
		// Класс только со статическими методами.
	}

	private static MenuType<MarketplaceMenu> register(WindowKind kind, String name) {
		MenuType<MarketplaceMenu> type = Registry.register(
				BuiltInRegistries.MENU,
				MarketplaceMod.id(name),
				new MenuType<>((syncId, inventory) -> new MarketplaceMenu(kind, syncId, inventory), FeatureFlagSet.of())
		);
		TYPES.put(kind, type);
		return type;
	}

	public static MenuType<MarketplaceMenu> typeOf(WindowKind kind) {
		MenuType<MarketplaceMenu> type = TYPES.get(kind);

		if (type == null) {

			throw new IllegalStateException("Вид окна не зарегистрирован: " + kind);
		}

		return type;
	}

	public static void open(ServerPlayer player, WindowKind kind) {

		player.openMenu(new SimpleMenuProvider(
				(syncId, inventory, p) -> new MarketplaceMenu(kind, syncId, inventory),
				kind.title()
		));
	}

	public static void initialize() {
		// MarketplaceMod.LOGGER.info("Marketplace: зарегистрировано окон — {}", TYPES.size());
	}
}
