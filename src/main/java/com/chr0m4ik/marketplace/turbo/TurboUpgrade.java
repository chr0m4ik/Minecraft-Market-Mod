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

/**
 * Уровень «Турбо печки» на предмете и его подпись.
 *
 * <p>Уровень хранится в компоненте предмета {@code minecraft:custom_data} под ключом
 * {@link #LEVEL_TAG} и в NBT блочной сущности под тем же ключом. Так уровень переживает и
 * поломку блока (своя loot table копирует {@code custom_data} из блочной сущности), и
 * постановку блока обратно.
 */
public final class TurboUpgrade {
	/** Ключ уровня: одинаково в NBT блочной сущности и в компоненте предмета. */
	public static final String LEVEL_TAG = "turbo_level";

	/** Ключи названия уровня и его описания в языковых файлах. */
	private static final String NAME_KEY = "marketplace.special.turbo_furnace.name.";
	private static final String LEVEL_KEY = "marketplace.special.turbo_furnace.level.";
	private static final String HINT_KEY = "marketplace.special.turbo_furnace.hint";

	private TurboUpgrade() {
		// Класс только со статическими методами.
	}

	/**
	 * Уровень печки на предмете.
	 *
	 * @return {@code 0}, если это не «Турбо печка»; иначе {@code 1..}{@link TurboFurnace#MAX_LEVEL}.
	 *         Предмет без компонента (например, выданный командой {@code /give}) считается
	 *         печкой 1-го уровня.
	 */
	public static int levelOf(ItemStack stack) {
		return stack.is(TurboFurnace.ITEM) ? levelOfComponents(stack.get(DataComponents.CUSTOM_DATA)) : 0;
	}

	/** Уровень из компонента (или из NBT блочной сущности): пусто и мусор читаются как 1-й уровень. */
	public static int levelOfComponents(@Nullable CustomData data) {
		int level = data == null ? 0 : data.copyTag().getIntOr(LEVEL_TAG, 0);
		return Mth.clamp(level <= 0 ? 1 : level, 1, TurboFurnace.MAX_LEVEL);
	}

	/** Записывает уровень в компонент предмета. */
	public static void setLevel(ItemStack stack, int level) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack,
				tag -> tag.putInt(LEVEL_TAG, Mth.clamp(level, 1, TurboFurnace.MAX_LEVEL)));
	}

	/** Компонент с уровнем — его кладём на предмет при поломке блока. */
	public static CustomData dataOf(int level) {
		CustomData data = CustomData.EMPTY;
		return data.update(tag -> tag.putInt(LEVEL_TAG, Mth.clamp(level, 1, TurboFurnace.MAX_LEVEL)));
	}

	/** Название уровня: «Турбо печка I». */
	public static MutableComponent nameOf(int level) {
		return plain(Component.translatable(NAME_KEY + clamp(level)).withStyle(ChatFormatting.AQUA));
	}

	/** Описание уровня: что за скорость и как получить уровень выше. */
	public static ItemLore loreOf(int level) {
		return new ItemLore(List.of(
				plain(Component.translatable(LEVEL_KEY + clamp(level)).withStyle(ChatFormatting.LIGHT_PURPLE)),
				plain(Component.translatable(HINT_KEY).withStyle(ChatFormatting.GRAY))));
	}

	/** Название и описание предмета. */
	public static void decorate(ItemStack stack, int level) {
		stack.set(DataComponents.CUSTOM_NAME, nameOf(level));
		stack.set(DataComponents.LORE, loreOf(level));
	}

	/**
	 * Название и описание для loot table-пути: то же самое, что {@link #decorate(ItemStack, int)},
	 * но в сборщик компонентов блочной сущности ({@code collectImplicitComponents}).
	 */
	public static void decorate(DataComponentMap.Builder components, int level) {
		components.set(DataComponents.CUSTOM_NAME, nameOf(level));
		components.set(DataComponents.LORE, loreOf(level));
	}

	/** Читает уровень из компонентов при постановке блока (см. {@code BlockItem}). */
	public static int levelOfComponents(DataComponentGetter components) {
		return levelOfComponents(components.get(DataComponents.CUSTOM_DATA));
	}

	private static int clamp(int level) {
		return Mth.clamp(level, 1, TurboFurnace.MAX_LEVEL);
	}

	/** Убирает курсив: игра делает изменённые названия курсивом. */
	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}
}
