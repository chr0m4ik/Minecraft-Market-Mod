package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.turbo.TurboFurnace;
import com.chr0m4ik.marketplace.turbo.TurboUpgrade;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * «Особые предметы» магазина: свои зачарования книгами и свой предмет-блок «Турбо печка».
 *
 * <p>Такие товары нельзя продавать по общей формуле: книга своего зачарования по формуле цены
 * зачарования (от веса) стоила бы 4–19 миллионов, а «Турбо печка» вовсе не имеет рецепта,
 * поэтому у всех у них <b>своя фиксированная цена</b>, согласованная с заказчиком
 * (см. {@code docs/special-items.md}). Цены заданы здесь, в одном месте: это те же «фиксированные
 * цены», что и в остальном моде (AGENTS.md §8), и таблица в коде не даёт опечататься в JSON.
 *
 * <p>Из раздела «Книги зачарований» наши зачарования исключаются ({@link #isSpecial}), иначе их
 * можно было бы купить по формуле веса в десятки раз дешевле. Предмет-блок в общие разделы не
 * попадает сам: он не записан в {@code prices.json} и потому не «покупаемый» для каталога.
 *
 * <p>Книга — обычная {@code minecraft:enchanted_book} со {@code stored_enchantments}: свой предмет
 * не нужен, ванильная наковальня уже умеет переносить зачарование на инструмент.
 */
public final class SpecialCatalog {
	/** «Жилковый майнер»: путь зачарования в нашем пространстве имён. */
	private static final String VEIN_MINER = "vein_miner";

	/** «Бур»: путь зачарования в нашем пространстве имён. */
	private static final String VEIN_DRILL = "vein_drill";

	/** «Магнит»: путь зачарования в нашем пространстве имён. */
	private static final String MAGNET = "magnet";

	/** «Плавильня»: путь зачарования в нашем пространстве имён. */
	private static final String SMELTER = "smelter";

	/** «Турбо печка»: путь предмета-блока в нашем пространстве имён. */
	private static final String TURBO_FURNACE = "turbo_furnace";

	/**
	 * Один «особый предмет»: цена за одну штуку.
	 *
	 * <p>Товары двух видов, и различаются они способом выдачи:
	 * <ul>
	 *     <li><b>книга</b> — своё зачарование ({@code item == null}), уровень — уровень зачарования;</li>
	 *     <li><b>свой предмет-блок</b> ({@code item} задан) — уровень лежит в компоненте предмета
	 *         (см. {@code turbo/TurboUpgrade}), поэтому один и тот же предмет идёт четырьмя
	 *         записями с разными уровнями и ценами.</li>
	 * </ul>
	 */
	private record Entry(String path, int level, double price, @Nullable Item item) {
	}

	/**
	 * Все «особые предметы» и их цены. Цены — из ТЗ заказчика:
	 * «Жилковый майнер» I/II/III — 200 000 000 / 750 000 000 / 1 600 000 000,
	 * «Бур» I/II/III — 100 000 000 / 600 000 000 / 1 300 000 000,
	 * «Магнит» I/II — 100 000 000 / 500 000 000,
	 * «Плавильня» I — 300 000 000,
	 * «Турбо печка» I/II/III/IV — 100 000 000 / 250 000 000 / 600 000 000 / 1 400 000 000.
	 *
	 * <p>Порядок записей важен для печек: в разделе магазина они сортируются по содержимому,
	 * а у всех четырёх содержимого нет, поэтому уровни идут в том порядке, в каком записаны
	 * здесь (сортировка списка в Java устойчивая).
	 */
	private static final List<Entry> ENTRIES = List.of(
			new Entry(VEIN_MINER, 1, 200_000_000.0, null),
			new Entry(VEIN_MINER, 2, 750_000_000.0, null),
			new Entry(VEIN_MINER, 3, 1_600_000_000.0, null),
			new Entry(VEIN_DRILL, 1, 100_000_000.0, null),
			new Entry(VEIN_DRILL, 2, 600_000_000.0, null),
			new Entry(VEIN_DRILL, 3, 1_300_000_000.0, null),
			new Entry(MAGNET, 1, 100_000_000.0, null),
			new Entry(MAGNET, 2, 500_000_000.0, null),
			new Entry(SMELTER, 1, 300_000_000.0, null),
			new Entry(TURBO_FURNACE, 1, 100_000_000.0, TurboFurnace.ITEM),
			new Entry(TURBO_FURNACE, 2, 250_000_000.0, TurboFurnace.ITEM),
			new Entry(TURBO_FURNACE, 3, 600_000_000.0, TurboFurnace.ITEM),
			new Entry(TURBO_FURNACE, 4, 1_400_000_000.0, TurboFurnace.ITEM));

	private SpecialCatalog() {
		// Класс только со статическими методами.
	}

	/** Товары для раздела магазина. Нужны реестры мира: зачарования лежат в динамическом реестре. */
	public static List<ItemStack> items(HolderLookup.Provider registries) {
		List<ItemStack> stacks = new ArrayList<>();

		for (Entry entry : ENTRIES) {
			if (entry.item() == null) {
				book(registries, entry).ifPresent(stacks::add);
			} else {
				stacks.add(blockItem(entry));
			}
		}

		return List.copyOf(stacks);
	}

	/**
	 * Свой предмет-блок магазина: уровень кладём в компонент предмета, а название и описание
	 * берём те же, что будут у печки, выпавшей из мира (см. {@code turbo/TurboUpgrade}).
	 */
	private static ItemStack blockItem(Entry entry) {
		ItemStack stack = new ItemStack(entry.item());
		TurboUpgrade.setLevel(stack, entry.level());
		TurboUpgrade.decorate(stack, entry.level());

		return stack;
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
		// Своё название, а не ванильное «Зачарованная книга»: иначе в разделе «Особые предметы»
		// лежат шесть одинаковых книг без подписи, и понять, что это наши зачарования, нельзя.
		stack.set(DataComponents.CUSTOM_NAME,
				plain(Component.translatable(nameKey(entry)).withStyle(ChatFormatting.AQUA)));
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

	/** Название книги: «Книга „Бур“ III». */
	private static String nameKey(Entry entry) {
		return "marketplace.special." + entry.path() + ".name." + entry.level();
	}

	/** Размер области уровня: «Область 7×7×7». */
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

	/**
	 * Магазинная цена «Турбо печки» нужного уровня — по ней считается и базовая цена продажи
	 * (см. {@code price/ItemPrices.basePriceOf(ItemStack)}): заказчик решил, что продать печку
	 * можно ровно за столько, сколько она стоит в магазине. Пусто — уровень вне {@code 1..4}.
	 *
	 * <p>Таблица цен одна на весь мод: и магазин, и продажа берут числа отсюда, поэтому
	 * разойтись они не могут.
	 */
	public static OptionalDouble turboPriceOf(int level) {
		return ENTRIES.stream()
				.filter(entry -> entry.item() == TurboFurnace.ITEM && entry.level() == level)
				.map(entry -> OptionalDouble.of(entry.price()))
				.findFirst()
				.orElseGet(OptionalDouble::empty);
	}

	/**
	 * Особый предмет, у которого цена зависит от уровня в компоненте предмета (сейчас это только
	 * «Турбо печка»). Такой предмет показывает свою цену как обычную базовую
	 * ({@code ItemPrices.priceOf} берёт её из {@link #turboPriceOf}), поэтому отдельную строку
	 * «цена магазина» в подсказке дублировать не нужно.
	 */
	public static boolean isLevelledBlock(ItemStack stack) {
		return TurboUpgrade.levelOf(stack) > 0;
	}

	/** Запись по предмету из магазина: книга ровно с одним нашим зачарованием нужного уровня
	 *  или свой предмет-блок с уровнем в компоненте предмета. */
	private static Optional<Entry> entryOf(ItemStack stack) {
		// Свой предмет-блок: уровень хранится в компоненте предмета, а не в зачарованиях.
		int blockLevel = TurboUpgrade.levelOf(stack);

		if (blockLevel > 0) {
			return ENTRIES.stream()
					.filter(entry -> entry.item() != null && stack.is(entry.item()) && entry.level() == blockLevel)
					.findFirst();
		}

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

		// Только книги: у записей-предметов зачарования нет, их путь не совпадёт с id зачарования
		// случайно, но проверять его незачем.
		return ENTRIES.stream()
				.filter(entry -> entry.item() == null && fullId(entry).equals(id))
				.toList();
	}

	/** Полный идентификатор зачарования: {@code marketplace:vein_miner}. */
	private static String fullId(Entry entry) {
		return MarketplaceMod.MOD_ID + ":" + entry.path();
	}
}
