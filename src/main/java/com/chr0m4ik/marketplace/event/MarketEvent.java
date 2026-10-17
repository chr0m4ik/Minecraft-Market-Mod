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

public record MarketEvent(MarketEvents.Name name, String category, List<String> itemIds, double multiplier,
		long durationTicks) {

	private static final String NAME_KEY = "name";
	private static final String CATEGORY_KEY = "category";
	private static final String ITEMS_KEY = "items";
	private static final String MULTIPLIER_KEY = "multiplier";
	private static final String DURATION_KEY = "duration";

	public MarketEvent {

		itemIds = List.copyOf(itemIds);
	}

	public static MarketEvent ofItems(MarketEvents.Name name, List<String> itemIds, double multiplier,
			long durationTicks) {
		return new MarketEvent(name, "", itemIds, multiplier, durationTicks);
	}

	public static MarketEvent ofCategory(MarketEvents.Name name, ShopCatalog.Category category,
			double multiplier, long durationTicks) {
		return new MarketEvent(name, category.name(), List.of(), multiplier, durationTicks);
	}

	public boolean isCategory() {
		return !this.category.isEmpty();
	}

	public Optional<ShopCatalog.Category> categoryOrEmpty() {
		return parseCategory(this.category);
	}

	public boolean affects(Item item) {
		if (this.itemIds.contains(itemId(item))) {
			return true;
		}

		Optional<ShopCatalog.Category> target = categoryOrEmpty();
		return target.isPresent() && ItemPrices.isBuyable(item) && ShopCatalog.categoryOf(item) == target.get();
	}

	public double multiplierFor(Item item) {
		return affects(item) ? this.multiplier : 1.0;
	}

	public boolean rises() {
		return this.multiplier >= 1.0;
	}

	public String percentText() {
		return MarketEvents.percentText(this.multiplier);
	}

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

	public static Optional<MarketEvent> fromTag(CompoundTag tag) {
		List<String> items = new ArrayList<>();
		ListTag stored = tag.getListOrEmpty(ITEMS_KEY);

		for (int index = 0; index < stored.size(); index++) {
			String id = stored.getStringOr(index, "");

			if (!id.isEmpty()) {
				items.add(id);
			}
		}

		long duration = tag.getLongOr(DURATION_KEY, 0L);

		if (duration <= 0L) {
			return Optional.empty();
		}

		return build(tag.getStringOr(NAME_KEY, ""), tag.getStringOr(CATEGORY_KEY, ""), items,
				tag.getDoubleOr(MULTIPLIER_KEY, 1.0), duration);
	}

	public static Optional<MarketEvent> fromNetwork(String nameId, String category, List<String> items,
			double multiplier) {
		return build(nameId, category, items, multiplier, 0L);
	}

	private static Optional<MarketEvent> build(String nameId, String categoryValue, List<String> items,
			double multiplier, long duration) {
		Optional<MarketEvents.Name> name = MarketEvents.Name.byId(nameId);

		if (name.isEmpty() || multiplier <= 0.0 || multiplier == 1.0) {
			return Optional.empty();
		}

		String category = categoryValue == null ? "" : categoryValue;

		if (category.isEmpty()) {
			return items.isEmpty() ? Optional.empty()
					: Optional.of(new MarketEvent(name.get(), "", items, multiplier, duration));
		}

		return parseCategory(category)
				.map(parsed -> new MarketEvent(name.get(), parsed.name(), List.of(), multiplier, duration));
	}

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

	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
