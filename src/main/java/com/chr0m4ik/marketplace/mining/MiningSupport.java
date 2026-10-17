package com.chr0m4ik.marketplace.mining;

import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

final class MiningSupport {

	private static final Set<TagKey<Block>> MINEABLE_TAGS = Set.of(
			BlockTags.MINEABLE_WITH_PICKAXE,
			BlockTags.MINEABLE_WITH_SHOVEL,
			BlockTags.MINEABLE_WITH_AXE,
			BlockTags.MINEABLE_WITH_HOE);

	private MiningSupport() {
		// Класс только со статическими методами.
	}

	static int level(ServerPlayer player, ResourceKey<Enchantment> key, ItemStack tool) {
		return player.registryAccess()
				.lookup(Registries.ENCHANTMENT)
				.flatMap(lookup -> lookup.get(key))
				.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, tool))
				.orElse(0);
	}

	static boolean canBreak(ItemStack tool, BlockState state) {
		if (tool.isEmpty()) {
			return false;
		}

		TagKey<Block> tag = mineableTag(tool);

		if (tag != null && !state.is(tag)) {
			return false;
		}

		return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
	}

	static TagKey<Block> mineableTag(ItemStack tool) {
		Tool component = tool.get(DataComponents.TOOL);

		if (component == null) {
			return null;
		}

		for (Tool.Rule rule : component.rules()) {

			if (rule.blocks() instanceof HolderSet.Named<Block> named && MINEABLE_TAGS.contains(named.key())) {
				return named.key();
			}
		}

		return null;
	}

	static boolean hasDurabilityFor(ServerPlayer player, ItemStack tool, int blocks) {
		if (player.hasInfiniteMaterials() || !tool.isDamageableItem()) {
			return true;
		}

		int cost = EnchantmentHelper.processDurabilityChange((ServerLevel) player.level(), tool, blocks);
		return tool.getDamageValue() + cost < tool.getMaxDamage();
	}
}
