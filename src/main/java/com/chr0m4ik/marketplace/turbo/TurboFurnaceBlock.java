package com.chr0m4ik.marketplace.turbo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Блок «Турбо печки».
 *
 * <p>Наследуемся от {@code AbstractFurnaceBlock}, а не от ванильной {@code FurnaceBlock}: последняя
 * жёстко привязана к {@code BlockEntityTypes.FURNACE} (в {@code newBlockEntity}, {@code getTicker}
 * и {@code openContainer}), поэтому со своим типом блочной сущности она не подходит.
 *
 * <p>Что уже работает «бесплатно» из {@code AbstractFurnaceBlock}: поворот по взгляду, состояние
 * {@code lit}, редстоун-сигнал для компаратора, выкидывание содержимого при поломке.
 * Частицы и звук горения у {@code AbstractFurnaceBlock} отсутствуют — они в {@code FurnaceBlock},
 * поэтому {@link #animateTick} скопирован оттуда слово в слово.
 */
public class TurboFurnaceBlock extends AbstractFurnaceBlock {
	public TurboFurnaceBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TurboFurnaceBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
			BlockEntityType<T> type) {
		// Тип, который создаёт newBlockEntity, обязан совпадать с ожидаемым здесь: иначе
		// createFurnaceTicker вернёт null, блочная сущность не будет тикать и печка «не плавит».
		return createFurnaceTicker(level, type, TurboFurnace.BLOCK_ENTITY);
	}

	@Override
	protected void openContainer(Level level, BlockPos pos, Player player) {
		if (level.getBlockEntity(pos) instanceof TurboFurnaceBlockEntity entity) {
			// Меню — ванильное печное: оно же даёт книгу рецептов и опыт за плавку.
			player.openMenu(entity);
			player.awardStat(Stats.INTERACT_WITH_FURNACE);
		}
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (state.getValue(LIT)) {
			double x = pos.getX() + 0.5;
			double y = pos.getY();
			double z = pos.getZ() + 0.5;

			if (random.nextDouble() < 0.1) {
				level.playLocalSound(x, y, z, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS,
						1.0F, 1.0F, false);
			}

			Direction direction = state.getValue(FACING);
			Direction.Axis axis = direction.getAxis();
			double spread = random.nextDouble() * 0.6 - 0.3;
			double dx = axis == Direction.Axis.X ? direction.getStepX() * 0.52 : spread;
			double dy = random.nextDouble() * 6.0 / 16.0;
			double dz = axis == Direction.Axis.Z ? direction.getStepZ() * 0.52 : spread;
			level.addParticle(ParticleTypes.SMOKE, x + dx, y + dy, z + dz, 0.0, 0.0, 0.0);
			level.addParticle(ParticleTypes.FLAME, x + dx, y + dy, z + dz, 0.0, 0.0, 0.0);
		}
	}
}
