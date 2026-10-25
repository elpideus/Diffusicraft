package io.github.elpideus.diffusicraft.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code diffusicraft:spire}: a tapering spire of one block grown up from a cave floor or down from a ceiling at the
 * origin, as the ice stalagmites of the Eisriesenwelt grow from the drips of the spring thaw, or the salt stalactites
 * of Mount Sodom from the brine that seeps through the roof. It leans a few degrees at most, and narrows from its
 * base, {@code radius} blocks across for each block of its height, to a point a block wide.
 *
 * <p>It fills only open space and water, and stops short of whatever it would run into, so a spire that reaches the
 * other side of a low cavern becomes a pillar. Under its base it fills the open space two blocks down, so that it
 * stands on uneven ground rather than over it. Vanilla's ice spikes cannot be used: below y = 50 their pillars never
 * reach the ground.
 */
public record SpireFeature(BlockState state, IntProvider height, float radius) implements Feature {
	public static final MapCodec<SpireFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BlockState.CODEC.fieldOf("state").forGetter(SpireFeature::state),
		IntProviders.codec(1, 48).fieldOf("height").forGetter(SpireFeature::height),
		Codec.floatRange(0f, 0.25f).fieldOf("radius").forGetter(SpireFeature::radius)
	).apply(instance, SpireFeature::new));

	private static final int ROOT = 2;
	private static final double MAX_LEAN = Math.toRadians(8);

	@Override
	public MapCodec<SpireFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		if (!open(level.getBlockState(origin))) {
			return false;
		}
		int up;
		if (level.getBlockState(origin.below()).isFaceSturdy(level, origin.below(), Direction.UP)) {
			up = 1;
		} else if (level.getBlockState(origin.above()).isFaceSturdy(level, origin.above(), Direction.DOWN)) {
			up = -1;
		} else {
			return false;
		}
		double lean = random.nextDouble() * MAX_LEAN;
		double turn = random.nextDouble() * Math.PI * 2;
		double dx = Math.sin(lean) * Math.cos(turn);
		double dz = Math.sin(lean) * Math.sin(turn);
		double dy = Math.cos(lean) * up;
		double length = Math.min(height.sample(random), free(level, origin, dx, dy, dz));
		if (length < 1) {
			return false;
		}
		double base = Math.max(0.5, length * radius);
		double cx = origin.getX() + 0.5;
		double cy = origin.getY() + 0.5 - up * 0.5;
		double cz = origin.getZ() + 0.5;
		double reach = base + Math.max(Math.abs(dx), Math.abs(dz)) * length;
		int minX = Mth.floor(cx - reach);
		int maxX = Mth.floor(cx + reach);
		int minZ = Mth.floor(cz - reach);
		int maxZ = Mth.floor(cz + reach);
		int minY = Math.max(up > 0 ? origin.getY() - ROOT : Mth.floor(cy - length), level.getMinY());
		int maxY = Math.min(up > 0 ? Mth.floor(cy + length) : origin.getY() + ROOT, level.getMaxY());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean placed = false;
		for (int y = minY; y <= maxY; y++) {
			// The block the axis passes through on this layer, which keeps a thin spire whole where its axis runs past
			// the corners of the blocks, far from all their centres.
			double t = Math.max(0, (y + 0.5 - cy) / dy);
			int axisX = Mth.floor(cx + dx * t);
			int axisZ = Mth.floor(cz + dz * t);
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					double px = x + 0.5 - cx;
					double py = y + 0.5 - cy;
					double pz = z + 0.5 - cz;
					// Below its base it is as wide as there; above, it narrows to its point.
					double along = Math.max(0, px * dx + py * dy + pz * dz);
					if (along > length) {
						continue;
					}
					double r = Math.max(0.5, base * Math.pow(1 - along / length, 1.3));
					double ox = px - dx * along;
					double oz = pz - dz * along;
					if (ox * ox + oz * oz > r * r && (x != axisX || z != axisZ)) {
						continue;
					}
					if (open(level.getBlockState(pos.set(x, y, z)))) {
						level.setBlock(pos, state, 2);
						placed = true;
					}
				}
			}
		}
		return placed;
	}

	/** How far the spire may run from the origin before it meets something it cannot fill. */
	private static double free(WorldGenLevel level, BlockPos origin, double dx, double dy, double dz) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		double x = origin.getX() + 0.5;
		double y = origin.getY() + 0.5;
		double z = origin.getZ() + 0.5;
		for (int i = 1; i < 48; i++) {
			pos.set(Mth.floor(x + dx * i), Mth.floor(y + dy * i), Mth.floor(z + dz * i));
			if (!open(level.getBlockState(pos))) {
				return i;
			}
		}
		return 48;
	}

	private static boolean open(BlockState state) {
		return state.isAir() || state.is(Blocks.WATER);
	}
}
