package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Пакет «состояние игрока»: баланс, стоимость инвентаря и закупки торговца.
 *
 * <p>Нужен плашке на HUD и подсказкам предметов: клиент должен знать, что торговец уже покупал,
 * чтобы показывать у него те же цены, что и сервер.
 *
 * <p>Деньги передаём в копейках (целыми числами), чтобы не терять точность.
 *
 * <p>Здесь же идут зерно мира и игровое время сервера: по ним клиент считает микро-дрейф цен
 * ({@code PriceDrift}) по той же формуле, что и сервер. Иначе подсказки расходились бы с тем,
 * что игрок платит на самом деле.
 */
public record MarketplaceStatusPayload(
		long balanceCents,
		long inventoryCents,
		List<SaleEntry> traderSales,
		long worldSeed,
		long gameTime
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MarketplaceStatusPayload> TYPE =
			new CustomPacketPayload.Type<>(MarketplaceMod.id("status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MarketplaceStatusPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::balanceCents,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::inventoryCents,
			SaleEntry.CODEC.apply(ByteBufCodecs.list()), MarketplaceStatusPayload::traderSales,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::worldSeed,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::gameTime,
			MarketplaceStatusPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Одна строка закупок торговца: сколько стаков предмета он купил. */
	public record SaleEntry(String itemId, double stacks) {
		public static final StreamCodec<RegistryFriendlyByteBuf, SaleEntry> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, SaleEntry::itemId,
				ByteBufCodecs.DOUBLE, SaleEntry::stacks,
				SaleEntry::new
		);
	}
}
