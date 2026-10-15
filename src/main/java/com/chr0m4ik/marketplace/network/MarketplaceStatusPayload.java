package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Пакет «состояние игрока»: баланс, стоимость инвентаря и текущее состояние рынка.
 *
 * <p>Нужен плашке на HUD и подсказкам предметов: клиент должен знать, что игрок уже продавал,
 * чтобы показывать те же цены, что и сервер.
 *
 * <p>Деньги передаём в копейках (целыми числами), чтобы не терять точность.
 */
public record MarketplaceStatusPayload(
		long balanceCents,
		long inventoryCents,
		List<MarketEntry> market
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MarketplaceStatusPayload> TYPE =
			new CustomPacketPayload.Type<>(MarketplaceMod.id("status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MarketplaceStatusPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::balanceCents,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::inventoryCents,
			MarketEntry.CODEC.apply(ByteBufCodecs.list()), MarketplaceStatusPayload::market,
			MarketplaceStatusPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Одна строка рынка: сколько стаков предмета продано. */
	public record MarketEntry(String itemId, double stacks) {
		public static final StreamCodec<RegistryFriendlyByteBuf, MarketEntry> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, MarketEntry::itemId,
				ByteBufCodecs.DOUBLE, MarketEntry::stacks,
				MarketEntry::new
		);
	}
}
