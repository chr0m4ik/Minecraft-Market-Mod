package com.chr0m4ik.marketplace.collector;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.world.InteractionResult;

/** Собиратель не добывает блоки. */
public final class CollectorHandler {

	private CollectorHandler() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				player.getItemInHand(hand).is(CollectorItem.ITEM) ? InteractionResult.FAIL : InteractionResult.PASS);
	}
}
