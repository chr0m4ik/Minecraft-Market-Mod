package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * «Особые предметы» магазина: книги своих зачарований.
 *
 * <p>Такие книги нельзя продавать по общей формуле цены зачарования (она считает от веса и дала бы
 * 4–19 миллионов) — у них <b>своя фиксированная цена</b>, согласованная с заказчиком
 * (см. {@code docs/special-items.md}). Цены заданы здесь, в одном месте: это те же «фиксированные
 * цены», что и в остальном моде (AGENTS.md §8), и таблица в коде не даёт опечататься в JSON.
 *
 * <p>Из раздела «Книги зачарований» наши зачарования исключаются ({@link #isSpecial}), иначе их
 * можно было бы купить по формуле веса в десятки раз дешевле.
 *
 * <p>Книга — обычная {@code minecraft:enchanted_book} со {@code stored_enchantments}: свой предмет
 * не нужен, ванильная наковальня уже умеет переносить зачарование на инструмент.
 */
public final class SpecialCatalog {
	/** Название раздела в магазине — там же, где и цены. */
	private static final String VEIN_MINER = "vein_miner";

	/** Одна книга: зачарование, уровень и цена за штуку. */
	private record Entry(String path, int level, double price) {
	}

	/**
	 * Все «особые предметы» и их цены. Цены — из ТЗ заказчика:
	 * «Жилковый майнер» I/II/III — 200 000 000 / 750 000 000 / 1 600 000 000.
	 */
	private static final List<Entry> ENTRIES = List.of(
			new Entry(VEIN_MINER, 1, 200_000_000.0),
			new Entry(VEIN_MINER, 2, 750_000_000.0),
			new Entry(VEIN_MINER, 3, 1_600_000_000.0));

	private SpecialCatalog() {
		// Класс только со статическими методами.
	}

	/** Книги для раздела магазина. Нужны реестры мира: зачарования лежат в динамическом реестре. */
	public static List<ItemStack> items(HolderLookup.Provider registries) {
		List<ItemStack> stacks = new ArrayList<>();

		for (Entry entry : ENTRIES) {
			book(registries, entry).ifPresent(stacks::add);
		}

		return List.copyOf(stacks);
	}

	/** Книга зачарования нужного уровня. */
	private static Optional<ItemStack> book(HolderLookup.Provider registries, Entry entry) {
		Optional<Holder.Reference<Enchantment>> holder = registries
				.lookupOrThrow(Registries.ENCHANTMENT)
				.get(ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id(entry.path())));

		if (holder.isEmpty()) {
			// Молча продавать нечего нельзя: это ошибка в данных мода.
			MarketplaceMod.LOGGER.error("Особый предмет: зачарование marketplace:{} не найдено", entry.path());
			return Optional.empty();
		}

		ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
		ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		enchantments.set(holder.get(), entry.level());
		stack.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
		stack.set(DataComponents.LORE, new ItemLore(loreOf(entry)));
		return Optional.of(stack);
	}

	/** Описание книги: что за уровень (с размером области) и на что зачарование ставится. */
	private static List<Component> loreOf(Entry entry) {
		return List.of(
				plain(Component.translatable(levelNameKey(entry)).withStyle(ChatFormatting.LIGHT_PURPLE)),
				plain(Component.translatable("marketplace.special." + entry.path() + ".hint")
						.withStyle(ChatFormatting.GRAY)));
	}

	/** Название уровня: «Жилковый майнер I — область 5×5×5». */
	private static String levelNameKey(Entry entry) {
		return "marketplace.special." + entry.path() + ".level." + entry.level();
	}

	/** Убирает курсив: игра делает изменённые названия курсивом. */
	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	/**
	 * Наше ли это зачарование. По нему общий раздел «Книги зачарований» пропускает наши книги:
	 * они продаются только в разделе «Особые предметы» и по своей цене.
	 */
	public static boolean isSpecial(Holder<Enchantment> holder) {
		return !entriesOf(holder).isEmpty();
	}

	/** Цена «особого предмета» за одну штуку. Пусто — это не особый предмет. */
	public static OptionalDouble priceOf(ItemStack stack) {
		return entryOf(stack).map(entry -> OptionalDouble.of(entry.price())).orElseGet(OptionalDouble::empty);
	}

	/** Запись по предмету из магазина: книга ровно с одним нашим зачарованием нужного уровня. */
	private static Optional<Entry> entryOf(ItemStack stack) {
		if (!stack.is(Items.ENCHANTED_BOOK)) {
			return Optional.empty();
		}

		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (stored == null || stored.size() != 1) {
			return Optional.empty();
		}

		Holder<Enchantment> holder = stored.keySet().iterator().next();
		int level = stored.getLevel(holder);
		// Уровень обязателен: уровни одного зачарования — это разные записи с разными ценами.
		return entriesOf(holder).stream().filter(entry -> entry.level() == level).findFirst();
	}

	/** Записи по зачарованию (у одного зачарования их столько, сколько у него уровней). */
	private static List<Entry> entriesOf(Holder<Enchantment> holder) {
		String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");

		if (id.isEmpty()) {
			return List.of();
		}

		return ENTRIES.stream().filter(entry -> fullId(entry).equals(id)).toList();
	}

	/** Полный идентификатор зачарования: {@code marketplace:vein_miner}. */
	private static String fullId(Entry entry) {
		return MarketplaceMod.MOD_ID + ":" + entry.path();
	}
}
