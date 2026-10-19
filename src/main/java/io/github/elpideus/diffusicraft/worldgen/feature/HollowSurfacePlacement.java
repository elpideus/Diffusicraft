package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.MapCodec;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.function.Consumer;

/**
 * {@code diffusicraft:hollow_surface}: moves a column's position onto the floor or the ceiling of the Grand Hollow it
 * crosses, picked at random where it has more than one (a ledge, a fallen block, the top of a pillar), and drops it
 * where it crosses none.
 *
 * <p>Vanilla finds cave floors by scanning a dozen blocks from a random height, which is right for its caves but
 * misses nearly every floor of a hollow a hundred blocks tall. This scans the whole height of the Grand Hollows in the
 * column instead, which the {@link Hollows} know without looking at a block. Outside a Diffusicraft world it places
 * nothing.
 */
public record HollowSurfacePlacement(CaveSurface surface) implements PlacementModifier {
	public static final MapCodec<HollowSurfacePlacement> CODEC = CaveSurface.CODEC.fieldOf("surface")
		.xmap(HollowSurfacePlacement::new, HollowSurfacePlacement::surface);

	private static final ThreadLocal<Hollows.Column> COLUMNS = ThreadLocal.withInitial(Hollows.Column::new);

	@Override
	public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
		WorldGenLevel level = context.getLevel();
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) level.getLevel().getChunkSource().randomState())
			.diffusicraft$biomeWorld();
		if (world == null) {
			return;
		}
		int x = origin.getX();
		int z = origin.getZ();
		Hollows.Column column = COLUMNS.get();
		TerrainSurface ground = world.field().surface(world.scale());
		world.hollows().column(x, z, (px, pz) -> (int) Math.floor(ground.y(px, pz)), column);
		int top = Math.min(column.grandTop(), level.getMaxY() - 1);
		int bottom = Math.max(column.grandBottom(), level.getMinY() + 1);
		if (top < bottom) {
			return;
		}
		boolean floor = surface == CaveSurface.FLOOR;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top + 1, z);
		BlockState upper = level.getBlockState(pos);
		int found = 0;
		int picked = 0;
		// Walks down in pairs of blocks: a floor is open over solid, a ceiling solid over open.
		for (int y = top; y >= bottom - 1; y--) {
			BlockState lower = level.getBlockState(pos.setY(y));
			int candidate;
			if (floor) {
				candidate = y + 1 <= top && upper.isAir() && lower.isFaceSturdy(level, pos, Direction.UP) ? y + 1
					: Integer.MIN_VALUE;
			} else {
				candidate = y >= bottom && lower.isAir() && upper.isFaceSturdy(level, pos.setY(y + 1), Direction.DOWN)
					? y : Integer.MIN_VALUE;
			}
			// Only the Grand Hollow's own surfaces, not those of a smaller cave that cuts through its band.
			if (candidate != Integer.MIN_VALUE && world.hollows().grandHollowNear(column, candidate) != null
				&& random.nextInt(++found) == 0) {
				picked = candidate;
			}
			upper = lower;
		}
		if (found > 0) {
			output.accept(new BlockPos(x, picked, z));
		}
	}

	@Override
	public MapCodec<HollowSurfacePlacement> codec() {
		return CODEC;
	}
}
