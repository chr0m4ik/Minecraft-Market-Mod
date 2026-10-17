package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record MarketplaceStatusPayload(
		long balanceCents,
		long inventoryCents,
		List<SaleEntry> traderSales,
		long worldSeed,
		long gameTime,
		EventData event
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MarketplaceStatusPayload> TYPE =
			new CustomPacketPayload.Type<>(MarketplaceMod.id("status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MarketplaceStatusPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::balanceCents,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::inventoryCents,
			SaleEntry.CODEC.apply(ByteBufCodecs.list()), MarketplaceStatusPayload::traderSales,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::worldSeed,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::gameTime,
			EventData.CODEC, MarketplaceStatusPayload::event,
			MarketplaceStatusPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public record SaleEntry(String itemId, double stacks) {
		public static final StreamCodec<RegistryFriendlyByteBuf, SaleEntry> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, SaleEntry::itemId,
				ByteBufCodecs.DOUBLE, SaleEntry::stacks,
				SaleEntry::new
		);
	}

	public record EventData(String name, String category, List<String> items, double multiplier) {

		public static final EventData NONE = new EventData("", "", List.of(), 1.0);

		public static final StreamCodec<RegistryFriendlyByteBuf, EventData> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, EventData::name,
				ByteBufCodecs.STRING_UTF8, EventData::category,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), EventData::items,
				ByteBufCodecs.DOUBLE, EventData::multiplier,
				EventData::new
		);

		public boolean active() {
			return !this.name.isEmpty();
		}
	}
}
