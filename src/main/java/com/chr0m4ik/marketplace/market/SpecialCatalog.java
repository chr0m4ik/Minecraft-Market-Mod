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

public final class SpecialCatalog {

	private static final String VEIN_MINER = "vein_miner";

	private static final String VEIN_DRILL = "vein_drill";

	private static final String MAGNET = "magnet";

	private static final String SMELTER = "smelter";

	private static final String TURBO_FURNACE = "turbo_furnace";

	private record Entry(String path, int level, double price, @Nullable Item item) {
	}

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

	private static ItemStack blockItem(Entry entry) {
		ItemStack stack = new ItemStack(entry.item());
		TurboUpgrade.setLevel(stack, entry.level());
		TurboUpgrade.decorate(stack, entry.level());

		return stack;
	}

	private static Optional<ItemStack> book(HolderLookup.Provider registries, Entry entry) {
		Optional<Holder.Reference<Enchantment>> holder = registries
				.lookupOrThrow(Registries.ENCHANTMENT)
				.get(ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id(entry.path())));

		if (holder.isEmpty()) {

			MarketplaceMod.LOGGER.error("Особый предмет: зачарование marketplace:{} не найдено", entry.path());
			return Optional.empty();
		}

		ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
		ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		enchantments.set(holder.get(), entry.level());
		stack.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());

		stack.set(DataComponents.CUSTOM_NAME,
				plain(Component.translatable(nameKey(entry)).withStyle(ChatFormatting.AQUA)));
		stack.set(DataComponents.LORE, new ItemLore(loreOf(entry)));
		return Optional.of(stack);
	}

	private static List<Component> loreOf(Entry entry) {
		return List.of(
				plain(Component.translatable(levelNameKey(entry)).withStyle(ChatFormatting.LIGHT_PURPLE)),
				plain(Component.translatable("marketplace.special." + entry.path() + ".hint")
						.withStyle(ChatFormatting.GRAY)));
	}

	private static String nameKey(Entry entry) {
		return "marketplace.special." + entry.path() + ".name." + entry.level();
	}

	private static String levelNameKey(Entry entry) {
		return "marketplace.special." + entry.path() + ".level." + entry.level();
	}

	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	public static boolean isSpecial(Holder<Enchantment> holder) {
		return !entriesOf(holder).isEmpty();
	}

	public static OptionalDouble priceOf(ItemStack stack) {
		return entryOf(stack).map(entry -> OptionalDouble.of(entry.price())).orElseGet(OptionalDouble::empty);
	}

	public static OptionalDouble turboPriceOf(int level) {
		return ENTRIES.stream()
				.filter(entry -> entry.item() == TurboFurnace.ITEM && entry.level() == level)
				.map(entry -> OptionalDouble.of(entry.price()))
				.findFirst()
				.orElseGet(OptionalDouble::empty);
	}

	public static boolean isLevelledBlock(ItemStack stack) {
		return TurboUpgrade.levelOf(stack) > 0;
	}

	private static Optional<Entry> entryOf(ItemStack stack) {

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

		return entriesOf(holder).stream().filter(entry -> entry.level() == level).findFirst();
	}

	private static List<Entry> entriesOf(Holder<Enchantment> holder) {
		String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");

		if (id.isEmpty()) {
			return List.of();
		}

		// случайно, но проверять его незачем.
		return ENTRIES.stream()
				.filter(entry -> entry.item() == null && fullId(entry).equals(id))
				.toList();
	}

	private static String fullId(Entry entry) {
		return MarketplaceMod.MOD_ID + ":" + entry.path();
	}
}
