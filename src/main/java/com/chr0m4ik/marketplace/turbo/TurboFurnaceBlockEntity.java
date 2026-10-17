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

public class TurboFurnaceBlockEntity extends AbstractFurnaceBlockEntity {

	private static final Component DEFAULT_NAME = Component.translatable("container.marketplace.turbo_furnace");

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

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		components.set(DataComponents.CUSTOM_DATA, TurboUpgrade.dataOf(this.level));
		TurboUpgrade.decorate(components, this.level);
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		this.level = TurboUpgrade.levelOfComponents(components);
	}

	public float speedMultiplier() {
		return TurboFurnace.speedOf(this.level);
	}

	public int level() {
		return this.level;
	}

	public void setLevel(int value) {
		this.level = Mth.clamp(value, 1, TurboFurnace.MAX_LEVEL);
		this.setChanged();
	}
}
