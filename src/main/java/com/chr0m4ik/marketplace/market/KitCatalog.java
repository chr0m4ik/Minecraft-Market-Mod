package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class KitCatalog {
	private static final String FILE_PATH = "/data/marketplace/kits.json";

	private static final double CONTENT_MARKUP = 1.2;

	private static final int CELLS_PER_SHULKER = 27;

	private static final String KIT_TAG = "marketplace:kit";

	private static final int AUTO = -1;

	public record KitEntry(ItemStack stack, int shulker, int cell) {
	}

	public record Kit(String id, List<KitEntry> entries, List<ItemStack> inventory, int shulkers) {

		public String nameKey() {
			return "marketplace.kit." + this.id;
		}

		public List<ItemStack> items() {
			List<ItemStack> all = new ArrayList<>(this.entries.size() + this.inventory.size());

			for (KitEntry entry : this.entries) {
				all.add(entry.stack());
			}

			all.addAll(this.inventory);
			return List.copyOf(all);
		}
	}

	private static List<Kit> kits = List.of();
	private static boolean loadAttempted;

	private static HolderLookup.Provider registries;

	private KitCatalog() {
		// Класс только со статическими методами.
	}

	public static void setRegistries(HolderLookup.Provider value) {
		if (registries != value) {
			registries = value;
			kits = List.of();
			loadAttempted = false;
		}
	}

	public static void setRegistriesIfMissing(HolderLookup.Provider value) {
		if (registries == null) {
			registries = value;
			kits = List.of();
			loadAttempted = false;
		}
	}

	public static List<Kit> kits() {
		ensureLoaded();
		return kits;
	}

	private static ChatFormatting colorOf(Kit kit) {
		return switch (kit.id()) {
			case "pvp_pro" -> ChatFormatting.AQUA;
			case "pvp_extra" -> ChatFormatting.BLUE;
			case "pvp_crystal" -> ChatFormatting.LIGHT_PURPLE;
			case "miner_dream" -> ChatFormatting.GRAY;
			case "lumberjack_dream" -> ChatFormatting.GREEN;
			case "builder" -> ChatFormatting.YELLOW;
			case "glutton" -> ChatFormatting.GOLD;
			case "pro_set" -> ChatFormatting.DARK_AQUA;
			case "legendary_set" -> ChatFormatting.RED;
			default -> ChatFormatting.WHITE;
		};
	}

	private static ItemStack shulkerOf(Kit kit) {
		String color = switch (kit.id()) {
			case "pvp_pro" -> "light_blue";
			case "pvp_extra" -> "blue";
			case "pvp_crystal" -> "purple";
			case "miner_dream" -> "gray";
			case "lumberjack_dream" -> "green";
			case "builder" -> "yellow";
			case "glutton" -> "orange";
			case "pro_set" -> "cyan";
			case "legendary_set" -> "red";
			default -> "white";
		};

		return BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:" + color + "_shulker_box"))
				.map(ItemStack::new)
				.orElseGet(() -> new ItemStack(Items.SHULKER_BOX));
	}

	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	public static ItemStack iconOf(Kit kit) {
		ItemStack icon = shulkerOf(kit);
		icon.set(DataComponents.CUSTOM_NAME, Component.translatable(kit.nameKey())
				.withStyle(Style.EMPTY.withItalic(false).withColor(colorOf(kit))));
		icon.set(DataComponents.LORE, new ItemLore(loreOf(kit)));
		mark(icon, kit);
		return icon;
	}

	private static void mark(ItemStack stack, Kit kit) {
		CompoundTag tag = new CompoundTag();
		tag.putString(KIT_TAG, kit.id());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	public static String kitId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return "";
		}

		return data.copyTag().getStringOr(KIT_TAG, "");
	}

	private static List<Component> loreOf(Kit kit) {
		List<Component> lines = new ArrayList<>();
		lines.add(plain(Component.translatable("marketplace.kit.price",
				com.chr0m4ik.marketplace.economy.MoneyFormat.format(priceOf(kit)))
				.withStyle(ChatFormatting.GOLD)));

		if (kit.shulkers() > 1) {
			lines.add(plain(Component.translatable("marketplace.kit.shulkers",
					Component.literal(String.valueOf(kit.shulkers())))
					.withStyle(ChatFormatting.RED)));
		}

		int shown = Math.min(kit.items().size(), 3);

		for (int i = 0; i < shown; i++) {
			ItemStack item = kit.items().get(i);
			lines.add(plain(Component.literal(" • ").append(item.getHoverName())
					.append(Component.literal(" ×" + item.getCount()))
					.withStyle(ChatFormatting.DARK_GRAY)));
		}

		if (kit.items().size() > shown) {
			lines.add(plain(Component.translatable("marketplace.kit.more",
					Component.literal(String.valueOf(kit.items().size() - shown)))
					.withStyle(ChatFormatting.DARK_GRAY)));
		}

		return lines;
	}

	public static double priceOf(Kit kit) {
		double contents = 0.0;

		for (ItemStack item : kit.items()) {
			contents += ItemPrices.priceOf(item);
		}

		double shulkers = kit.shulkers() > 0 ? kit.shulkers() * ItemPrices.priceOf(shulkerOf(kit)) : 0.0;
		return contents * CONTENT_MARKUP + shulkers;
	}

	public static List<ItemStack> deliver(Kit kit) {
		if (kit.shulkers() <= 0) {

			return copyOf(kit.items());
		}

		List<ItemStack> overflow = new ArrayList<>();
		List<List<ItemStack>> boxes = layout(kit, overflow);
		List<ItemStack> result = new ArrayList<>();

		for (List<ItemStack> inside : boxes) {
			ItemStack shulker = shulkerOf(kit);

			ItemContainerContents contents = ItemContainerContents.fromItems(inside);

			if (contents == ItemContainerContents.EMPTY) {

				MarketplaceMod.LOGGER.error("Набор {}: шлякер собрался пустым — проверьте kits.json", kit.id());
			} else {
				shulker.set(DataComponents.CONTAINER, contents);
			}

			result.add(shulker);
		}

		result.addAll(copyOf(kit.inventory()));
		result.addAll(overflow);
		return List.copyOf(result);
	}

	private static List<ItemStack> copyOf(List<ItemStack> stacks) {
		List<ItemStack> copies = new ArrayList<>(stacks.size());

		for (ItemStack stack : stacks) {
			copies.add(stack.copy());
		}

		return copies;
	}

	private static List<List<ItemStack>> layout(Kit kit, List<ItemStack> overflow) {
		List<List<ItemStack>> boxes = new ArrayList<>();

		for (int box = 0; box < kit.shulkers(); box++) {
			List<ItemStack> cells = new ArrayList<>(CELLS_PER_SHULKER);

			for (int cell = 0; cell < CELLS_PER_SHULKER; cell++) {
				cells.add(ItemStack.EMPTY);
			}

			boxes.add(cells);
		}

		for (KitEntry entry : kit.entries()) {
			if (!place(boxes, entry)) {
				overflow.add(entry.stack());
			}
		}

		if (!overflow.isEmpty()) {
			// MarketplaceMod.LOGGER.warn("Набор {}: {} предмет(ов) не влезли в шлякеры и будут выданы в инвентарь",
					// kit.id(), overflow.size());
		}

		return boxes;
	}

	private static boolean place(List<List<ItemStack>> boxes, KitEntry entry) {
		if (entry.shulker() >= 1 && entry.shulker() <= boxes.size()) {
			List<ItemStack> box = boxes.get(entry.shulker() - 1);

			for (int cell = Math.max(0, entry.cell()); cell < box.size(); cell++) {
				if (box.get(cell).isEmpty()) {
					box.set(cell, entry.stack());
					return true;
				}
			}
		}

		for (List<ItemStack> box : boxes) {
			for (int cell = 0; cell < box.size(); cell++) {
				if (box.get(cell).isEmpty()) {
					box.set(cell, entry.stack());
					return true;
				}
			}
		}

		return false;
	}

	public static Optional<Kit> kitOf(ItemStack stack) {
		String id = kitId(stack);

		if (id.isEmpty()) {
			return Optional.empty();
		}

		for (Kit kit : kits()) {
			if (kit.id().equals(id)) {
				return Optional.of(kit);
			}
		}

		return Optional.empty();
	}

	public static synchronized void ensureLoaded() {
		if (loadAttempted) {
			return;
		}

		loadAttempted = true;

		try (InputStream stream = KitCatalog.class.getResourceAsStream(FILE_PATH)) {
			if (stream == null) {
				MarketplaceMod.LOGGER.error("Не найден файл наборов {} — раздел «Киты» будет пустым", FILE_PATH);
				return;
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				JsonArray array = root.getAsJsonArray("kits");
				List<Kit> loaded = new ArrayList<>();

				for (JsonElement element : array) {
					JsonObject object = element.getAsJsonObject();
					String id = object.get("id").getAsString();
					int shulkers = object.has("shulkers") ? object.get("shulkers").getAsInt() : 0;

					List<KitEntry> entries = readEntries(object.getAsJsonArray("items"), shulkers, id);
					List<ItemStack> inventory = object.has("inventory")
							? readEntries(object.getAsJsonArray("inventory"), 0, id).stream()
									.map(KitEntry::stack)
									.toList()
							: List.of();

					loaded.add(new Kit(id, entries, inventory, shulkers));
				}

				kits = List.copyOf(loaded);
				// MarketplaceMod.LOGGER.info("Наборов загружено: {}", kits.size());
			}
		} catch (Exception exception) {

			MarketplaceMod.LOGGER.error("Не удалось прочитать файл наборов {}", FILE_PATH, exception);
		}
	}

	private static List<KitEntry> readEntries(JsonArray array, int shulkers, String kitId) {
		List<KitEntry> items = new ArrayList<>();

		for (JsonElement element : array) {
			JsonObject object = element.getAsJsonObject();
			Identifier id = Identifier.parse(object.get("item").getAsString());
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);

			if (item.isEmpty()) {
				// MarketplaceMod.LOGGER.warn("В наборах неизвестный предмет, запись пропущена: {}", id);
				continue;
			}

			Item entry = item.get();
			int total = object.get("count").getAsInt();
			int perStack = Math.max(1, entry.getDefaultMaxStackSize());
			int shulker = object.has("shulker") ? object.get("shulker").getAsInt() : 0;
			int cell = object.has("slot") ? object.get("slot").getAsInt() : AUTO;

			if (cell != AUTO && shulker < 1) {
				shulker = 1;
			}

			if (shulkers > 0 && shulker > shulkers) {
				// MarketplaceMod.LOGGER.warn("Набор {}: у предмета {} указан шлякер {}, а их всего {}",
						// kitId, id, shulker, shulkers);
			}

			if (cell >= CELLS_PER_SHULKER) {
				// MarketplaceMod.LOGGER.warn("Набор {}: у предмета {} указана ячейка {}, а в шлякере их {}",
						// kitId, id, cell, CELLS_PER_SHULKER);
			}

			for (int left = total; left > 0; left -= perStack) {
				ItemStack stack = new ItemStack(entry, Math.min(perStack, left));

				if (object.has("enchantments") && registries != null) {
					applyEnchantments(stack, object.getAsJsonObject("enchantments"));
				}

				if (object.has("potion") && registries != null) {
					Identifier potionId = Identifier.parse(object.get("potion").getAsString());
					registries.lookup(Registries.POTION)
							.flatMap(lookup -> lookup.get(net.minecraft.resources.ResourceKey.create(Registries.POTION, potionId)))
							.ifPresent(holder -> stack.set(DataComponents.POTION_CONTENTS, new PotionContents(holder)));
				}

				items.add(new KitEntry(stack, shulker, cell));
			}
		}

		return List.copyOf(items);
	}

	private static void applyEnchantments(ItemStack stack, JsonObject enchantments) {
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);

		for (String key : enchantments.keySet()) {
			Identifier id = Identifier.parse(key);
			Optional<Holder.Reference<Enchantment>> holder = registries
					.lookupOrThrow(Registries.ENCHANTMENT)
					.get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, id));

			if (holder.isEmpty()) {
				// MarketplaceMod.LOGGER.warn("В наборах неизвестное зачарование, пропущено: {}", id);
				continue;
			}

			mutable.set(holder.get(), enchantments.get(key).getAsInt());
		}

		if (!mutable.toImmutable().isEmpty()) {
			stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
		}
	}

	public static Optional<Holder<Potion>> potion(String id) {
		if (registries == null) {
			return Optional.empty();
		}

		return registries.lookup(Registries.POTION)
				.flatMap(lookup -> lookup.get(net.minecraft.resources.ResourceKey.create(Registries.POTION,
						Identifier.parse(id))));
	}
}
