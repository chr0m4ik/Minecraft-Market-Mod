package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Пустой пакет «открой рынок», который клиент отправляет серверу при нажатии клавиши {@code I}.
 *
 * <p>Почему пакет нужен: окно создаётся на сервере, а клавиша нажимается на клиенте.
 * Данных в пакете нет — важен сам факт.
 */
public record OpenMarketplacePayload() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<OpenMarketplacePayload> TYPE =
			new CustomPacketPayload.Type<>(MarketplaceMod.id("open_marketplace"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OpenMarketplacePayload> CODEC =
			StreamCodec.unit(new OpenMarketplacePayload());

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
