package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.collector.CollectorItem;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ShopCatalog {

	public static final int PAGE_SIZE = 45;

	public enum Category {
		KITS("marketplace.gui.category.kits", () -> Items.SHULKER_BOX),

		CUSTOM_KITS("marketplace.gui.category.customKits", () -> Items.SHULKER_SHELL),

		AUCTION("marketplace.gui.category.auction", () -> Items.EMERALD_BLOCK),

		SPECIAL("marketplace.gui.category.special", () -> Items.ENCHANTED_GOLDEN_APPLE),
		WEAPONS("marketplace.gui.category.weapons", () -> Items.DIAMOND_SWORD),
		ARMOR("marketplace.gui.category.armor", () -> Items.DIAMOND_CHESTPLATE),
		TOOLS("marketplace.gui.category.tools", () -> Items.DIAMOND_PICKAXE),
		BLOCKS("marketplace.gui.category.blocks", () -> Items.BRICKS),
		POTIONS("marketplace.gui.category.potions", () -> Items.POTION),
		ARROWS("marketplace.gui.category.arrows", () -> Items.SPECTRAL_ARROW),
		BOOKS("marketplace.gui.category.books", () -> Items.ENCHANTED_BOOK),
		FOOD("marketplace.gui.category.food", () -> Items.BREAD),
		MATERIALS("marketplace.gui.category.materials", () -> Items.IRON_INGOT),
		OTHER("marketplace.gui.category.other", () -> Items.OAK_BOAT),
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

	private static final double RARE_PRICE = 1_000_000.0;

	private static final List<String> COLOR_ORDER = List.of(
			"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
			"lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink");

	private static final List<String> ARMOR_TYPES = List.of(
			"_helmet", "_chestplate", "_leggings", "_boots", "elytra", "wolf_armor",
			"horse_armor", "nautilus_armor", "harness");

	private static final List<String> ARMOR_MATERIALS = List.of(
			"leather", "copper", "golden", "chainmail", "iron", "diamond", "netherite", "turtle");

	private static final Map<Category, List<ItemStack>> CACHE = new EnumMap<>(Category.class);

	private static HolderLookup.Provider registries;

	private static List<CustomKit> customKits = List.of();

	private static List<ItemStack> auctionIcons = List.of();

	private static Map<Item, Integer> CREATIVE_ORDER;

	private static Map<String, Integer> FAMILY_ORDER;

	private static Map<Enchantment, Integer> ENCHANTMENT_ORDER;

	private record SortKey(int group, int family, int color, int content, int contentLevel, int creative, String id) {
	}

	private static final Comparator<SortKey> SORT_KEY_ORDER = Comparator
			.comparingInt(SortKey::group)
			.thenComparingInt(SortKey::family)
			.thenComparingInt(SortKey::color)
			.thenComparingInt(SortKey::content)
			.thenComparingInt(SortKey::contentLevel)
			.thenComparingInt(SortKey::creative)
			.thenComparing(SortKey::id);

	private ShopCatalog() {
		// Класс только со статическими методами.
	}

	public static void setCustomKits(List<CustomKit> kits) {
		customKits = List.copyOf(kits);
	}

	public static void setAuctionIcons(List<ItemStack> icons) {
		auctionIcons = List.copyOf(icons);
	}

	public static void setRegistries(HolderLookup.Provider value) {
		if (registries != value) {
			registries = value;
			CACHE.clear();
			CREATIVE_ORDER = null;
			FAMILY_ORDER = null;
			ENCHANTMENT_ORDER = null;
		}
	}

	public static void refresh(HolderLookup.Provider value) {
		registries = value;
		CACHE.clear();
		ENCHANTMENT_ORDER = null;
	}

	public static List<ItemStack> items(Category category) {
		boolean live = category == Category.AUCTION;
		List<ItemStack> cached = live ? null : CACHE.get(category);

		if (cached != null) {
			return cached;
		}

		List<ItemStack> stacks = new ArrayList<>();

		switch (category) {
			case KITS -> {
				for (KitCatalog.Kit kit : KitCatalog.kits()) {
					stacks.add(KitCatalog.iconOf(kit));
				}
			}
			case CUSTOM_KITS -> {

				for (CustomKit kit : customKits) {
					stacks.add(CustomKits.iconOf(kit));
				}
			}
			case AUCTION -> {

				for (ItemStack icon : auctionIcons) {
					stacks.add(icon.copy());
				}
			}
			case SPECIAL -> {

				if (registries == null) {

					MarketplaceMod.LOGGER.error("Магазин: раздел «Особые предметы» собирается без реестров "
							+ "мира — книги зачарований построить нельзя");
				} else {
					stacks.addAll(SpecialCatalog.items(registries));
				}
			}
			case POTIONS -> {
				addPotions(stacks, true);
				addOminousBottles(stacks);
			}
			case ARROWS -> addArrows(stacks);
			case BOOKS -> addBooks(stacks);
			default -> {
				for (Item item : BuiltInRegistries.ITEM) {

					// Подписанные книги в магазине не нужны вовсе.
					if (ItemPrices.isBuyable(item) && item != Items.WRITTEN_BOOK && item != Items.WRITABLE_BOOK
							&& item != CollectorItem.ITEM && categoryOf(item) == category) {
						stacks.add(new ItemStack(item));
					}
				}
			}
		}

		if (category != Category.CUSTOM_KITS && category != Category.AUCTION) {
			stacks.sort(Comparator.comparing(stack -> sortKey(category, stack), SORT_KEY_ORDER));
		}

		List<ItemStack> result = List.copyOf(stacks);

		if (!live && (category != Category.SPECIAL || registries != null)) {
			CACHE.put(category, result);
		}

		return result;
	}

	private static void addPotions(List<ItemStack> stacks, boolean splashOnly) {
		for (Holder<Potion> potion : BuiltInRegistries.POTION.listElements().toList()) {

			if (potion.unwrapKey().map(key -> key.identifier().getPath()).orElse("").equals("luck")) {
				continue;
			}

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

	private static void addOminousBottles(List<ItemStack> stacks) {
		for (int level = OminousBottleAmplifier.MIN_AMPLIFIER; level <= OminousBottleAmplifier.MAX_AMPLIFIER; level++) {
			ItemStack stack = new ItemStack(Items.OMINOUS_BOTTLE);
			stack.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, new OminousBottleAmplifier(level));
			stacks.add(stack);
		}
	}

	private static void addArrows(List<ItemStack> stacks) {
		stacks.add(new ItemStack(Items.ARROW));
		stacks.add(new ItemStack(Items.SPECTRAL_ARROW));

		for (Holder<Potion> potion : BuiltInRegistries.POTION.listElements().toList()) {
			ItemStack stack = new ItemStack(Items.TIPPED_ARROW);
			stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
			stacks.add(stack);
		}
	}

	private static void addBooks(List<ItemStack> stacks) {
		if (registries == null) {
			stacks.add(new ItemStack(Items.ENCHANTED_BOOK));
			return;
		}

		registries.lookup(Registries.ENCHANTMENT).ifPresent(lookup -> lookup.listElements().forEach(enchantment -> {

			if (SpecialCatalog.isSpecial(enchantment)) {
				return;
			}

			for (int level = 1; level <= enchantment.value().getMaxLevel(); level++) {
				ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
				ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
				enchantments.set(enchantment, level);
				stack.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
				stacks.add(stack);
			}
		}));
	}

	private static SortKey sortKey(Category category, ItemStack stack) {
		Item item = stack.getItem();
		String id = BuiltInRegistries.ITEM.getKey(item).getPath();

		if (category == Category.POTIONS || category == Category.ARROWS || category == Category.BOOKS
				|| category == Category.SPECIAL) {
			return new SortKey(0, 0, 0, contentKey(stack), contentLevel(stack), 0, id);
		}

		if (category == Category.ARMOR) {
			return new SortKey(armorType(id), armorMaterial(id), colorRank(id), 0, 0, creativeOrder(item), id);
		}

		String family = family(id);
		return new SortKey(0, familyOrder(family), colorRank(id), 0, 0, creativeOrder(item), id);
	}

	private static String family(String id) {
		for (String color : COLOR_ORDER) {
			if (id.startsWith(color + "_")) {
				return id.substring(color.length() + 1);
			}
		}

		return id;
	}

	private static int familyOrder(String family) {
		if (FAMILY_ORDER == null) {
			Map<String, Integer> order = new HashMap<>();

			for (Item item : BuiltInRegistries.ITEM) {
				String id = BuiltInRegistries.ITEM.getKey(item).getPath();
				order.merge(family(id), creativeOrder(item), Math::min);
			}

			FAMILY_ORDER = order;
		}

		return FAMILY_ORDER.getOrDefault(family, Integer.MAX_VALUE);
	}

	private static int colorRank(String id) {
		for (int index = 0; index < COLOR_ORDER.size(); index++) {
			if (id.startsWith(COLOR_ORDER.get(index) + "_")) {
				return index + 1;
			}
		}

		return 0;
	}

	private static int armorType(String id) {
		for (int index = 0; index < ARMOR_TYPES.size(); index++) {
			if (id.endsWith(ARMOR_TYPES.get(index))) {
				return index;
			}
		}

		return ARMOR_TYPES.size();
	}

	private static int armorMaterial(String id) {
		for (int index = 0; index < ARMOR_MATERIALS.size(); index++) {
			if (id.startsWith(ARMOR_MATERIALS.get(index) + "_")) {
				return index;
			}
		}

		return ARMOR_MATERIALS.size();
	}

	private static int contentKey(ItemStack stack) {
		Item item = stack.getItem();

		if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION
				|| item == Items.TIPPED_ARROW) {
			return potionIndex(stack);
		}

		if (item == Items.ARROW) {
			return -2;
		}

		if (item == Items.SPECTRAL_ARROW) {
			return -1;
		}

		if (item == Items.ENCHANTED_BOOK) {
			return enchantmentIndex(stack);
		}

		if (item == Items.OMINOUS_BOTTLE) {
			// Дурные зелья — после всех обычных зелий.
			return BuiltInRegistries.POTION.size() + ominousLevel(stack);
		}

		return 0;
	}

	private static int contentLevel(ItemStack stack) {
		Item item = stack.getItem();

		if (item == Items.POTION) {
			return 0;
		}

		if (item == Items.SPLASH_POTION) {
			return 1;
		}

		if (item == Items.LINGERING_POTION) {
			return 2;
		}

		if (item == Items.ENCHANTED_BOOK) {
			return enchantmentLevel(stack);
		}

		return 0;
	}

	private static int potionIndex(ItemStack stack) {
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);

		if (contents == null) {
			return Integer.MAX_VALUE;
		}

		return contents.potion()
				.map(potion -> BuiltInRegistries.POTION.getId(potion.value()))
				.orElse(Integer.MAX_VALUE);
	}

	private static int ominousLevel(ItemStack stack) {
		OminousBottleAmplifier amplifier = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
		return amplifier == null ? 0 : amplifier.value();
	}

	private static int enchantmentIndex(ItemStack stack) {
		if (ENCHANTMENT_ORDER == null) {
			Map<Enchantment, Integer> order = new HashMap<>();

			if (registries != null) {
				List<Holder.Reference<Enchantment>> all = registries.lookupOrThrow(Registries.ENCHANTMENT)
						.listElements()
						.sorted(Comparator.comparing(holder -> holder.key().identifier().toString()))
						.toList();

				for (int index = 0; index < all.size(); index++) {
					order.put(all.get(index).value(), index);
				}
			}

			ENCHANTMENT_ORDER = order;
		}

		return bookEnchantment(stack)
				.map(enchantment -> ENCHANTMENT_ORDER.getOrDefault(enchantment, Integer.MAX_VALUE))
				.orElse(Integer.MAX_VALUE);
	}

	private static int enchantmentLevel(ItemStack stack) {
		ItemEnchantments enchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (enchantments == null || enchantments.isEmpty()) {
			return 0;
		}

		Holder<Enchantment> holder = enchantments.keySet().iterator().next();
		return enchantments.getLevel(holder);
	}

	private static Optional<Enchantment> bookEnchantment(ItemStack stack) {
		ItemEnchantments enchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (enchantments == null || enchantments.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(enchantments.keySet().iterator().next().value());
	}

	private static int creativeOrder(Item item) {
		if (CREATIVE_ORDER == null) {
			Map<Item, Integer> order = scanCreativeTabs();

			if (order.isEmpty() && registries != null) {
				CreativeModeTabs.tryRebuildTabContents(FeatureFlags.VANILLA_SET, true, registries);
				order = scanCreativeTabs();
			}

			CREATIVE_ORDER = order;
		}

		return CREATIVE_ORDER.getOrDefault(item, Integer.MAX_VALUE);
	}

	private static Map<Item, Integer> scanCreativeTabs() {
		Map<Item, Integer> order = new HashMap<>();
		int index = 0;

		for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
			if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
				continue;
			}

			for (ItemStack display : tab.getDisplayItems()) {
				if (!order.containsKey(display.getItem())) {
					order.put(display.getItem(), index);
				}

				index++;
			}
		}

		return order;
	}

	public static int pages(Category category) {
		return Math.max(1, (items(category).size() + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	public static List<ItemStack> page(Category category, int page) {
		List<ItemStack> all = items(category);
		int from = Math.max(0, Math.min(page, pages(category) - 1) * PAGE_SIZE);
		int to = Math.min(all.size(), from + PAGE_SIZE);
		return from >= to ? List.of() : all.subList(from, to);
	}

	public static Optional<Category> byName(String name) {
		for (Category category : Category.values()) {
			if (category.name().equalsIgnoreCase(name)) {
				return Optional.of(category);
			}
		}

		return Optional.empty();
	}

	public static String names() {
		List<String> names = new ArrayList<>();

		for (Category category : Category.values()) {
			names.add(category.name().toLowerCase(java.util.Locale.ROOT));
		}

		return String.join(", ", names);
	}

	public static Category categoryOf(Item item) {
		String id = BuiltInRegistries.ITEM.getKey(item).getPath();

		if (item == Items.ENCHANTED_BOOK) {
			return Category.BOOKS;
		}

		if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION
				|| item == Items.OMINOUS_BOTTLE) {
			return Category.POTIONS;
		}

		if (id.equals("trial_key") || id.equals("ominous_trial_key")
				|| id.equals("netherite_upgrade_smithing_template")) {
			return Category.RARE;
		}

		if (id.endsWith("_boat") || id.endsWith("_raft") || id.endsWith("_chest_boat")
				|| id.endsWith("_dye") || id.endsWith("_map") || id.equals("map") || id.equals("filled_map")
				|| id.endsWith("_minecart") || id.equals("bundle") || id.endsWith("_bundle")
				|| id.endsWith("_banner_pattern") || id.endsWith("_pottery_sherd") || id.equals("brick")) {
			return Category.OTHER;
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
				|| id.equals("elytra") || id.equals("turtle_helmet") || id.equals("wolf_armor")
				|| id.endsWith("_wolf_armor") || id.endsWith("_harness");
	}

	private static boolean isTool(String id) {
		return id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_hoe") || id.equals("shears")
				|| id.equals("fishing_rod") || id.equals("flint_and_steel") || id.endsWith("_bucket")
				|| id.equals("brush") || id.equals("spyglass") || id.equals("compass")
				|| id.equals("recovery_compass") || id.equals("clock") || id.equals("name_tag")
				|| id.equals("lead") || id.endsWith("_on_a_stick");
	}
}
