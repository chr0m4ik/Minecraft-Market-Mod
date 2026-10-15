package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Пакет «состояние игрока»: сколько у него денег на счету и сколько стоит всё,
 * что лежит в инвентаре (по цене продажи за 1x). Сервер отправляет его клиенту,
 * чтобы плашка на HUD показывала свежие числа.
 *
 * <p>Деньги передаём в копейках (целыми числами), чтобы не терять точность.
 */
public record MarketplaceStatusPayload(long balanceCents, long inventoryCents) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MarketplaceStatusPayload> TYPE =
			new CustomPacketPayload.Type<>(MarketplaceMod.id("status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MarketplaceStatusPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::balanceCents,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::inventoryCents,
			MarketplaceStatusPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
