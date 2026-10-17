package com.chr0m4ik.marketplace.client.screen;

import com.chr0m4ik.marketplace.client.MarketplaceCursor;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class MarketplaceScreen extends AbstractContainerScreen<MarketplaceMenu> {

	private static final Identifier CHEST_TEXTURE =
			Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

	private static final Identifier SMALL_CHEST_TEXTURE =
			Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");

	private static final Identifier PLATE_BACKGROUND = Identifier.withDefaultNamespace("tooltip/background");
	private static final Identifier PLATE_FRAME = Identifier.withDefaultNamespace("tooltip/frame");

	private static final int MONEY_COLOR = 0xFFFFD700;

	private static final int PLATE_PADDING = 4;

	private static final int PLATE_HEIGHT = 16;

	private static final int PLATE_MARGIN = 6;

	private static final int ORDER_FRAME = 0xFFFFD700;

	public MarketplaceScreen(MarketplaceMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, WindowKind.IMAGE_WIDTH, menu.getKind().imageHeight());
	}

	@Override
	protected void init() {
		super.init();
		MarketplaceCursor.keep(true);
	}

	@Override
	public void removed() {
		super.removed();
		MarketplaceCursor.keep(false);
	}

	public boolean isHoveringButton() {
		return this.hoveredSlot != null && this.menu.isButtonSlot(this.hoveredSlot.index);
	}

	public int getHoveredSlotIndex() {
		return this.hoveredSlot == null ? -1 : this.hoveredSlot.index;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);

		this.extractStagedCells(graphics);

		int rows = this.menu.getKind().rows();
		int x = this.leftPos;
		int y = this.topPos;

		if (rows == 3) {
			// 3 ряда целиком лежат в текстуре сундука.
			graphics.blit(RenderPipelines.GUI_TEXTURED, SMALL_CHEST_TEXTURE,
					x, y, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
		} else {

			int containerHeight = rows * 18 + 17;
			graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE,
					x, y, 0.0F, 0.0F, this.imageWidth, containerHeight, 256, 256);
			graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE,
					x, y + containerHeight, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
		}
	}

	@Override
	public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractContents(graphics, mouseX, mouseY, partialTick);
		this.extractMoneyPlate(graphics);

		// Справа вверху — номер страницы магазина.
		if (this.menu.getKind() == WindowKind.BUY_LIST) {
			Component page = Component.translatable("marketplace.gui.buy.page",
					Component.literal(String.valueOf(this.menu.getShopPage())),
					Component.literal(String.valueOf(this.menu.getShopPages())));
			int width = this.font.width(page);
			graphics.text(this.font, page, this.leftPos + this.imageWidth - width - 8, this.topPos + 6,
					0x404040, false);
		}
	}

	private void extractStagedCells(GuiGraphicsExtractor graphics) {
		if (this.menu.getKind() != WindowKind.SELL) {
			return;
		}

		for (int i = 0; i < this.menu.tradeSlotCount(); i++) {
			if (!this.menu.isCellOrder(i) || this.menu.getTradeContainer().getItem(i).isEmpty()) {
				continue;
			}

			int x = this.leftPos + 8 + (i % 9) * 18 - 1;
			int y = this.topPos + 18 + (i / 9) * 18 - 1;
			graphics.fill(x, y, x + 18, y + 1, ORDER_FRAME);
			graphics.fill(x, y + 17, x + 18, y + 18, ORDER_FRAME);
			graphics.fill(x, y + 1, x + 1, y + 17, ORDER_FRAME);
			graphics.fill(x + 17, y + 1, x + 18, y + 17, ORDER_FRAME);
		}
	}

	private void extractMoneyPlate(GuiGraphicsExtractor graphics) {
		Component amount = Component.literal(MoneyFormat.format(this.menu.getBalance()));
		int plateWidth = this.font.width(amount) + PLATE_PADDING * 2;
		int x = this.leftPos + this.imageWidth + PLATE_MARGIN;
		int y = this.topPos + (this.imageHeight - PLATE_HEIGHT) / 2;

		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PLATE_BACKGROUND, x, y, plateWidth, PLATE_HEIGHT);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PLATE_FRAME, x, y, plateWidth, PLATE_HEIGHT);
		graphics.text(this.font, amount, x + PLATE_PADDING, y + PLATE_PADDING, MONEY_COLOR, true);
	}
}
