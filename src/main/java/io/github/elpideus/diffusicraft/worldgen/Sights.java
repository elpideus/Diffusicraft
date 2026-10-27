package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.worldgen.biome.BiomeClimate;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.biome.HollowBiomes;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.stream.DoubleStream;

/**
 * Finds the sights of a Diffusicraft world that vanilla's searches cannot: the Hollows of each kind, the cave biomes of
 * the Grand Hollows and the lakes in their basins, for {@code /diffusicraft find}. The {@link Hollows} model places
 * them from the seed alone, so they are found before their chunks generate, from the model and the coarse map. The
 * place given is then checked against the terrain itself, as the noise of its chunk will lay it out ({@link Ground}):
 * the world interpolates the model's density between the corners of its cells, vanilla's caves cut into it and its
 * aquifers flood it, so the model's floor can lie blocks off, or under water. The place is on the floor three quarters
 * of the way from the cavern's middle to its wall along one of its axes, or else farther out or in. Where all of the
 * floor lies under water or lava, it is in the air just over it.
 *
 * <p>The check needs the terrain at full detail, which the models may take seconds to compute where no one has been:
 * search off the server thread. Not thread-safe: each search keeps its own scratch columns.
 */
public final class Sights {
	/** Blocks around the start within which a sight is looked for. */
	public static final int RADIUS = 65_536;
	/**
	 * Coarse blocks around the start within which the cave biomes are looked for: each holds a few hundred kilometres
	 * of the coarse map, which the climate over a cavern is read from, and only so many are kept in memory.
	 */
	private static final int CLIMATE_BLOCKS = 2;
	/**
	 * How far out from a cavern's middle its floor is looked for, as a share of its radius: three quarters, then out
	 * to the wall, where the floor of a Deep Grand Hollow rises out of its lava, then in to the middle.
	 */
	private static final double[] REACHES = reaches();
	/** Blocks of open air, without water or lava, that a floor needs over it. */
	private static final int HEADROOM = 4;
	/** Blocks over the floor that the place given lies, clear of the moss, snow and plants that features add. */
	private static final int MARGIN = 1;
	/** The model's density a block into the open: it falls by about a quarter per block near the walls. */
	private static final float OPEN = -0.25f;

	private static final Hollows.Kind[] GRAND = {Hollows.Kind.GRAND_HOLLOW, Hollows.Kind.ANCIENT_GRAND_HOLLOW};
	private static final Hollows.Kind[] ANCIENT = {Hollows.Kind.ANCIENT_GRAND_HOLLOW};

	/** What can be looked for. */
	public enum Sight {
		HOLLOW("hollow", null, Hollows.Kind.HOLLOW),
		DEEP_HOLLOW("deep_hollow", null, Hollows.Kind.DEEP_HOLLOW),
		GRAND_HOLLOW("grand_hollow", null, Hollows.Kind.GRAND_HOLLOW),
		ANCIENT_GRAND_HOLLOW("ancient_grand_hollow", null, Hollows.Kind.ANCIENT_GRAND_HOLLOW),
		DEEP_GRAND_HOLLOW("deep_grand_hollow", null, Hollows.Kind.DEEP_GRAND_HOLLOW),
		/** A Grand Hollow whose basin holds a lake, of water or of ice. */
		HOLLOW_LAKE("hollow_lake", null, GRAND),
		GLOWWORM_GROTTO("glowworm_grotto", HollowBiomes.GLOWWORM_GROTTO, GRAND),
		SELENITE_CAVERNS("selenite_caverns", HollowBiomes.SELENITE_CAVERNS, ANCIENT),
		MUSHROOM_FOREST("mushroom_forest", HollowBiomes.MUSHROOM_FOREST, ANCIENT),
		SALT_CAVERNS("salt_caverns", HollowBiomes.SALT_CAVERNS, GRAND),
		ICE_CAVES("ice_caves", HollowBiomes.ICE_CAVES, GRAND),
		SULFUR_CAVES("sulfur_caves", HollowBiomes.SULFUR_CAVES, GRAND),
		LUSH_CAVES("lush_caves", HollowBiomes.LUSH_CAVES, GRAND);

		private final String id;
		private final @Nullable String biome;
		private final Hollows.Kind[] kinds;

		Sight(String id, @Nullable String biome, Hollows.Kind... kinds) {
			this.id = id;
			this.biome = biome;
			this.kinds = kinds;
		}

		/** Its name in the command. */
		public String id() {
			return id;
		}

		/** The cave biome it is, or {@code null} for a kind of hollow or a lake. */
		public @Nullable String biome() {
			return biome;
		}

		/** The kind of hollow it is, or {@code null} if it can be more than one. */
		public Hollows.@Nullable Kind kind() {
			return biome == null && this != HOLLOW_LAKE ? kinds[0] : null;
		}
	}

	/** What lies under the place given. */
	public enum Footing {
		/** The cavern's floor. */
		FLOOR,
		/** Water, a block under it: its lake, or an aquifer's. */
		LAKE,
		/** Lava, a block under it: its lava sea. */
		LAVA
	}

	/**
	 * A sight found: the cavern, its biome where Diffusicraft picks one, and a place in it, with what lies under that
	 * place.
	 */
	public record Found(Sight sight, Hollows.Chamber chamber, @Nullable String biome, BlockPos pos, Footing footing) {
	}

	/** The terrain's blocks before features and structures, as the noise of a chunk lays them out. */
	@FunctionalInterface
	public interface Ground {
		/** The blocks of column {@code (x, z)} from {@code minY} up, {@code height} of them: both multiples of 16. */
		NoiseColumn column(int x, int z, int minY, int height);
	}

	/** A column of a chamber where the model leaves a floor, and the heights the terrain is checked between there. */
	private record Spot(int x, int z, int bottom, int top) {
	}

	/** A chamber that the model says is the sight, and the columns to look for a place in, best first. */
	private record Candidate(Hollows.Chamber chamber, @Nullable String biome, List<Spot> spots) {
	}

	private final BiomeWorld world;
	private final Hollows hollows;
	private final Ground ground;
	private final IntBinaryOperator surface;
	private final Hollows.Column column = new Hollows.Column();
	private final TerrainField.Column terrain = new TerrainField.Column();
	private final BiomeClimate climate = new BiomeClimate();

	public Sights(BiomeWorld world, Ground ground) {
		this.world = world;
		this.hollows = world.hollows();
		this.ground = ground;
		WorldScale scale = world.scale();
		TerrainField field = world.field();
		// The coarse map's surface: the hollows keep a roof of rock under it, which only matters near the surface.
		this.surface = (x, z) -> (int) scale.surfaceY(field.coarseElevation(scale.toPixel(z), scale.toPixel(x)));
	}

	/** The sights of a level, or {@code null} if Diffusicraft does not generate it. */
	public static @Nullable Sights of(ServerLevel level) {
		RandomState state = level.getChunkSource().randomState();
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) state).diffusicraft$biomeWorld();
		if (world == null) {
			return null;
		}
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		return new Sights(world, (x, z, minY, height) -> generator.getBaseColumn(x, z,
			LevelHeightAccessor.create(minY, height), state));
	}

	/** Blocks around the start within which a sight is looked for: less for the cave biomes, at the larger sizes. */
	public int radius(Sight sight) {
		if (sight.biome == null && sight != Sight.HOLLOW_LAKE) {
			return RADIUS;
		}
		double coarseBlock = TerrainField.COARSE_BLOCK * world.field().coarseScale() / world.scale().pixelsPerBlock();
		return (int) Math.min(RADIUS, CLIMATE_BLOCKS * coarseBlock);
	}

	/**
	 * The sight nearest {@code (x, z)}, within {@link #radius} blocks of it, or {@code null}. A chamber where the
	 * terrain leaves nowhere to stand, after all, is passed over for the next.
	 */
	public @Nullable Found nearest(Sight sight, int x, int z) {
		Set<Long> passed = new HashSet<>();
		while (true) {
			Candidate best = null;
			double bestDistance = radius(sight);
			for (Hollows.Kind kind : sight.kinds) {
				Candidate candidate = nearest(sight, kind, x, z, bestDistance, passed);
				if (candidate != null) {
					best = candidate;
					bestDistance = Math.hypot(candidate.chamber.x() - x, candidate.chamber.z() - z);
				}
			}
			if (best == null) {
				return null;
			}
			Found found = place(sight, best);
			if (found != null) {
				return found;
			}
			passed.add(best.chamber.id());
		}
	}

	/**
	 * The chamber of one kind of hollow nearest {@code (x, z)} that the model says is the sight, with its middle
	 * within {@code radius} of it, leaving out those {@code passed} over. Looks through squares of cells twice as wide
	 * each time, nearest first, so the first that passes is the nearest.
	 */
	private @Nullable Candidate nearest(Sight sight, Hollows.Kind kind, int x, int z, double radius, Set<Long> passed) {
		int cell = hollows.cell(kind);
		Set<Long> tested = new HashSet<>();
		for (int cells = 1; ; cells *= 2) {
			double reach = Math.min((double) cells * cell, radius);
			List<Hollows.Chamber> chambers = hollows.chambers(kind, x, z, (int) Math.ceil(reach / cell));
			for (Hollows.Chamber chamber : chambers) {
				if (Math.hypot(chamber.x() - x, chamber.z() - z) > reach) {
					break;
				}
				if (passed.contains(chamber.id()) || !tested.add(chamber.id())) {
					continue;
				}
				Candidate candidate = candidate(sight, chamber);
				if (candidate != null) {
					return candidate;
				}
			}
			if (reach >= radius) {
				return null;
			}
		}
	}

	/** The chamber as a candidate, if the model says it is the sight and leaves a floor in it, or {@code null}. */
	private @Nullable Candidate candidate(Sight sight, Hollows.Chamber chamber) {
		String biome = null;
		boolean lake = false;
		if (chamber.kind() == Hollows.Kind.GRAND_HOLLOW || chamber.kind() == Hollows.Kind.ANCIENT_GRAND_HOLLOW) {
			BiomeClimate c = HollowBiomes.climate(world, chamber, terrain, climate);
			biome = HollowBiomes.pick(chamber.kind(), chamber.id(), c);
			lake = chamber.basin() > 0 && HollowBiomes.holdsLake(biome, c);
		}
		if (sight.biome != null && !sight.biome.equals(biome) || sight == Sight.HOLLOW_LAKE && !lake) {
			return null;
		}
		double[][] ways = {
			{chamber.cos() * chamber.rx(), chamber.sin() * chamber.rx()},
			{-chamber.cos() * chamber.rx(), -chamber.sin() * chamber.rx()},
			{-chamber.sin() * chamber.rz(), chamber.cos() * chamber.rz()},
			{chamber.sin() * chamber.rz(), -chamber.cos() * chamber.rz()},
		};
		List<Spot> spots = new ArrayList<>();
		for (double reach : REACHES) {
			for (double[] way : ways) {
				int x = (int) Math.floor(chamber.x() + reach * way[0]);
				int z = (int) Math.floor(chamber.z() + reach * way[1]);
				hollows.column(x, z, surface, column);
				int bottom = (int) Math.ceil(column.bottom()) - 1;
				int roof = roof((int) Math.floor(Math.min(column.top(), chamber.y() + chamber.up())), bottom);
				if (roof != Integer.MIN_VALUE) {
					spots.add(new Spot(x, z, bottom, roof));
				}
			}
		}
		return spots.isEmpty() ? null : new Candidate(chamber, biome, spots);
	}

	/**
	 * The place in a candidate: on the first of its spots where the terrain has a floor with {@value #HEADROOM} blocks
	 * of open air over it, or else just over the water or lava of the first where it has those instead, or
	 * {@code null}.
	 */
	private @Nullable Found place(Sight sight, Candidate candidate) {
		Found over = null;
		for (Spot spot : candidate.spots) {
			int minY = Math.floorDiv(spot.bottom, 16) * 16;
			NoiseColumn blocks = ground.column(spot.x, spot.z, minY, Math.ceilDiv(spot.top + 1 - minY, 16) * 16);
			int open = 0;
			for (int y = spot.top; y >= spot.bottom; y--) {
				BlockState state = blocks.getBlock(y);
				if (state.isAir()) {
					open++;
					continue;
				}
				if (open >= HEADROOM) {
					BlockPos pos = new BlockPos(spot.x, y + 1 + MARGIN, spot.z);
					FluidState fluid = state.getFluidState();
					if (fluid.isEmpty()) {
						return new Found(sight, candidate.chamber, candidate.biome, pos, Footing.FLOOR);
					}
					if (over == null) {
						over = new Found(sight, candidate.chamber, candidate.biome, pos,
							fluid.is(FluidTags.LAVA) ? Footing.LAVA : Footing.LAKE);
					}
				}
				open = 0;
			}
		}
		return over;
	}

	private static double[] reaches() {
		DoubleStream.Builder reaches = DoubleStream.builder();
		for (int i = 0; i < 30; i++) {
			reaches.add(0.75 + 0.02 * i);
		}
		return reaches.add(0.5).add(0.25).add(0).build().toArray();
	}

	/**
	 * The roof over the highest floor that the model leaves in the prepared column between {@code top} and
	 * {@code bottom}, with {@value #HEADROOM} blocks open over it, or {@link Integer#MIN_VALUE}. The terrain is checked
	 * under that roof only: the rock over the lower edges of a dome holds caves of vanilla's, which are not the cavern.
	 */
	private int roof(int top, int bottom) {
		int open = 0;
		int roof = Integer.MIN_VALUE;
		for (int y = top; y >= bottom; y--) {
			float density = hollows.density(column, y);
			if (density < OPEN) {
				if (open++ == 0) {
					roof = y;
				}
			} else if (density >= 0) {
				if (open >= HEADROOM) {
					return roof;
				}
				open = 0;
			}
		}
		return Integer.MIN_VALUE;
	}
}
