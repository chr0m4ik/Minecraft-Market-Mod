package com.chr0m4ik.marketplace.event;

import com.chr0m4ik.marketplace.market.ShopCatalog;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Описание активного крупного ивента: название, цель, множитель цены и длительность.
 *
 * <p>Ивент задевает либо несколько предметов ({@link #itemIds()}), либо целый раздел магазина
 * ({@link #category()}, значения из {@link ShopCatalog.Category}). Множитель применяется ко всем
 * ценам затронутых предметов одинаково и симметрично: и к покупке в магазине, и к продаже
 * торговцу, и к ордерам рынка (см. {@link ItemPrices#priceOf(net.minecraft.world.item.ItemStack)}).
 * Поэтому ивент не может «напечатать деньги»: он опускает или поднимает обе стороны сделки.
 *
 * <p>Ивент — чистое описание без времени: сколько ему ещё идти, хранит сейв мира
 * (см. {@code economy/MarketplaceState} и {@link EventService}). Описание сохраняется в NBT
 * строками (id названия, имя раздела, id предметов), поэтому реестры для чтения не нужны,
 * а неизвестные предметы в сейве просто ни на что не влияют.
 *
 * @param name          название (влияет только на текст и на направление цены);
 * @param category      имя раздела магазина или пустая строка, если ивент на предметы;
 * @param itemIds       id предметов (пусто, если ивент на раздел);
 * @param multiplier    множитель цены, например 0.87 или 1.12;
 * @param durationTicks сколько ивент длится — в тиках активного времени мира.
 */
public record MarketEvent(MarketEvents.Name name, String category, List<String> itemIds, double multiplier,
		long durationTicks) {
	/** Ключ названия в NBT. */
	private static final String NAME_KEY = "name";
	private static final String CATEGORY_KEY = "category";
	private static final String ITEMS_KEY = "items";
	private static final String MULTIPLIER_KEY = "multiplier";
	private static final String DURATION_KEY = "duration";

	public MarketEvent {
		// Список делаем неизменяемым: описание ивента не должно меняться на ходу.
		itemIds = List.copyOf(itemIds);
	}

	/** Ивент на один или несколько предметов. */
	public static MarketEvent ofItems(MarketEvents.Name name, List<String> itemIds, double multiplier,
			long durationTicks) {
		return new MarketEvent(name, "", itemIds, multiplier, durationTicks);
	}

	/** Ивент на целый раздел магазина. */
	public static MarketEvent ofCategory(MarketEvents.Name name, ShopCatalog.Category category,
			double multiplier, long durationTicks) {
		return new MarketEvent(name, category.name(), List.of(), multiplier, durationTicks);
	}

	/** Ивент задевает целый раздел, а не отдельные предметы. */
	public boolean isCategory() {
		return !this.category.isEmpty();
	}

	/** Раздел ивента, если он на раздел. */
	public Optional<ShopCatalog.Category> categoryOrEmpty() {
		return parseCategory(this.category);
	}

	/**
	 * Затронут ли предмет. Сервер и клиент вызывают этот метод с одними и теми же данными,
	 * поэтому множитель у них совпадает (так же устроен микро-дрейф — {@code PriceDrift}).
	 *
	 * <p>Для раздела берём только то, что магазин реально продаёт
	 * ({@link ItemPrices#isBuyable}), — иначе ивент метил бы, например, бедрок.
	 */
	public boolean affects(Item item) {
		if (this.itemIds.contains(itemId(item))) {
			return true;
		}

		Optional<ShopCatalog.Category> target = categoryOrEmpty();
		return target.isPresent() && ItemPrices.isBuyable(item) && ShopCatalog.categoryOf(item) == target.get();
	}

	/** Множитель цены для предмета: 1.0 — предмет ивентом не затронут. */
	public double multiplierFor(Item item) {
		return affects(item) ? this.multiplier : 1.0;
	}

	/** Ивент поднимает цену (по множителю, а не по названию: у «распродажи» направление любое). */
	public boolean rises() {
		return this.multiplier >= 1.0;
	}

	/** Отклонение цены в процентах со знаком: «+12.4%». */
	public String percentText() {
		return MarketEvents.percentText(this.multiplier);
	}

	/**
	 * Отпечаток описания. Нужен кэшу множителей ({@link EventService}) и проверке «ивент
	 * изменился»: по нему видно, что описание другое, а не то же самое.
	 */
	public long signature() {
		long hash = this.name.id().hashCode();
		hash = hash * 31L + this.category.hashCode();
		hash = hash * 31L + this.itemIds.hashCode();
		hash = hash * 31L + Double.hashCode(this.multiplier);
		return hash;
	}

	/** Описание в NBT — для сейва мира. */
	public CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
		tag.putString(NAME_KEY, this.name.id());
		tag.putString(CATEGORY_KEY, this.category);
		ListTag items = new ListTag();

		for (String id : this.itemIds) {
			items.addAndUnwrap(StringTag.valueOf(id));
		}

		tag.put(ITEMS_KEY, items);
		tag.putDouble(MULTIPLIER_KEY, this.multiplier);
		tag.putLong(DURATION_KEY, this.durationTicks);
		return tag;
	}

	/**
	 * Описание из NBT. Пусто — если запись повреждена: неизвестное название, недопустимый
	 * множитель или пустая цель (такой ивент ничего не делает, и хранить его нельзя).
	 */
	public static Optional<MarketEvent> fromTag(CompoundTag tag) {
		Optional<MarketEvents.Name> name = MarketEvents.Name.byId(tag.getStringOr(NAME_KEY, ""));

		if (name.isEmpty()) {
			return Optional.empty();
		}

		String category = tag.getStringOr(CATEGORY_KEY, "");
		List<String> items = new ArrayList<>();
		ListTag stored = tag.getListOrEmpty(ITEMS_KEY);

		for (int index = 0; index < stored.size(); index++) {
			String id = stored.getStringOr(index, "");

			if (!id.isEmpty()) {
				items.add(id);
			}
		}

		double multiplier = tag.getDoubleOr(MULTIPLIER_KEY, 1.0);
		long duration = tag.getLongOr(DURATION_KEY, 0L);
		boolean valid = category.isEmpty() ? !items.isEmpty() : parseCategory(category).isPresent();

		if (!valid || multiplier <= 0.0 || multiplier == 1.0 || duration <= 0L) {
			return Optional.empty();
		}

		return Optional.of(new MarketEvent(name.get(), category, items, multiplier, duration));
	}

	/** Раздел по его имени ({@code BLOCKS} и так далее); пустая строка и мусор — пусто. */
	private static Optional<ShopCatalog.Category> parseCategory(String value) {
		if (value == null || value.isEmpty()) {
			return Optional.empty();
		}

		for (ShopCatalog.Category category : ShopCatalog.Category.values()) {
			if (category.name().equals(value)) {
				return Optional.of(category);
			}
		}

		return Optional.empty();
	}

	/** id предмета: строкой, потому что она одинакова на сервере и на клиенте. */
	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
