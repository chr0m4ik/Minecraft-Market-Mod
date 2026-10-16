package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.mining.MagnetHandler;
import com.chr0m4ik.marketplace.mining.SmelterHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Отдаёт дроп блока зачарованиям «Плавильня» и «Магнит» <b>до</b> появления предмета в мире.
 *
 * <p>Ваниль выбрасывает дроп так: {@code Block.playerDestroy} → {@code Block.dropResources} →
 * {@code Block.popResource(Level, BlockPos, ItemStack)}, и уже внутри {@code popResource} создаётся
 * {@code ItemEntity}. Мы встаём в самое начало {@code popResource} и <b>подменяем стек</b>, который
 * дальше пойдёт в сущность предмета: что «Плавильня» переплавила, а «Магнит» забрал в инвентарь,
 * в мире не появится вовсе. Остаток (и весь стек, если зачарований нет) ваниль выбрасывает
 * как обычно — поэтому предмет не появляется в мире «на мгновение» и не теряется.
 *
 * <p>Почему {@code @ModifyVariable}, а не {@code @Inject}: зачарования меняют сам <b>предмет</b>
 * (руда → слиток), а у {@code ItemStack} тип предмета неизменяемый — подменить стек можно только
 * заменой аргумента. Почему оба зачарования в одной точке: порядок важен («сначала плавим, потом
 * кладём в инвентарь»), а два отдельных перехвата одного вызова выполнялись бы в неопределённом
 * порядке и могли бы, например, убрать в инвентарь ещё не переплавленную руду.
 *
 * <p>Миксин серверный, поэтому лежит в общем конфиге ({@code marketplace.common.mixins.json}),
 * а не в клиентском: иначе на выделенном сервере он бы просто не загрузился.
 */
@Mixin(Block.class)
public abstract class BlockMixin {
	/** Перехват выброса предмета из блока. Позиция не нужна: игрока и блок даёт {@code BreakContext}. */
	@ModifyVariable(
			method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
			at = @At("HEAD"),
			argsOnly = true)
	private static ItemStack marketplace$specialDrop(ItemStack itemStack) {
		ItemStack result = SmelterHandler.smelt(itemStack);
		MagnetHandler.absorb(result);
		return result;
	}
}
