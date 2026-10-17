package com.chr0m4ik.marketplace.price;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Сколько добавляют к цене зачарования.
 *
 * <p><b>Книга.</b> У каждого зачарования в игре есть «вес» ({@code weight}) — чем он меньше, тем
 * реже зачарование встречается. Цена книги считается как {@code 400 000 / вес × 2,2^(уровень−1)}:
 * «Острота I» стоит копейки, «Починка» (вес 2) — в пять раз дороже, «Шёлковое касание» (вес 1) —
 * в десять. Сверху — наценка за количество зачарований: собранная «идеальная» книга дороже суммы
 * своих частей (см. {@link #complexity}).
 *
 * <p><b>Предмет.</b> Зачарованный предмет оценивается иначе, и это главное отличие: книгу можно
 * положить на что угодно, а с предмета зачарование уже не снять. Поэтому на плохом материале оно
 * стоит копейки, а на хорошем — почти как книга (коэффициенты в {@link #tierFactor}). Так находка
 * из «разрушенного портала» (золотая броня с зачарованиями) перестаёт быть состоянием, а алмазная
 * вещь с зачарованиями остаётся дорогой.
 *
 * <p>Дополнительно у предмета есть скидка за количество зачарований ({@link #bundleFactor}):
 * чем больше разных зачарований, тем меньше каждое из них добавляет к цене, потому что «набор»
 * продают целиком и разобрать его нельзя. И жёсткий потолок {@link #JUNK_CAP} для дешёвых
 * материалов — чтобы даже «божественный» золотой меч не стоил миллионы.
 */
public final class EnchantmentPrices {
	/** Цена самого обычного зачарования (вес 10) первого уровня. */
	private static final double BASE_VALUE = 400_000.0;

	/** Самый большой вес зачарования в игре — считаем его «обычным». */
	private static final double COMMON_WEIGHT = 10.0;

	/** Насколько дороже становится книга за каждое следующее зачарование в ней. */
	private static final double COMPLEXITY_STEP = 0.25;

	/**
	 * Во сколько раз дороже каждый следующий уровень зачарования.
	 * В наковальне книга следующего уровня получается слиянием двух книг предыдущего, поэтому
	 * рост двойной, плюс 10% за потраченный опыт и износ наковальни: 2 × 1,1 = 2,2.
	 */
	private static final double LEVEL_STEP = 2.2;

	/** Скидка за количество зачарований на предмете: 1,00 · 0,90 · 0,80 … но не ниже 0,55. */
	private static final double BUNDLE_STEP = 0.10;
	private static final double BUNDLE_FLOOR = 0.55;

	/** Потолок прибавки за зачарования для дешёвых материалов (см. {@link #JUNK_FACTOR_MAX}). */
	private static final double JUNK_CAP = 1_500_000.0;

	/** Материал дешевле этого порога считается «плохим» — для него работает {@link #JUNK_CAP}. */
	private static final double JUNK_FACTOR_MAX = 0.15;

	/**
	 * Коэффициенты по материалу. Порядок важен: материал определяется по префиксу id
	 * ({@code golden_sword}, {@code diamond_chestplate}), поэтому длинные префиксы идут раньше.
	 */
	private static final List<Map.Entry<String, Double>> MATERIAL_FACTORS = List.of(
			Map.entry("netherite", 0.95),
			Map.entry("diamond", 0.60),
			Map.entry("iron", 0.12),
			Map.entry("chainmail", 0.03),
			Map.entry("copper", 0.03),
			Map.entry("golden", 0.02),
			Map.entry("stone", 0.015),
			Map.entry("wooden", 0.01),
			Map.entry("leather", 0.01));

	/**
	 * Точные id с особым коэффициентом: то, что нельзя определить по материалу.
	 * Значения — та же шкала, что у материалов.
	 */
	private static final Map<String, Double> ITEM_FACTORS = Map.ofEntries(
			// Черепаший шлем — единственная «броня» из панциря, по прочности между кольчугой и железом.
			Map.entry("minecraft:turtle_helmet", 0.12),
			// Волчья броня — из панциря армадилла, зачаровывается как обычная броня.
			Map.entry("minecraft:wolf_armor", 0.15),
			// Лук и арбалет: материал деревянный, но это основное оружие, поэтому не 0.01.
			Map.entry("minecraft:bow", 0.30),
			Map.entry("minecraft:crossbow", 0.30),
			Map.entry("minecraft:shield", 0.30),
			Map.entry("minecraft:fishing_rod", 0.30),
			Map.entry("minecraft:carrot_on_a_stick", 0.30),
			Map.entry("minecraft:warped_fungus_on_a_stick", 0.30),
			// Трезубец, булава и элитра — уникальные вещи, зачарование на них ценно полностью.
			Map.entry("minecraft:trident", 1.0),
			Map.entry("minecraft:mace", 1.0),
			Map.entry("minecraft:elytra", 1.0));

	/** Всё остальное (модовые предметы, еда, обычные книги): зачарование почти ничего не добавляет. */
	private static final double DEFAULT_FACTOR = 0.10;

	/** Книга зачарований — переносимый предмет, её цена не режется. */
	private static final double BOOK_FACTOR = 1.0;

	/**
	 * Особые цены. Проклятия по весу выходят самыми дорогими, хотя только мешают, а некоторые
	 * зачарования наоборот почти бесполезны и столько стоить не должны. Id проверены по данным игры.
	 */
	private static final Map<String, Double> SPECIAL = Map.of(
			"minecraft:binding_curse", 100_000.0,
			"minecraft:vanishing_curse", 250_000.0,
			// «Мусорные» зачарования: по весу дорогие, по делу — почти не нужны.
			"minecraft:bane_of_arthropods", 150_000.0,
			"minecraft:thorns", 300_000.0,
			"minecraft:channeling", 300_000.0,
			"minecraft:aqua_affinity", 150_000.0
	);

	/**
	 * Сокровища: их нельзя получить из стола зачаровывания, только из лута, поэтому цена вдвое выше.
	 * Список взят из тега игры {@code minecraft:treasure}; проклятия сюда не входят — у них своя цена.
	 */
	private static final Set<String> TREASURE = Set.of(
			"minecraft:mending",
			"minecraft:frost_walker",
			"minecraft:soul_speed",
			"minecraft:swift_sneak",
			"minecraft:wind_burst"
	);

	/** Во сколько раз дороже сокровища. */
	private static final double TREASURE_MULTIPLIER = 2.0;

	/** Кэш коэффициентов по предмету: цена пересчитывается каждый кадр в подсказках. */
	private static final Map<Item, Double> FACTOR_CACHE = new HashMap<>();

	private EnchantmentPrices() {
		// Класс только со статическими методами.
	}

	/** Цена книги: все зачарования сразу, с наценкой за количество. Так же считается цена книги в магазине. */
	public static double valueOf(ItemEnchantments enchantments) {
		return sumOf(enchantments) * complexity(enchantments.size());
	}

	/**
	 * Цена зачарований на конкретном предмете: сумма книжных цен, умноженная на коэффициент
	 * материала ({@link #tierFactor}) и на скидку за количество зачарований ({@link #bundleFactor}),
	 * но не больше потолка для дешёвых материалов и не больше суммы книг — снять зачарование нельзя.
	 */
	public static double valueOnItem(ItemStack stack, ItemEnchantments enchantments) {
		if (enchantments.isEmpty()) {
			return 0.0;
		}

		double books = sumOf(enchantments);
		double factor = tierFactor(stack);
		double value = books * factor * bundleFactor(enchantments.size());

		if (factor <= JUNK_FACTOR_MAX) {
			value = Math.min(value, JUNK_CAP);
		}

		return Math.min(value, books);
	}

	/** Сумма цен зачарований без наценки за количество и без коэффициентов. */
	private static double sumOf(ItemEnchantments enchantments) {
		double sum = 0.0;

		for (Holder<Enchantment> holder : enchantments.keySet()) {
			sum += valueOf(holder, enchantments.getLevel(holder));
		}

		return sum;
	}

	/**
	 * Во сколько раз зачарование дешевле на этом предмете. 1.0 — книга зачарований, 0.01–0.02 —
	 * дерево, камень и золото, 0.60 — алмаз, 0.95 — незерит.
	 */
	public static double tierFactor(ItemStack stack) {
		if (stack.isEmpty()) {
			return DEFAULT_FACTOR;
		}

		return FACTOR_CACHE.computeIfAbsent(stack.getItem(), EnchantmentPrices::computeTierFactor);
	}

	private static double computeTierFactor(Item item) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);

		// Книга зачарований — переносимый предмет: её цена не режется.
		if (id.getPath().equals("enchanted_book")) {
			return BOOK_FACTOR;
		}

		Double exact = ITEM_FACTORS.get(id.toString());

		if (exact != null) {
			return exact;
		}

		String path = id.getPath();

		for (Map.Entry<String, Double> entry : MATERIAL_FACTORS) {
			if (path.startsWith(entry.getKey() + "_")) {
				return entry.getValue();
			}
		}

		return DEFAULT_FACTOR;
	}

	/** Скидка за количество зачарований: 1.0, 0.9, 0.8 … но не ниже {@link #BUNDLE_FLOOR}. */
	private static double bundleFactor(int count) {
		return Math.max(BUNDLE_FLOOR, 1.0 - BUNDLE_STEP * (count - 1));
	}

	/** Цена одного зачарования на конкретном уровне. */
	public static double valueOf(Holder<Enchantment> holder, int level) {
		String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
		Double special = SPECIAL.get(id);

		if (special != null) {
			return special;
		}

		double price = valueOf(holder.value(), level);
		return TREASURE.contains(id) ? price * TREASURE_MULTIPLIER : price;
	}

	/** Цена по весу зачарования (для зачарований без особой цены). */
	public static double valueOf(Enchantment enchantment, int level) {
		int weight = Math.max(1, enchantment.getWeight());
		return BASE_VALUE * (COMMON_WEIGHT / weight) * Math.pow(LEVEL_STEP, Math.max(0, level - 1));
	}

	/**
	 * Наценка за сложность книги: одно зачарование — 1.0, два — 1.25, пять — 2.0, восемь — 2.75.
	 * Для предметов не применяется: там вместо неё скидка {@link #bundleFactor}.
	 */
	private static double complexity(int count) {
		return 1.0 + (count - 1) * COMPLEXITY_STEP;
	}
}
