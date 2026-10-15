package com.chr0m4ik.marketplace.client.screen;

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

/**
 * Экран рынка. Один класс на все окна мода: текстура и высота выбираются по виду окна.
 *
 * <p>Своих текстур у мода нет, поэтому используем ванильные:
 * сундук ({@code shulker_box.png}, 3 ряда) и двойной сундук ({@code generic_54.png}, 6 рядов).
 */
public class MarketplaceScreen extends AbstractContainerScreen<MarketplaceMenu> {
	/** Текстура двойного сундука: 6 рядов слотов + инвентарь игрока. */
	private static final Identifier CHEST_TEXTURE =
			Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	/** Текстура обычного сундука: 3 ряда слотов + инвентарь игрока. */
	private static final Identifier SMALL_CHEST_TEXTURE =
			Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");

	/** Ванильные спрайты подсказки — из них собираем плашку с деньгами. */
	private static final Identifier PLATE_BACKGROUND = Identifier.withDefaultNamespace("tooltip/background");
	private static final Identifier PLATE_FRAME = Identifier.withDefaultNamespace("tooltip/frame");

	/** Цвет суммы на плашке — золотой. */
	private static final int MONEY_COLOR = 0xFFFFD700;
	/** Отступ текста внутри плашки. */
	private static final int PLATE_PADDING = 4;
	/** Высота плашки. */
	private static final int PLATE_HEIGHT = 16;
	/** Насколько плашка отступает вправо от окна. */
	private static final int PLATE_MARGIN = 6;

	/** Полупрозрачная зелёная подсветка ячеек рынка, которые ещё не подтвердили. */
	private static final int STAGED_TINT = 0x5500FF00;

	public MarketplaceScreen(MarketplaceMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, WindowKind.IMAGE_WIDTH, menu.getKind().imageHeight());
	}

	/**
	 * Наведён ли курсор на слот-кнопку. Нужно подсказкам предметов: у кнопок своя подсказка,
	 * а не цена.
	 */
	public boolean isHoveringButton() {
		return this.hoveredSlot != null && this.menu.isButtonSlot(this.hoveredSlot.index);
	}

	/** Номер слота под курсором ({@code -1}, если курсор ни на чём). */
	public int getHoveredSlotIndex() {
		return this.hoveredSlot == null ? -1 : this.hoveredSlot.index;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		// Зелёная подсветка ложится под предметы, поэтому рисуем её сразу после фона окна.
		this.extractStagedCells(graphics);

		int rows = this.menu.getKind().rows();
		int x = this.leftPos;
		int y = this.topPos;

		if (rows == 3) {
			// 3 ряда целиком лежат в текстуре сундука.
			graphics.blit(RenderPipelines.GUI_TEXTURED, SMALL_CHEST_TEXTURE,
					x, y, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
		} else {
			// Двойной сундук: верхняя часть (ряды слотов) и отдельно инвентарь игрока.
			int containerHeight = rows * 18 + 17;
			graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE,
					x, y, 0.0F, 0.0F, this.imageWidth, containerHeight, 256, 256);
			graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE,
					x, y + containerHeight, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
		}
	}

	/**
	 * Рисуем плашку с деньгами справа от окна. Делаем это до подсказок предметов,
	 * чтобы подсказка всегда была сверху.
	 */
	@Override
	public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractContents(graphics, mouseX, mouseY, partialTick);
		this.extractMoneyPlate(graphics);
	}

	/**
	 * Подсвечивает зелёным предметы, которые игрок только что положил и ещё не подтвердил.
	 * Так видно, что уже выставлено на продажу (ордер), а что пока лежит «на столе».
	 */
	private void extractStagedCells(GuiGraphicsExtractor graphics) {
		if (this.menu.getKind() != WindowKind.SELL) {
			return;
		}

		for (int i = 0; i < this.menu.tradeSlotCount(); i++) {
			ItemStack stack = this.menu.getTradeContainer().getItem(i);

			if (stack.isEmpty() || this.menu.isCellOrder(i)) {
				continue;
			}

			int x = this.leftPos + 8 + (i % 9) * 18;
			int y = this.topPos + 18 + (i / 9) * 18;
			graphics.fill(x, y, x + 16, y + 16, STAGED_TINT);
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
