package com.chr0m4ik.marketplace.turbo;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

import java.util.List;

public final class TurboUpgrade {

	public static final String LEVEL_TAG = "turbo_level";

	private static final String NAME_KEY = "marketplace.special.turbo_furnace.name.";
	private static final String LEVEL_KEY = "marketplace.special.turbo_furnace.level.";
	private static final String HINT_KEY = "marketplace.special.turbo_furnace.hint";

	private TurboUpgrade() {
		// Класс только со статическими методами.
	}

	public static int levelOf(ItemStack stack) {
		return stack.is(TurboFurnace.ITEM) ? levelOfComponents(stack.get(DataComponents.CUSTOM_DATA)) : 0;
	}

	public static int levelOfComponents(@Nullable CustomData data) {
		int level = data == null ? 0 : data.copyTag().getIntOr(LEVEL_TAG, 0);
		return Mth.clamp(level <= 0 ? 1 : level, 1, TurboFurnace.MAX_LEVEL);
	}

	public static void setLevel(ItemStack stack, int level) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack,
				tag -> tag.putInt(LEVEL_TAG, Mth.clamp(level, 1, TurboFurnace.MAX_LEVEL)));
	}

	public static CustomData dataOf(int level) {
		CustomData data = CustomData.EMPTY;
		return data.update(tag -> tag.putInt(LEVEL_TAG, Mth.clamp(level, 1, TurboFurnace.MAX_LEVEL)));
	}

	public static MutableComponent nameOf(int level) {
		return plain(Component.translatable(NAME_KEY + clamp(level)).withStyle(ChatFormatting.AQUA));
	}

	public static ItemLore loreOf(int level) {
		return new ItemLore(List.of(
				plain(Component.translatable(LEVEL_KEY + clamp(level)).withStyle(ChatFormatting.LIGHT_PURPLE)),
				plain(Component.translatable(HINT_KEY).withStyle(ChatFormatting.GRAY))));
	}

	public static void decorate(ItemStack stack, int level) {
		stack.set(DataComponents.CUSTOM_NAME, nameOf(level));
		stack.set(DataComponents.LORE, loreOf(level));
	}

	public static void decorate(DataComponentMap.Builder components, int level) {
		components.set(DataComponents.CUSTOM_NAME, nameOf(level));
		components.set(DataComponents.LORE, loreOf(level));
	}

	public static int levelOfComponents(DataComponentGetter components) {
		return levelOfComponents(components.get(DataComponents.CUSTOM_DATA));
	}

	public static boolean isTurbo(ItemStack stack) {
		return stack.is(TurboFurnace.ITEM);
	}

	public static int resultLevel(ItemStack left, ItemStack right) {
		int first = levelOf(left);
		int second = levelOf(right);

		if (first == 0 || first != second || first >= TurboFurnace.MAX_LEVEL) {
			return 0;
		}

		return first + 1;
	}

	public static int levelCost(int newLevel) {
		return switch (newLevel) {
			case 2 -> 25;
			case 3 -> 50;
			case 4 -> 100;
			default -> 0;
		};
	}

	public static ItemStack upgraded(ItemStack base, int level) {
		ItemStack result = base.copyWithCount(1);
		setLevel(result, level);
		decorate(result, level);
		return result;
	}

	private static int clamp(int level) {
		return Mth.clamp(level, 1, TurboFurnace.MAX_LEVEL);
	}

	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}
}
