package com.chr0m4ik.marketplace.collector;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

import java.util.List;

/** Собиратель: забирает спавнер вместе с мобом внутри. */
public final class CollectorItem extends Item {

	public static final ResourceKey<Item> ITEM_KEY =
			ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MarketplaceMod.MOD_ID, "collector"));

	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new CollectorItem(new Item.Properties()
					.setId(ITEM_KEY)
					.stacksTo(1)
					.durability(1)
					.component(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("minecraft", "golden_hoe"))
					.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
					.component(DataComponents.RARITY, Rarity.EPIC)
					.component(DataComponents.LORE, new ItemLore(List.of(
							Component.translatable("marketplace.item.collector.hint")
									.withStyle(ChatFormatting.GRAY))))));

	public CollectorItem(Item.Properties properties) {
		super(properties);
	}

	public static void register() {
		// Регистрация происходит при инициализации класса.
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();

		if (!level.getBlockState(pos).is(Blocks.SPAWNER)) {
			return InteractionResult.PASS;
		}

		Player player = context.getPlayer();

		if (player == null) {
			return InteractionResult.PASS;
		}

		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (!(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
			return InteractionResult.PASS;
		}

		// Данные блока переносим вместе с мобом: при установке они вернутся в спавнер.
		CompoundTag data = spawner.saveCustomOnly(level.registryAccess());
		ItemStack picked = new ItemStack(Items.SPAWNER);

		if (!data.isEmpty()) {
			picked.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(spawner.getType(), data));
		}

		level.removeBlock(pos, false);
		level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 0.8F, 0.9F);

		if (!player.getInventory().add(picked)) {
			player.drop(picked, false, Prediction.SERVER_ONLY);
		}

		useUp(context, player);

		return InteractionResult.SUCCESS;
	}

	/** Прочность 1: после одного спавнера предмет ломается. */
	private static void useUp(UseOnContext context, Player player) {
		if (player.getAbilities().instabuild) {
			return;
		}

		ItemStack held = context.getItemInHand();
		int damage = held.getDamageValue() + 1;

		if (damage < held.getMaxDamage()) {
			held.setDamageValue(damage);
			return;
		}

		player.setItemInHand(context.getHand(), ItemStack.EMPTY);
		player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS,
				0.7F, 1.0F);
	}
}
