package com.chr0m4ik.marketplace.mining;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Контекст ломания блока: <b>кто</b> ломает, <b>чем</b> и <b>какой блок</b>.
 *
 * <p>Нужен зачарованиям, которые работают с дропом: у {@code Block.popResource} (там дроп
 * перехватывается) нет ни игрока, ни блока — только позиция и предмет. Зато через
 * {@code ServerPlayerGameMode.destroyBlock} проходит <b>любое</b> ломание блока игроком, поэтому
 * всё нужное запоминается на входе в него (миксин
 * {@link com.chr0m4ik.marketplace.mixin.ServerPlayerGameModeMixin}) и очищается на выходе.
 *
 * <p><b>Вложенность.</b> «Жилковый майнер» ломает жилу вложенными вызовами {@code destroyBlock},
 * а значит внутри одного нажатия блоков несколько. Поэтому блоки складываются в стопку: наверху
 * всегда тот блок, чей дроп создаётся прямо сейчас. Игрок и инструмент берутся с <b>самого
 * внешнего</b> вызова — это то нажатие, которое начал игрок, и именно им считаются уровень
 * зачарования и запас магнита.
 *
 * <p>Весь доступ идёт из главного потока сервера, поэтому обычных статических полей достаточно.
 */
public final class BreakContext {
	/**
	 * Блоки текущего нажатия, от самого внешнего вызова к самому внутреннему. Пустая стопка
	 * означает «дроп создаётся не ломанием блока игроком» (взрывы, сущности, воронки) — такие
	 * дропы наши зачарования не трогают.
	 */
	private static final Deque<Block> BLOCKS = new ArrayDeque<>();

	/** Игрок, начавший это нажатие. */
	private static ServerPlayer player;

	/** Инструмент, которым игрок начал это нажатие. */
	private static ItemStack tool = ItemStack.EMPTY;

	private BreakContext() {
		// Класс только со статическими методами.
	}

	/**
	 * Игрок начал (или продолжил вложенно) ломать блок.
	 *
	 * @return {@code true}, если это самое внешнее ломание — новое нажатие игрока
	 */
	public static boolean begin(ServerPlayer breaker, Block block) {
		if (BLOCKS.isEmpty()) {
			player = breaker;
			tool = breaker.getMainHandItem();
		}

		BLOCKS.addLast(block);
		return BLOCKS.size() == 1;
	}

	/** Ломание блока закончилось (в том числе отменённое чужой защитой). */
	public static void end() {
		BLOCKS.pollLast();

		if (BLOCKS.isEmpty()) {
			player = null;
			tool = ItemStack.EMPTY;
		}
	}

	/** Игрок, который ломает блок прямо сейчас. {@code null} — ломание идёт не игроком. */
	public static ServerPlayer player() {
		return player;
	}

	/** Инструмент, которым начато это нажатие. */
	public static ItemStack tool() {
		return tool;
	}

	/** Блок, чей дроп создаётся прямо сейчас. {@code null} — вне ломания блока игроком. */
	public static Block block() {
		return BLOCKS.peekLast();
	}
}
