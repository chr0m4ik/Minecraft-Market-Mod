package com.chr0m4ik.marketplace.mining;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;

public final class BreakContext {

	private static final Deque<Block> BLOCKS = new ArrayDeque<>();

	private static ServerPlayer player;

	private static ItemStack tool = ItemStack.EMPTY;

	private BreakContext() {
		// Класс только со статическими методами.
	}

	public static boolean begin(ServerPlayer breaker, Block block) {
		if (BLOCKS.isEmpty()) {
			player = breaker;
			tool = breaker.getMainHandItem();
		}

		BLOCKS.addLast(block);
		return BLOCKS.size() == 1;
	}

	public static void end() {
		BLOCKS.pollLast();

		if (BLOCKS.isEmpty()) {
			player = null;
			tool = ItemStack.EMPTY;
		}
	}

	public static ServerPlayer player() {
		return player;
	}

	public static ItemStack tool() {
		return tool;
	}

	public static Block block() {
		return BLOCKS.peekLast();
	}
}
