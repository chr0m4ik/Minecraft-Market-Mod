package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.mining.BreakContext;
import com.chr0m4ik.marketplace.mining.MagnetHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Запоминает контекст ломания блока: какой игрок ломает, чем и какой это блок.
 *
 * <p>Нужно зачарованиям, которые работают с дропом. У {@code Block.popResource} (там
 * перехватывается дроп) нет ни игрока, ни блока — только позиция и предмет. Зато через
 * {@code destroyBlock} проходит <b>любое</b> ломание блока игроком, в том числе вложенные вызовы
 * «Жилкового майнера» и слои «Бура», поэтому контекст известен и для каждого блока цепочки.
 * Хранит его {@link BreakContext}, а читают «Магнит» и «Плавильня».
 *
 * <p>Вложенность считает {@link BreakContext}: на входе он кладёт блок в стопку, на выходе
 * забирает. Самое внешнее ломание (то, которое начал сам игрок) — это новое нажатие, поэтому
 * именно на нём восстанавливается запас магнита.
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

	/** Начало ломания блока: запоминаем игрока, инструмент и блок (в том числе для «Плавильни»). */
	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void marketplace$beginBreak(BlockPos pos, CallbackInfoReturnable<Boolean> info) {
		// Состояние блока читаем здесь, до его удаления: по нему «Плавильня» понимает, руда ли это.
		BlockState state = this.player.level().getBlockState(pos);

		if (BreakContext.begin(this.player, state.getBlock())) {
			// Самое внешнее ломание — новое нажатие игрока: у магнита восстанавливается запас.
			MagnetHandler.onSwingStart(this.player);
		}
	}

	/** Конец ломания (в том числе отменённого чужой защитой): закрываем контекст. */
	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void marketplace$endBreak(BlockPos pos, CallbackInfoReturnable<Boolean> info) {
		BreakContext.end();
	}
}
