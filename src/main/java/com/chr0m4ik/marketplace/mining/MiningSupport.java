package com.chr0m4ik.marketplace.mining;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Общее для «Жилкового майнера» и «Бура»: уровень своего зачарования на инструменте
 * и проверка, хватит ли прочности.
 *
 * <p>Оба зачарования ломают блоки ванильным циклом
 * ({@code serverPlayer.gameMode.destroyBlock}), который сам тратит прочность, поэтому
 * своего урона инструменту никто не наносит — здесь только считаем, выдержит ли он.
 */
final class MiningSupport {
	private MiningSupport() {
		// Класс только со статическими методами.
	}

	/**
	 * Уровень зачарования на инструменте ({@code 0} — зачарования нет).
	 *
	 * <p>Держатель берём из реестров мира каждый раз: реестр зачарований в 26.3 динамический
	 * и пересоздаётся при {@code /reload} — устаревший держатель перестал бы находить зачарование.
	 */
	static int level(ServerPlayer player, ResourceKey<Enchantment> key, ItemStack tool) {
		return player.registryAccess()
				.lookup(Registries.ENCHANTMENT)
				.flatMap(lookup -> lookup.get(key))
				.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, tool))
				.orElse(0);
	}

	/**
	 * Хватит ли прочности на {@code blocks} ударов так, чтобы инструмент остался целым.
	 *
	 * <p>Урон считаем той же формулой, что и игра ({@link EnchantmentHelper#processDurabilityChange}),
	 * поэтому «Прочность» (Unbreaking) учитывается. Оставляем минимум одну единицу: при
	 * {@code damage >= maxDamage} игра уничтожает предмет, и игрок теряет инструмент.
	 * В креативе прочность не тратится вовсе.
	 */
	static boolean hasDurabilityFor(ServerPlayer player, ItemStack tool, int blocks) {
		if (player.hasInfiniteMaterials() || !tool.isDamageableItem()) {
			return true;
		}

		int cost = EnchantmentHelper.processDurabilityChange((ServerLevel) player.level(), tool, blocks);
		return tool.getDamageValue() + cost < tool.getMaxDamage();
	}
}
