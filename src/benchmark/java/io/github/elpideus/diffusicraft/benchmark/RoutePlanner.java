package io.github.elpideus.diffusicraft.benchmark;

import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a way over ordinary land, where players are. A straight line ends in the side of a mountain or out at sea, and
 * neither is what the benchmark means to measure. The way is read from the terrain itself, as the coarse map averages a
 * mountain away with the lowlands around it, and that runs the models, so this happens before anything is measured.
 * The teleport must land where nothing has been computed, so {@link #coarse} plans from the coarse map alone there.
 */
final class RoutePlanner {
	/** A place on the map, in blocks. */
	record Point(double x, double z) {
		Point ahead(double heading, double blocks) {
			return new Point(x + blocks * Math.cos(heading), z + blocks * Math.sin(heading));
		}
	}

	/** A place to fly to and how high to be there. */
	record Waypoint(Point point, int y) {
	}

	/** How long a leg of the route is, in blocks. */
	private static final int LEG_BLOCKS = 256;
	/** How many legs ahead each choice of way looks. */
	private static final int LEGS_AHEAD = 6;
	/** The height up to which land counts as ordinary, in metres. */
	private static final float LOW_LAND = 300;
	/** What a point over water costs: as much as a point this many metres above the low land. */
	private static final float WATER_COST = 2000;
	/** What turning costs, per radian, so that the route only turns for a reason and does not fold back on itself. */
	private static final double TURN_COST = 200;
	private static final double[] ALL_WAYS = turns(8);
	private static final double[] FORWARD_WAYS = {0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2};

	private final TerrainField field;
	private final WorldScale scale;
	private final boolean coarse;

	RoutePlanner(ServerLevel level) {
		if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
			|| !DiffusicraftWorldgen.isDiffusicraft(generator.generatorSettings().value())) {
			throw new IllegalStateException("The benchmark needs a Diffusicraft world");
		}
		scale = DiffusicraftWorldgen.scale(generator.generatorSettings().value());
		field = TerrainFields.forState(level.getChunkSource().randomState());
		coarse = false;
	}

	private RoutePlanner(TerrainField field, WorldScale scale, boolean coarse) {
		this.field = field;
		this.scale = scale;
		this.coarse = coarse;
	}

	/** This planner reading the coarse map alone. */
	RoutePlanner coarse() {
		return new RoutePlanner(field, scale, true);
	}

	private static double[] turns(int ways) {
		double[] turns = new double[ways];
		for (int way = 0; way < ways; way++) {
			turns[way] = (way - ways / 2) * 2 * Math.PI / ways;
		}
		return turns;
	}

	/** The points of a route of at least {@code blocks} from {@code start}, each picking the cheapest way on. */
	List<Point> route(Point start, double blocks) {
		List<Point> route = new ArrayList<>();
		Point at = start;
		double heading = cheapest(at, 0, ALL_WAYS);
		for (double planned = 0; planned < blocks; planned += LEG_BLOCKS) {
			at = at.ahead(heading, LEG_BLOCKS);
			route.add(at);
			heading = cheapest(at, heading, FORWARD_WAYS);
		}
		return route;
	}

	private double cheapest(Point from, double heading, double[] turns) {
		double best = heading;
		double lowest = Double.MAX_VALUE;
		for (double turn : turns) {
			double cost = Math.abs(turn) * TURN_COST;
			for (int leg = 1; leg <= LEGS_AHEAD; leg++) {
				cost += cost(elevation(from.ahead(heading + turn, leg * LEG_BLOCKS)));
			}
			if (cost < lowest) {
				lowest = cost;
				best = heading + turn;
			}
		}
		return best;
	}

	private static double cost(float elevation) {
		return elevation <= 0 ? WATER_COST : Math.max(0, elevation - LOW_LAND);
	}

	/** The first ordinary land to the east of {@code from}, at least {@code blocks} away. */
	Point landing(Point from, double blocks) {
		for (int leg = 0; leg < 1024; leg++) {
			Point point = from.ahead(0, blocks + leg * LEG_BLOCKS);
			if (cost(elevation(point)) == 0) {
				return point;
			}
		}
		throw new IllegalStateException("No ordinary land east of " + from);
	}

	/** How high to fly over each point: {@code height} over the ground the map expects, which smooths hills away. */
	List<Waypoint> cruise(List<Point> points, int height) {
		return points.stream().map(point -> new Waypoint(point, Math.round(surfaceY(point)) + height)).toList();
	}

	/** How many of the points are ordinary land, for the log. */
	long lowLand(List<Point> points) {
		return points.stream().filter(point -> cost(elevation(point)) == 0).count();
	}

	/** Where the ground is at {@code point}, in blocks. */
	float surfaceY(Point point) {
		return scale.surfaceY(elevation(point));
	}

	private float elevation(Point point) {
		double i = scale.toPixel((int) point.z());
		double j = scale.toPixel((int) point.x());
		return coarse ? field.coarseElevation(i, j) : field.elevation(i, j);
	}
}
