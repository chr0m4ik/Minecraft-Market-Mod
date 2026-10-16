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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
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

/**
 * Наборы («киты») магазина.
 *
 * <p>Данные лежат в ресурсах мода — {@code /data/marketplace/kits.json}. Каждый набор: id,
 * количество шлякеров (0 — выдать предметами) и список предметов с количеством, зачарованиями
 * и (для зелий) типом зелья.
 *
 * <p>Цена набора: сумма цен содержимого плюс цена шлякеров, минус 5% (оптовая скидка).
 * Цены предметов берутся из общего прайса, поэтому наборы дорожают и дешевеют вместе с ним.
 *
 * <p>Набор выдаётся шалкером с содержимым. Если шлякеров нет — предметы кладутся в инвентарь,
 * и шалкер в цену не входит.
 */
public final class KitCatalog {
	private static final String FILE_PATH = "/data/marketplace/kits.json";

	/** Оптовая скидка на набор. */
	private static final double DISCOUNT = 0.95;

	/** Один набор: id, предметы и сколько шлякеров нужно. */
	public record Kit(String id, List<ItemStack> items, int shulkers) {
		/** Ключ перевода названия набора. */
		public String nameKey() {
			return "marketplace.kit." + this.id;
		}
	}

	private static List<Kit> kits = List.of();
	private static boolean loadAttempted;

	/** Реестры мира: нужны, чтобы собрать зачарования и зелья. */
	private static HolderLookup.Provider registries;

	private KitCatalog() {
		// Класс только со статическими методами.
	}

	/** Отдаём каталогу реестры мира (вызывается серверной частью окна). */
	public static void setRegistries(HolderLookup.Provider value) {
		if (registries != value) {
			registries = value;
			kits = List.of();
			loadAttempted = false;
		}
	}

	/** Все наборы. */
	public static List<Kit> kits() {
		ensureLoaded();
		return kits;
	}

	/** Свой цвет названия для каждого набора — так они различаются в списке. */
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

	/** Шалкер набора. */
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

	/** Убирает курсив: игра делает изменённые названия курсивом. */
	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	/** Иконка набора для магазина: покупается шалкер, поэтому в списке тоже шалкер. */
	public static ItemStack iconOf(Kit kit) {
		ItemStack icon = shulkerOf(kit);
		icon.set(DataComponents.CUSTOM_NAME, Component.translatable(kit.nameKey())
				.withStyle(Style.EMPTY.withItalic(false).withColor(colorOf(kit))));
		icon.set(DataComponents.LORE, new ItemLore(loreOf(kit)));
		return icon;
	}

	/** Строки описания набора: цена, пометка про шлякеры и начало содержимого. */
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

		// Показываем только начало списка: полностью — по Shift.
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

	/** Полный список содержимого (показывается при зажатом Shift). */
	public static List<Component> fullContents(Kit kit) {
		List<Component> lines = new ArrayList<>();

		for (ItemStack item : kit.items()) {
			lines.add(Component.literal(" • ").append(item.getHoverName())
					.append(Component.literal(" ×" + item.getCount()))
					.withStyle(net.minecraft.ChatFormatting.GRAY));
		}

		return lines;
	}

	/**
	 * Цена набора: содержимое плюс шлякеры, минус 5%.
	 * Покупка набора не умножается на общую наценку магазина — скидка уже заложена.
	 */
	public static double priceOf(Kit kit) {
		double sum = 0.0;

		for (ItemStack item : kit.items()) {
			sum += ItemPrices.priceOf(item);
		}

		sum += kit.shulkers() * ItemPrices.priceOf(Items.SHULKER_BOX);
		return sum * DISCOUNT;
	}

	/** Что выдать игроку: шалкеры с содержимым или предметы (когда шлякеров нет). */
	public static List<ItemStack> deliver(Kit kit) {
		if (kit.shulkers() <= 0) {
			return List.copyOf(kit.items());
		}

		List<ItemStack> result = new ArrayList<>();
		int perShulker = 27;
		int index = 0;

		for (int box = 0; box < kit.shulkers() && index < kit.items().size(); box++) {
			List<ItemStack> inside = new ArrayList<>();

			while (index < kit.items().size() && inside.size() < perShulker) {
				inside.add(kit.items().get(index));
				index++;
			}

			ItemStack shulker = shulkerOf(kit);
			shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inside));
			result.add(shulker);
		}

		return result;
	}

	/** Найти набор по его иконке (магазин показывает и продаёт именно иконку). */
	public static Optional<Kit> kitOf(ItemStack stack) {
		for (Kit kit : kits()) {
			if (ItemStack.matches(iconOf(kit), stack)) {
				return Optional.of(kit);
			}
		}

		return Optional.empty();
	}

	/** Читает kits.json из ресурсов мода. */
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
					loaded.add(new Kit(
							object.get("id").getAsString(),
							readItems(object.getAsJsonArray("items")),
							object.has("shulkers") ? object.get("shulkers").getAsInt() : 0));
				}

				kits = List.copyOf(loaded);
				MarketplaceMod.LOGGER.info("Наборов загружено: {}", kits.size());
			}
		} catch (Exception exception) {
			// Не прячем проблему: пишем понятную ошибку, раздел останется пустым.
			MarketplaceMod.LOGGER.error("Не удалось прочитать файл наборов {}", FILE_PATH, exception);
		}
	}

	/** Читает предметы одного набора. */
	private static List<ItemStack> readItems(JsonArray array) {
		List<ItemStack> items = new ArrayList<>();

		for (JsonElement element : array) {
			JsonObject object = element.getAsJsonObject();
			Identifier id = Identifier.parse(object.get("item").getAsString());
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);

			if (item.isEmpty()) {
				MarketplaceMod.LOGGER.warn("В наборах неизвестный предмет, запись пропущена: {}", id);
				continue;
			}

			Item entry = item.get();
			int total = object.get("count").getAsInt();
			int perStack = Math.max(1, entry.getDefaultMaxStackSize());

			// Большие количества режем на нормальные стаки: стак из 128 предметов игра
			// отправить не может (именно из-за этого вылетал мир при покупке набора).
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

				items.add(stack);
			}
		}

		return List.copyOf(items);
	}

	/** Накладывает зачарования из JSON на предмет. */
	private static void applyEnchantments(ItemStack stack, JsonObject enchantments) {
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);

		for (String key : enchantments.keySet()) {
			Identifier id = Identifier.parse(key);
			Optional<Holder.Reference<Enchantment>> holder = registries
					.lookupOrThrow(Registries.ENCHANTMENT)
					.get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, id));

			if (holder.isEmpty()) {
				MarketplaceMod.LOGGER.warn("В наборах неизвестное зачарование, пропущено: {}", id);
				continue;
			}

			mutable.set(holder.get(), enchantments.get(key).getAsInt());
		}

		if (!mutable.toImmutable().isEmpty()) {
			stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
		}
	}

	/** Держатель зелья по id (нужен для подсказок). */
	public static Optional<Holder<Potion>> potion(String id) {
		if (registries == null) {
			return Optional.empty();
		}

		return registries.lookup(Registries.POTION)
				.flatMap(lookup -> lookup.get(net.minecraft.resources.ResourceKey.create(Registries.POTION,
						Identifier.parse(id))));
	}
}
