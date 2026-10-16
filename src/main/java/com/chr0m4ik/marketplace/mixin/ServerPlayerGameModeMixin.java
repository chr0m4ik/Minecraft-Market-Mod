package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.mining.MagnetHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Запоминает, какой игрок ломает блок, пока идёт ванильный цикл {@code destroyBlock}.
 *
 * <p>Нужно «Магниту»: у {@code Block.popResource} (там перехватывается дроп) игрока нет — только
 * позиция и предмет. Зато через {@code destroyBlock} проходит <b>любое</b> ломание блока игроком,
 * в том числе вложенные вызовы «Жилкового майнера» и слои «Бура», поэтому игрок известен и для
 * каждого блока цепочки.
 *
 * <p>Вложенность считается: счётчик растёт на входе и падает на выходе, а игрок и инструмент
 * запоминаются только на самом внешнем вызове — том, который начал сам игрок.
 *
 * <p>Миксин серверный и лежит в общем конфиге миксинов ({@code marketplace.common.mixins.json}):
 * в клиентском он не загрузился бы на выделенном сервере.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	/** Игрок, которому принадлежит этот режим игры. Поле ванильного класса. */
	@Shadow
	@Final
	protected ServerPlayer player;

	/** Начало ломания блока: если это начало нажатия игрока — восстанавливаем запас магнита. */
	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void marketplace$magnetBegin(BlockPos pos, CallbackInfoReturnable<Boolean> info) {
		MagnetHandler.beginBreak(this.player);
	}

	/** Конец ломания (в том числе отменённого чужой защитой): отпускаем игрока. */
	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void marketplace$magnetEnd(BlockPos pos, CallbackInfoReturnable<Boolean> info) {
		MagnetHandler.endBreak();
	}
}
