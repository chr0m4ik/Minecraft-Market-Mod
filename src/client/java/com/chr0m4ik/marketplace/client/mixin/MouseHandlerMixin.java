package com.chr0m4ik.marketplace.client.mixin;

import com.chr0m4ik.marketplace.client.MarketplaceCursor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Не даёт игре перебросить курсор в центр экрана, пока открыто окно магазина.
 *
 * <p>При открытии нового окна игра на мгновение «захватывает» мышь, а {@code MouseHandler.grabMouse}
 * ставит курсор ровно в центр окна (ширина/2, высота/2) и вызывает {@code InputConstants.grabMouse}.
 * Из-за этого при перелистывании страниц или переходе между окнами магазина курсор прыгал в центр —
 * в хардкоре это могло стоить игроку нескольких часов.
 *
 * <p>Мы запоминаем настоящую позицию курсора на каждом движении и возвращаем её сразу после захвата.
 * Работает <b>только</b> когда открыт наш экран: обычные сундуки и интерфейсы игры ведут себя как
 * раньше. Миксин чисто клиентский — на сервере он не загружается.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	private double xpos;

	@Shadow
	private double ypos;

	/** Запоминаем курсор до того, как игра его куда-нибудь денет. */
	@Inject(method = "onMove", at = @At("HEAD"))
	private void marketplace$rememberCursor(long window, double x, double y, double dx, double dy,
			CallbackInfo info) {
		MarketplaceCursor.remember(x, y);
	}

	/**
	 * После захвата мыши возвращаем курсор туда, где он был.
	 * Срабатывает только при открытом окне магазина.
	 */
	@Inject(method = "grabMouse", at = @At("RETURN"))
	private void marketplace$restoreCursor(CallbackInfo info) {
		if (!MarketplaceCursor.shouldKeep() || !MarketplaceCursor.hasPosition()) {
			return;
		}

		this.xpos = MarketplaceCursor.x();
		this.ypos = MarketplaceCursor.y();
		InputConstants.grabMouse(Minecraft.getInstance().getWindow(), this.xpos, this.ypos);
	}
}
