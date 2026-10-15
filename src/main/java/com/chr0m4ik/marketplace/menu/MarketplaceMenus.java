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

/**
 * Регистрация окон мода.
 *
 * <p>У каждого вида окна свой {@code MenuType} — по нему игра понимает, какое окно открывать
 * на клиенте. Размеры и заголовки берутся из {@link WindowKind}.
 */
public final class MarketplaceMenus {
	/** Вид окна → его тип. Заполняется при загрузке класса. */
	private static final Map<WindowKind, MenuType<MarketplaceMenu>> TYPES = new EnumMap<>(WindowKind.class);

	public static final MenuType<MarketplaceMenu> MAIN = register(WindowKind.MAIN, "main");
	public static final MenuType<MarketplaceMenu> TRADER = register(WindowKind.TRADER, "trader");
	public static final MenuType<MarketplaceMenu> SELL = register(WindowKind.SELL, "sell");
	public static final MenuType<MarketplaceMenu> BUY = register(WindowKind.BUY, "buy");

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

	/** Тип окна по его виду. */
	public static MenuType<MarketplaceMenu> typeOf(WindowKind kind) {
		MenuType<MarketplaceMenu> type = TYPES.get(kind);

		if (type == null) {
			// Такое может случиться только при ошибке в коде, поэтому падаем громко.
			throw new IllegalStateException("Вид окна не зарегистрирован: " + kind);
		}

		return type;
	}

	/** Открывает игроку нужное окно. */
	public static void open(ServerPlayer player, WindowKind kind) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inventory, p) -> new MarketplaceMenu(kind, syncId, inventory),
				kind.title()
		));
	}

	/** Вызывается при загрузке мода: сам вызов нужен, чтобы класс загрузился и всё зарегистрировал. */
	public static void initialize() {
		MarketplaceMod.LOGGER.info("Marketplace: зарегистрировано окон — {}", TYPES.size());
	}
}
