package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.mining.MagnetHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Отдаёт дроп блока зачарованию «Магнит» <b>до</b> появления предмета в мире.
 *
 * <p>Ваниль выбрасывает дроп так: {@code Block.playerDestroy} → {@code Block.dropResources} →
 * {@code Block.popResource(Level, BlockPos, ItemStack)}, и уже внутри {@code popResource} создаётся
 * {@code ItemEntity}. Мы встаём в самое начало {@code popResource} и урезаем стек на то, что магнит
 * забрал в инвентарь. Остаток (и весь стек, если магнита нет или запас исчерпан) ваниль выбрасывает
 * как обычно — поэтому предмет не появляется в мире «на мгновение» и не теряется.
 *
 * <p>Миксин серверный, поэтому лежит в общем конфиге ({@code marketplace.common.mixins.json}),
 * а не в клиентском: иначе на выделенном сервере он бы просто не загрузился.
 */
@Mixin(Block.class)
public abstract class BlockMixin {
	/**
	 * Перехват выброса предмета из блока. Позиция не нужна: предмет кладётся в инвентарь, а не
	 * подбирается с земли; если места нет, игра сама выбросит его под ногами игрока.
	 */
	@Inject(
			method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
			at = @At("HEAD"))
	private static void marketplace$magnet(Level level, BlockPos pos, ItemStack itemStack, CallbackInfo info) {
		MagnetHandler.absorb(level, itemStack);
	}
}
