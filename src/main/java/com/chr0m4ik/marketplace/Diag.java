package com.chr0m4ik.marketplace;

import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * ВРЕМЕННО: диагностика проблемы «книг нет в продаже».
 *
 * <p>Заказчик открывает магазин сам (клавиша {@code I} → «Купить» → иконка раздела), и по логу
 * должно быть видно, что именно происходит: какие разделы собрались и сколько в них предметов,
 * что положил сервер в слоты и что реально лежит в слотах открытого окна. Все строки помечены
 * словом {@code ВРЕМЕННО}, чтобы их было легко найти и потом убрать.
 *
 * <p>Главная строка для заказчика — {@link #logShopWindow}: она пишется в лог клиента при каждом
 * открытии раздела магазина и говорит, сколько предметов доехало до окна и сколько из них наши
 * книги. Если заказчик снова увидит пусто, по этой строке сразу понятно, пусто ли на самом деле
 * (тогда рядом будет {@code ERROR}) или он смотрит не туда.
 *
 * <p>Удалить вместе с вызовами после того, как причина будет найдена и починена.
 */
public final class Diag {
	/**
	 * Метка сборки: меняется при каждой правке — по ней видно, что играет именно свежий код.
	 * Если у заказчика в логе другая метка, значит играет старая сборка.
	 */
	public static final String BUILD_TAG = "diag-2026-10-16-special-2";

	private Diag() {
		// Класс только со статическими методами.
	}

	/** Строка диагностики. */
	public static void log(String format, Object... args) {
		MarketplaceMod.LOGGER.info("ВРЕМЕННО: " + format, args);
	}

	/**
	 * Откуда запущен код и когда он собран. Если играет старая сборка, это будет видно сразу:
	 * у неё другая метка и другое время файла класса.
	 */
	public static String buildInfo() {
		String location = "неизвестно";
		String time = "неизвестно";

		try {
			Path path = Paths.get(MarketplaceMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			location = path.toString();

			// В разработке код берётся из каталога классов, в обычной игре — из jar-файла.
			File classes = new File(path.toFile(), "com/chr0m4ik/marketplace/MarketplaceMod.class");
			time = String.valueOf((classes.isFile() ? classes : path.toFile()).lastModified());
		} catch (URISyntaxException | RuntimeException e) {
			location = "ошибка: " + e;
		}

		return BUILD_TAG + ", источник " + location + ", время " + time;
	}

	/** Все разделы магазина и число предметов в каждом — по этой строке видно, что каталог собран. */
	public static void logCategories(HolderLookup.Provider registries, String where) {
		log("сборка: {}", buildInfo());
		ShopCatalog.refresh(registries);
		StringBuilder line = new StringBuilder();

		for (ShopCatalog.Category category : ShopCatalog.Category.values()) {
			line.append(category.name()).append(' ').append(ShopCatalog.items(category).size()).append(", ");
		}

		log("разделы магазина ({}): {}", where, line);

		for (ItemStack stack : ShopCatalog.items(ShopCatalog.Category.SPECIAL)) {
			log("  SPECIAL: {} «{}»", itemId(stack), stack.getHoverName().getString());
		}
	}

	/**
	 * Серверная строка об открытом разделе магазина: сколько товаров сервер положил в окно.
	 * Пустой раздел — это ошибка, поэтому о ней пишется {@code ERROR} с меткой сборки.
	 */
	public static void logShopSection(String where, ShopCatalog.Category category, int pageNumber, int pages,
			List<ItemStack> page, int total) {
		log("магазин ({}): сборка {}, раздел {} «{}», страница {} из {}, товаров на странице {}, всего в разделе {}, наших книг {}",
				where, BUILD_TAG, category.name(), categoryName(category), pageNumber, pages,
				page.size(), total, countOurBooks(page));

		if (page.isEmpty()) {
			MarketplaceMod.LOGGER.error("Магазин: раздел {} пуст — показать нечего (сборка {}). Если раздел собирается "
					+ "из реестров мира (SPECIAL, BOOKS, POTIONS, ARROWS), значит игра не видит данные мода: "
					+ "ищите выше строки ERROR про зачарование или про отсутствие реестров",
					category.name(), BUILD_TAG);
		}
	}

	/**
	 * Строка «что реально в окне магазина» — одинаковая на сервере и на клиенте.
	 * Заказчику нужна именно клиентская: она говорит, что доехало до его экрана.
	 */
	public static void logShopWindow(String where, MarketplaceMenu menu) {
		if (menu.getKind() != WindowKind.BUY_LIST) {
			return;
		}

		ShopCatalog.Category[] categories = ShopCatalog.Category.values();
		int index = Math.max(0, Math.min(menu.getShopCategory(), categories.length - 1));
		ShopCatalog.Category category = categories[index];
		int filled = 0;
		int ours = 0;

		for (int i = 0; i < menu.tradeSlotCount(); i++) {
			ItemStack stack = menu.getTradeContainer().getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			filled++;

			if (isOurBook(stack)) {
				ours++;
			}
		}

		log("магазин ({}): сборка {}, раздел {} «{}», страница {} из {}, предметов в окне {}, из них наших книг {}",
				where, BUILD_TAG, category.name(), categoryName(category),
				menu.getShopPage(), menu.getShopPages(), filled, ours);

		if (filled == 0 && menu.getShopPages() > 0) {
			MarketplaceMod.LOGGER.error("Магазин: окно раздела {} пустое (сборка {}). Сервер не прислал ни одного "
					+ "предмета — пришлите эту строку и строки ERROR рядом с ней", category.name(), BUILD_TAG);
		}
	}

	/** Название раздела на языке игры (на сервере язык не выбран, поэтому придёт английский). */
	private static String categoryName(ShopCatalog.Category category) {
		return Component.translatable(category.titleKey()).getString();
	}

	/** Сколько предметов в списке — наши книги своих зачарований. */
	public static int countOurBooks(List<ItemStack> stacks) {
		int count = 0;

		for (ItemStack stack : stacks) {
			if (isOurBook(stack)) {
				count++;
			}
		}

		return count;
	}

	/** Наша ли это книга: книга зачарований с зачарованием в нашем пространстве имён. */
	public static boolean isOurBook(ItemStack stack) {
		if (!stack.is(Items.ENCHANTED_BOOK)) {
			return false;
		}

		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (stored == null || stored.isEmpty()) {
			return false;
		}

		for (Holder<Enchantment> holder : stored.keySet()) {
			String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");

			if (id.startsWith(MarketplaceMod.MOD_ID + ":")) {
				return true;
			}
		}

		return false;
	}

	/** Что реально лежит в слотах открытого окна — и на сервере, и на клиенте. */
	public static void dumpMenu(String where, AbstractContainerMenu menu) {
		int filled = 0;

		for (int i = 0; i < menu.slots.size(); i++) {
			if (!menu.getSlot(i).getItem().isEmpty()) {
				filled++;
			}
		}

		log("{}: окон {}, слотов {}, непустых {}", where, menu.getClass().getSimpleName(), menu.slots.size(), filled);

		for (int i = 0; i < menu.slots.size(); i++) {
			ItemStack stack = menu.getSlot(i).getItem();

			if (stack.isEmpty()) {
				continue;
			}

			log("  слот {}: {} x{} «{}»{}{}", i, itemId(stack), stack.getCount(),
					stack.getHoverName().getString(), enchantmentsOf(stack), loreOf(stack));
		}

		if (filled == 0) {
			log("  ВНИМАНИЕ: все слоты окна пустые");
		}
	}

	/** Наши ли это зачарования и с каким уровнем — по этой части строки проверяется критерий успеха. */
	public static String enchantmentsOf(ItemStack stack) {
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (stored == null || stored.isEmpty()) {
			return "";
		}

		StringBuilder line = new StringBuilder(" зачарования: ");

		for (Holder<Enchantment> holder : stored.keySet()) {
			String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("без id");
			line.append(id).append(' ').append(stored.getLevel(holder)).append("; ");
		}

		return line.toString();
	}

	/** Идентификатор предмета в игре. */
	public static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/** Описание предмета: у иконок разделов там лежит число товаров — то, что видит игрок. */
	public static String loreOf(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);

		if (lore == null || lore.lines().isEmpty()) {
			return "";
		}

		StringBuilder line = new StringBuilder(" описание: ");

		for (Component text : lore.lines()) {
			line.append('«').append(text.getString()).append("» ");
		}

		return line.toString();
	}
}
