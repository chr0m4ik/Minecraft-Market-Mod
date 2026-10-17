package com.chr0m4ik.marketplace.turbo;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

import java.util.Set;

public final class TurboFurnace {

	public static final int MAX_LEVEL = 4;

	private static final float[] SPEED = {1.0F, 1.25F, 1.7F, 2.5F, 5.0F};

	private static final ResourceKey<Block> BLOCK_KEY =
			ResourceKey.create(Registries.BLOCK, MarketplaceMod.id("turbo_furnace"));

	private static final ResourceKey<Item> ITEM_KEY =
			ResourceKey.create(Registries.ITEM, MarketplaceMod.id("turbo_furnace"));

	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY,
			new TurboFurnaceBlock(BlockBehaviour.Properties.of()
					.setId(BLOCK_KEY)
					.mapColor(MapColor.STONE)
					.instrument(NoteBlockInstrument.BASEDRUM)
					.requiresCorrectToolForDrops()
					.strength(3.5F)
					.lightLevel(state -> state.getValue(TurboFurnaceBlock.LIT) ? 13 : 0)));

	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new BlockItem(BLOCK, new Item.Properties()
					.setId(ITEM_KEY)
					.useBlockDescriptionPrefix()
					.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

	public static final BlockEntityType<TurboFurnaceBlockEntity> BLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			MarketplaceMod.id("turbo_furnace"),
			new BlockEntityType<>(TurboFurnaceBlockEntity::new, Set.of(BLOCK)));

	private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("functional_blocks"));

	private TurboFurnace() {
		// Класс только со статическими полями и методами.
	}

	public static void register() {

		if (ITEM instanceof BlockItem blockItem) {
			blockItem.registerBlocks(Item.BY_BLOCK, ITEM);
		}

		CreativeModeTabEvents.modifyOutputEvent(FUNCTIONAL_BLOCKS)
				.register(output -> output.insertAfter(Items.FURNACE, new ItemStack(ITEM)));
	}

	public static float speedOf(int level) {
		return SPEED[Mth.clamp(level, 1, MAX_LEVEL)];
	}
}
