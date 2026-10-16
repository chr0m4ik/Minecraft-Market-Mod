package com.chr0m4ik.marketplace.turbo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Блочная сущность «Турбо печки»: ванильная печь плюс уровень ускорения.
 *
 * <p>Наследник {@code AbstractFurnaceBlockEntity} (а не {@code FurnaceBlockEntity}: у той
 * конструктор жёстко привязан к ванильному типу блочной сущности). Ванильный {@code serverTick}
 * делает всё сам — рецепты, топливо, воронки, опыт, редстоун, — а множитель скорости ему
 * подставляет миксин {@code TurboFurnaceSpeedMixin}.
 *
 * <p>Уровень лежит в своём поле {@code turbo_level}: писать его в ванильное поле
 * {@code speed_multiplier} нельзя, потому что ваниль перезаписывает его при каждой заправке
 * топливом.
 */
public class TurboFurnaceBlockEntity extends AbstractFurnaceBlockEntity {
	/** Название окна печки. */
	private static final Component DEFAULT_NAME = Component.translatable("container.marketplace.turbo_furnace");

	/** Уровень печки: 1..4. */
	private int level = 1;

	public TurboFurnaceBlockEntity(BlockPos pos, BlockState state) {
		super(TurboFurnace.BLOCK_ENTITY, pos, state, RecipeType.SMELTING);
	}

	@Override
	protected Component getDefaultName() {
		return DEFAULT_NAME;
	}

	@Override
	protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
		// Именно ванильное печное меню: оно даёт книгу рецептов и начисление опыта за плавку
		// при закрытии окна. Своё меню сломало бы и то, и другое.
		return new FurnaceMenu(containerId, inventory, this, this.dataAccess);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt(TurboUpgrade.LEVEL_TAG, this.level);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.level = Mth.clamp(input.getIntOr(TurboUpgrade.LEVEL_TAG, 1), 1, TurboFurnace.MAX_LEVEL);
	}

	/**
	 * Что уйдёт на предмет при поломке. Ванильная {@code BaseContainerBlockEntity} кладёт сюда
	 * только {@code custom_name} и содержимое, поэтому уровень, название уровня и описание
	 * дописываем сами: своя loot table копирует эти компоненты из блочной сущности.
	 */
	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		components.set(DataComponents.CUSTOM_DATA, TurboUpgrade.dataOf(this.level));
		TurboUpgrade.decorate(components, this.level);
	}

	/** Постановка блока предметом: уровень берём из компонента предмета. */
	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		this.level = TurboUpgrade.levelOfComponents(components);
	}

	/** Множитель скорости уровня — его подставляет миксин на {@code getSpeedMultiplier}. */
	public float speedMultiplier() {
		return TurboFurnace.speedOf(this.level);
	}

	/** Уровень печки. */
	public int level() {
		return this.level;
	}

	/** Смена уровня (например, при соединении двух печек в наковальне). */
	public void setLevel(int value) {
		this.level = Mth.clamp(value, 1, TurboFurnace.MAX_LEVEL);
		this.setChanged();
	}
}
