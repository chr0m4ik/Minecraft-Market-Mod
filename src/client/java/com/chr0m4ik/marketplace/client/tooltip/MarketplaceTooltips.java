package com.chr0m4ik.marketplace.client.tooltip;

import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderService;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Дописывает в подсказки предметов их цену.
 *
 * <p>Правила:
 * <ul>
 *     <li>обычная цена — везде вне окна торговца;</li>
 *     <li>в окне торговца — цена с уценкой ({@link ItemPrices#TRADER_MULTIPLIER});</li>
 *     <li>у кнопок цены нет, а у кнопки «итоговая цена» подсказка показывает сводку по товару.</li>
 * </ul>
 * Цены клиент читает из того же файла мода, что и сервер, — см. {@link ItemPrices}.
 */
public final class MarketplaceTooltips {
	private MarketplaceTooltips() {
		// Класс только со статическими методами.
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register(MarketplaceTooltips::addPrice);
	}

	private static void addPrice(ItemStack stack, Item.TooltipContext context, TooltipFlag flag, List<Component> lines) {
		if (stack.isEmpty()) {
			return;
		}

		Screen screen = Minecraft.getInstance().gui.screen();

		if (screen instanceof MarketplaceScreen marketplace) {
			MarketplaceMenu menu = marketplace.getMenu();

			if (marketplace.isHoveringButton()) {
				// Кнопка «итоговая цена» — показываем сводку по выставленному товару.
				if (menu.getKind() == WindowKind.TRADER && stack.is(Items.PAPER)) {
					addTraderSummary(lines, menu);
				}

				return;
			}

			// В окне торговца цены показываем с уценкой.
			double multiplier = menu.getKind() == WindowKind.TRADER ? ItemPrices.TRADER_MULTIPLIER : 1.0;
			addPriceLines(lines, stack, multiplier);

			return;
		}

		addPriceLines(lines, stack, 1.0);
	}

	private static void addPriceLines(List<Component> lines, ItemStack stack, double multiplier) {
		double unit = ItemPrices.priceOf(stack.copyWithCount(1)) * multiplier;

		if (unit <= 0.0) {
			return;
		}

		lines.add(Component.translatable("marketplace.tooltip.price.unit", money(unit))
				.withStyle(ChatFormatting.GREEN));

		if (stack.getCount() > 1) {
			double total = ItemPrices.priceOf(stack) * multiplier;
			lines.add(Component.translatable("marketplace.tooltip.price.stack", money(total))
					.withStyle(ChatFormatting.GOLD));
		}
	}

	/** Подсказка кнопки «итоговая цена»: сколько товара, обычная цена и цена с уценкой. */
	private static void addTraderSummary(List<Component> lines, MarketplaceMenu menu) {
		TraderService.Offer offer = menu.currentOffer();

		lines.add(Component.translatable("marketplace.tooltip.trader.count",
				Component.literal(String.valueOf(offer.itemCount()))).withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("marketplace.tooltip.trader.full", money(offer.fullPrice()))
				.withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable("marketplace.tooltip.trader.discounted", money(offer.payout()))
				.withStyle(ChatFormatting.GREEN));
	}

	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount));
	}
}
