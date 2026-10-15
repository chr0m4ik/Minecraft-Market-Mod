package com.chr0m4ik.marketplace.client.hud;

import com.chr0m4ik.marketplace.client.ClientMarketplaceStatus;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Плашка с деньгами на HUD (в правом верхнем углу).
 *
 * <p>Две строки: сколько денег на счету и сколько стоит всё, что лежит в инвентаре
 * (по цене продажи за 1x). Данные присылает сервер — см. {@code MarketplaceStatusSync}.
 *
 * <p>Фон — ванильные спрайты подсказки, своих текстур у мода нет.
 */
public class MoneyHudElement implements HudElement {
	private static final Identifier PLATE_BACKGROUND = Identifier.withDefaultNamespace("tooltip/background");
	private static final Identifier PLATE_FRAME = Identifier.withDefaultNamespace("tooltip/frame");

	private static final int PADDING = 4;
	private static final int MARGIN = 4;
	private static final int LINE_HEIGHT = 10;
	private static final int BALANCE_COLOR = 0xFFFFD700;
	private static final int INVENTORY_COLOR = 0xFF55FF55;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null || client.gui.hud.isHidden() || !ClientMarketplaceStatus.hasData()) {
			return;
		}

		Font font = client.font;
		Component balanceLine = Component.translatable("marketplace.hud.balance",
				MoneyFormat.format(ClientMarketplaceStatus.balance()));
		Component inventoryLine = Component.translatable("marketplace.hud.inventory",
				MoneyFormat.format(ClientMarketplaceStatus.inventoryValue()));

		int width = Math.max(font.width(balanceLine), font.width(inventoryLine)) + PADDING * 2;
		int height = LINE_HEIGHT * 2 + PADDING * 2;
		int x = graphics.guiWidth() - width - MARGIN;
		int y = MARGIN;

		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PLATE_BACKGROUND, x, y, width, height);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PLATE_FRAME, x, y, width, height);
		graphics.text(font, balanceLine, x + PADDING, y + PADDING, BALANCE_COLOR, true);
		graphics.text(font, inventoryLine, x + PADDING, y + PADDING + LINE_HEIGHT, INVENTORY_COLOR, true);
	}
}
