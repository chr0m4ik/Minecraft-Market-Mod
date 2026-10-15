package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Каталог магазина: на какие разделы делятся предметы и что лежит в каждом разделе.
 *
 * <p>Раздел определяется по самому предмету, вручную ничего перечислять не нужно.
 * Некоторые разделы собираются особым образом: зелья, стрелы с эффектом и книги зачарований
 * в игре представлены одним предметом с разным содержимым, поэтому для них перебираются
 * варианты из реестров.
 *
 * <p>Предметы, которые нельзя купить (недоступные в выживании), в магазин не попадают.
 */
public final class ShopCatalog {
	/** Сколько товаров показываем на одной странице: 45 ячеек (9×5). */
	public static final int PAGE_SIZE = 45;

	/** Разделы магазина. Иконка — предмет, которым раздел показывается в меню. */
	public enum Category {
		KITS("marketplace.gui.category.kits", () -> Items.SHULKER_BOX),
		WEAPONS("marketplace.gui.category.weapons", () -> Items.DIAMOND_SWORD),
		ARMOR("marketplace.gui.category.armor", () -> Items.DIAMOND_CHESTPLATE),
		TOOLS("marketplace.gui.category.tools", () -> Items.DIAMOND_PICKAXE),
		BLOCKS("marketplace.gui.category.blocks", () -> Items.BRICKS),
		POTIONS("marketplace.gui.category.potions", () -> Items.POTION),
		ARROWS("marketplace.gui.category.arrows", () -> Items.SPECTRAL_ARROW),
		BOOKS("marketplace.gui.category.books", () -> Items.ENCHANTED_BOOK),
		FOOD("marketplace.gui.category.food", () -> Items.BREAD),
		MATERIALS("marketplace.gui.category.materials", () -> Items.IRON_INGOT),
		SPAWN_EGGS("marketplace.gui.category.spawnEggs", () -> Items.CHICKEN_SPAWN_EGG),
		RARE("marketplace.gui.category.rare", () -> Items.NETHER_STAR);

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

	/** С какой цены предмет считается редким. */
	private static final double RARE_PRICE = 1_000_000.0;

	/** Кэш: раздел → товары (отсортированы по названию, как в креативном инвентаре). */
	private static final Map<Category, List<ItemStack>> CACHE = new EnumMap<>(Category.class);

	private ShopCatalog() {
		// Класс только со статическими методами.
	}

	/** Товары раздела. Список строится один раз. */
	public static List<ItemStack> items(Category category) {
		List<ItemStack> cached = CACHE.get(category);

		if (cached != null) {
			return cached;
		}

		List<ItemStack> stacks = new ArrayList<>();

		switch (category) {
			case KITS -> {
				// Киты появятся на этапе 8.
			}
			case POTIONS -> addPotions(stacks, false);
			case ARROWS -> addArrows(stacks);
			case BOOKS -> addBooks(stacks);
			default -> {
				for (Item item : BuiltInRegistries.ITEM) {
					// Недоступные в выживании предметы (барьер, бедрок, свет и прочее) в магазин не берём.
					if (ItemPrices.isBuyable(item) && categoryOf(item) == category) {
						stacks.add(new ItemStack(item));
					}
				}
			}
		}

		stacks.sort(Comparator.comparing(ShopCatalog::sortKey));
		List<ItemStack> result = List.copyOf(stacks);
		CACHE.put(category, result);
		return result;
	}

	/** Зелья: обычные, брызгающие и туманные (флаг — только брызгающие и туманные). */
	private static void addPotions(List<ItemStack> stacks, boolean splashOnly) {
		for (Holder<Potion> potion : BuiltInRegistries.POTION.listElements().toList()) {
			stacks.add(potionStack(Items.POTION, potion));

			if (splashOnly) {
				stacks.add(potionStack(Items.SPLASH_POTION, potion));
				stacks.add(potionStack(Items.LINGERING_POTION, potion));
			}
		}
	}

	private static ItemStack potionStack(Item item, Holder<Potion> potion) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
		return stack;
	}

	/** Стрелы: обычная, спектральная и все стрелы с эффектом. */
	private static void addArrows(List<ItemStack> stacks) {
		stacks.add(new ItemStack(Items.ARROW));
		stacks.add(new ItemStack(Items.SPECTRAL_ARROW));

		for (Holder<Potion> potion : BuiltInRegistries.POTION.listElements().toList()) {
			ItemStack stack = new ItemStack(Items.TIPPED_ARROW);
			stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
			stacks.add(stack);
		}
	}

	/** Книги зачарований: пока обычная книга (варианты по зачарованиям добавлю отдельно). */
	private static void addBooks(List<ItemStack> stacks) {
		stacks.add(new ItemStack(Items.ENCHANTED_BOOK));
	}

	/** Ключ сортировки: сначала по названию предмета, потом по цене. */
	private static String sortKey(ItemStack stack) {
		return stack.getHoverName().getString() + "\u0000" + BuiltInRegistries.ITEM.getKey(stack.getItem());
	}

	/** Сколько страниц в разделе (минимум одна, чтобы окно не было пустым). */
	public static int pages(Category category) {
		return Math.max(1, (items(category).size() + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	/** Товары одной страницы раздела. */
	public static List<ItemStack> page(Category category, int page) {
		List<ItemStack> all = items(category);
		int from = Math.max(0, Math.min(page, pages(category) - 1) * PAGE_SIZE);
		int to = Math.min(all.size(), from + PAGE_SIZE);
		return from >= to ? List.of() : all.subList(from, to);
	}

	/** К какому разделу относится предмет. */
	public static Category categoryOf(Item item) {
		String id = BuiltInRegistries.ITEM.getKey(item).getPath();

		// Книги зачарований, зелья и стрелы собираются отдельно (switch в items).
		if (item == Items.ENCHANTED_BOOK) {
			return Category.BOOKS;
		}

		if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
			return Category.POTIONS;
		}

		if (item == Items.ARROW || item == Items.SPECTRAL_ARROW || item == Items.TIPPED_ARROW) {
			return Category.ARROWS;
		}

		if (id.endsWith("_spawn_egg") || item == Items.SPAWNER || id.endsWith("spawner")
				|| item == Items.VAULT) {
			return Category.SPAWN_EGGS;
		}

		// Головы и черепа — к редким, а не к блокам.
		if (id.endsWith("_head") || id.endsWith("_skull")) {
			return Category.RARE;
		}

		if (ItemPrices.priceOf(item) >= RARE_PRICE) {
			return Category.RARE;
		}

		if (isWeapon(id) || item == Items.END_CRYSTAL) {
			return Category.WEAPONS;
		}

		if (isArmor(id)) {
			return Category.ARMOR;
		}

		if (isTool(id)) {
			return Category.TOOLS;
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
		return id.endsWith("_sword") || id.endsWith("_axe") || id.endsWith("_spear") || id.equals("bow")
				|| id.equals("crossbow") || id.equals("trident") || id.equals("mace") || id.equals("shield")
				|| id.equals("firework_rocket");
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
				|| id.equals("lead") || id.endsWith("_on_a_stick");
	}
}
