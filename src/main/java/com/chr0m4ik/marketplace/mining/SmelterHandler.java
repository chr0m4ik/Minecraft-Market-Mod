package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

public final class SmelterHandler {

	private static final ResourceKey<Enchantment> SMELTER =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("smelter"));

	private SmelterHandler() {
		// Класс только со статическими методами.
	}

	public static void register() {
		// MarketplaceMod.LOGGER.info("Плавильня: дроп руды заменяется результатом плавки, опыт не добавляется");
	}

	public static ItemStack smelt(ItemStack itemStack) {
		ServerPlayer player = BreakContext.player();
		Block block = BreakContext.block();

		if (player == null || block == null || itemStack.isEmpty() || !VeinMinerHandler.isOre(block)) {
			return itemStack;
		}

		if (MiningSupport.level(player, SMELTER, BreakContext.tool()) <= 0) {
			return itemStack;   // на инструменте нет плавильни — дроп обычный
		}

		ServerLevel level = (ServerLevel) player.level();
		SingleRecipeInput input = new SingleRecipeInput(itemStack);
		Optional<RecipeHolder<SmeltingRecipe>> recipe = level.getServer()
				.getRecipeManager()
				.getRecipeFor(RecipeType.SMELTING, input, level);

		if (recipe.isEmpty()) {

			return itemStack;
		}

		ItemStack result = recipe.get().value().assemble(input);

		if (result.isEmpty()) {
			return itemStack;
		}

		result.setCount(result.getCount() * itemStack.getCount());
		return result;
	}
}
