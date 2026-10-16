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

/**
 * Общее для «Жилкового майнера» и «Бура»: уровень своего зачарования на инструменте,
 * проверка, хватит ли прочности, и подходит ли инструмент для блока.
 *
 * <p>Оба зачарования ломают блоки ванильным циклом
 * ({@code serverPlayer.gameMode.destroyBlock}), который сам тратит прочность, поэтому
 * своего урона инструменту никто не наносит — здесь только считаем, выдержит ли он.
 */
final class MiningSupport {
	/**
	 * Теги «чем добывается». По ним понимаем тип инструмента в руке: кирка берёт камень и его
	 * родню, лопата — землю и песок, топор — дерево, мотыга — листву и прочее рыхлое.
	 * Набор тот же, что у ванильных инструментов в их компоненте {@code minecraft:tool}.
	 */
	private static final Set<TagKey<Block>> MINEABLE_TAGS = Set.of(
			BlockTags.MINEABLE_WITH_PICKAXE,
			BlockTags.MINEABLE_WITH_SHOVEL,
			BlockTags.MINEABLE_WITH_AXE,
			BlockTags.MINEABLE_WITH_HOE);

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
	 * Может ли этот инструмент добыть этот блок. Нужны <b>оба</b> условия:
	 * <ol>
	 *     <li><b>тип инструмента</b> — блок должен лежать в теге «чем добывается» для этого
	 *     инструмента ({@link #MINEABLE_TAGS}); кирка не берёт землю, дерево и песок;</li>
	 *     <li><b>уровень инструмента</b> — если блок требует правильный инструмент, он должен
	 *     подходить: каменная кирка не берёт алмазную руду (это решает ванильная проверка
	 *     {@code isCorrectToolForDrops}, в ней же теги {@code incorrect_for_*_tool}).</li>
	 * </ol>
	 *
	 * <p>Почему нельзя было оставить только {@code Player.hasCorrectToolForDrops}: у блоков без
	 * требования к инструменту (земля, песок, дерево) она возвращает {@code true} для любой кирки,
	 * поэтому бур копал землю и дерево.
	 *
	 * <p>Если тип инструмента не из четырёх наших (например, ножницы), ведём себя как ваниль:
	 * смотрим только на уровень инструмента.
	 */
	static boolean canBreak(ItemStack tool, BlockState state) {
		if (tool.isEmpty()) {
			return false;
		}

		TagKey<Block> tag = mineableTag(tool);

		// Тип инструмента: кирка — только камень и его родня, лопата — земля и песок, и так далее.
		if (tag != null && !state.is(tag)) {
			return false;
		}

		// Уровень инструмента: блок требует правильный инструмент — значит наш должен подходить.
		return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
	}

	/** Тег «чем добывается» по инструменту в руке. {@code null} — тип инструмента не из наших. */
	static TagKey<Block> mineableTag(ItemStack tool) {
		Tool component = tool.get(DataComponents.TOOL);

		if (component == null) {
			return null;
		}

		for (Tool.Rule rule : component.rules()) {
			// Ванильные инструменты объявляют свой тег именно правилом компонента minecraft:tool,
			// поэтому чужой (модовый) инструмент с тем же тегом тоже подойдёт.
			if (rule.blocks() instanceof HolderSet.Named<Block> named && MINEABLE_TAGS.contains(named.key())) {
				return named.key();
			}
		}

		return null;
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
