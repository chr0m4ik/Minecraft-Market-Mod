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
import net.minecraft.network.chat.Component;
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

	/** Иконка набора для магазина: покупается шалкер, поэтому в списке тоже шалкер. */
	public static ItemStack iconOf(Kit kit) {
		ItemStack icon = new ItemStack(Items.SHULKER_BOX);
		icon.set(DataComponents.CUSTOM_NAME, Component.translatable(kit.nameKey()));
		icon.set(DataComponents.LORE, new ItemLore(loreOf(kit)));
		return icon;
	}

	/** Строки описания набора: сначала сводка, затем содержимое. */
	private static List<Component> loreOf(Kit kit) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("marketplace.kit.price",
				com.chr0m4ik.marketplace.economy.MoneyFormat.format(priceOf(kit)))
				.withStyle(net.minecraft.ChatFormatting.GOLD));

		if (kit.shulkers() > 1) {
			lines.add(Component.translatable("marketplace.kit.shulkers",
					Component.literal(String.valueOf(kit.shulkers())))
					.withStyle(net.minecraft.ChatFormatting.RED));
		}

		lines.add(Component.translatable("marketplace.kit.contents").withStyle(net.minecraft.ChatFormatting.GRAY));

		// Показываем первые строки, остальное — по Shift (см. подсказки клиента).
		for (int i = 0; i < Math.min(kit.items().size(), 5); i++) {
			ItemStack item = kit.items().get(i);
			lines.add(Component.literal(" • ").append(item.getHoverName())
					.append(Component.literal(" ×" + item.getCount()))
					.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		}

		if (kit.items().size() > 5) {
			lines.add(Component.translatable("marketplace.kit.more",
					Component.literal(String.valueOf(kit.items().size() - 5)))
					.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
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
			sum += ItemPrices.priceOf(item) * item.getCount();
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

			ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
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

			ItemStack stack = new ItemStack(item.get(), object.get("count").getAsInt());

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
