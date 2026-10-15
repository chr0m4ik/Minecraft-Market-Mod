package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Каталог магазина: на какие разделы делятся предметы и что лежит в каждом разделе.
 *
 * <p>Раздел определяется по самому предмету — ничего вручную перечислять не нужно:
 * дорогие предметы попадают в «Редкие», блоки — в «Блоки», еда — в «Еду» и так далее.
 */
public final class ShopCatalog {
	/** Сколько предметов показываем на одной странице: 45 ячеек (9×5). */
	public static final int PAGE_SIZE = 45;

	/** Разделы магазина. Иконка — предмет, которым раздел показывается в меню. */
	public enum Category {
		KITS("marketplace.gui.category.kits", () -> Items.SHULKER_BOX),
		WEAPONS("marketplace.gui.category.weapons", () -> Items.DIAMOND_SWORD),
		ARMOR("marketplace.gui.category.armor", () -> Items.DIAMOND_CHESTPLATE),
		BLOCKS("marketplace.gui.category.blocks", () -> Items.BRICKS),
		POTIONS("marketplace.gui.category.potions", () -> Items.POTION),
		FOOD("marketplace.gui.category.food", () -> Items.BREAD),
		RARE("marketplace.gui.category.rare", () -> Items.NETHER_STAR),
		TOOLS("marketplace.gui.category.tools", () -> Items.DIAMOND_PICKAXE),
		MATERIALS("marketplace.gui.category.materials", () -> Items.IRON_INGOT);

		private final String titleKey;
		private final java.util.function.Supplier<Item> icon;

		Category(String titleKey, java.util.function.Supplier<Item> icon) {
			this.titleKey = titleKey;
			this.icon = icon;
		}

		public String titleKey() {
			return this.titleKey;
		}

		public Item icon() {
			return this.icon.get();
		}
	}

	/** С какого цены предмет считается редким. */
	private static final double RARE_PRICE = 1_000_000.0;

	/** Кэш: раздел → предметы (отсортированы по цене, от дешёвых к дорогим). */
	private static final Map<Category, List<Item>> CACHE = new EnumMap<>(Category.class);

	private ShopCatalog() {
		// Класс только со статическими методами.
	}

	/** Предметы раздела. Список строится один раз и сортируется по цене. */
	public static List<Item> items(Category category) {
		List<Item> cached = CACHE.get(category);

		if (cached != null) {
			return cached;
		}

		List<Item> items = new ArrayList<>();

		for (Item item : BuiltInRegistries.ITEM) {
			// Киты — отдельная система (этап 8), предметов у них пока нет.
			if (category != Category.KITS && categoryOf(item) == category) {
				items.add(item);
			}
		}

		items.sort((first, second) -> Double.compare(ItemPrices.priceOf(first), ItemPrices.priceOf(second)));
		List<Item> result = List.copyOf(items);
		CACHE.put(category, result);
		return result;
	}

	/** Сколько страниц в разделе (минимум одна, чтобы окно не было пустым). */
	public static int pages(Category category) {
		return Math.max(1, (items(category).size() + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	/** Предметы одной страницы раздела. */
	public static List<Item> page(Category category, int page) {
		List<Item> all = items(category);
		int from = Math.max(0, Math.min(page, pages(category) - 1) * PAGE_SIZE);
		int to = Math.min(all.size(), from + PAGE_SIZE);
		return from >= to ? List.of() : all.subList(from, to);
	}

	/** К какому разделу относится предмет. */
	public static Category categoryOf(Item item) {
		String id = BuiltInRegistries.ITEM.getKey(item).getPath();

		// Редкое — всё, что дорого: так на отдельной полке оказываются маяки, шалкеры, незерит.
		if (ItemPrices.priceOf(item) >= RARE_PRICE) {
			return Category.RARE;
		}

		if (isWeapon(id)) {
			return Category.WEAPONS;
		}

		if (isArmor(id)) {
			return Category.ARMOR;
		}

		if (isTool(id)) {
			return Category.TOOLS;
		}

		if (isPotion(id)) {
			return Category.POTIONS;
		}

		if (new ItemStack(item).has(DataComponents.FOOD)) {
			return Category.FOOD;
		}

		if (item instanceof BlockItem) {
			return Category.BLOCKS;
		}

		return Category.MATERIALS;
	}

	private static boolean isWeapon(String id) {
		return id.endsWith("_sword") || id.endsWith("_axe") || id.equals("bow") || id.equals("crossbow")
				|| id.equals("trident") || id.equals("mace") || id.equals("shield") || id.equals("arrow")
				|| id.endsWith("_arrow") || id.equals("spectral_arrow") || id.equals("firework_rocket");
	}

	private static boolean isArmor(String id) {
		return id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings")
				|| id.endsWith("_boots") || id.endsWith("_horse_armor") || id.endsWith("_nautilus_armor")
				|| id.equals("elytra") || id.equals("turtle_helmet") || id.endsWith("_wolf_armor");
	}

	private static boolean isTool(String id) {
		return id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_hoe") || id.equals("shears")
				|| id.equals("fishing_rod") || id.equals("flint_and_steel") || id.endsWith("_bucket")
				|| id.equals("brush") || id.equals("spyglass") || id.equals("compass")
				|| id.equals("recovery_compass") || id.equals("clock") || id.equals("name_tag")
				|| id.equals("lead") || id.equals("carrot_on_a_stick") || id.equals("warped_fungus_on_a_stick")
				|| id.endsWith("_on_a_stick");
	}

	private static boolean isPotion(String id) {
		return id.equals("potion") || id.equals("splash_potion") || id.equals("lingering_potion")
				|| id.equals("tipped_arrow") || id.equals("ominous_bottle") || id.equals("experience_bottle");
	}
}
