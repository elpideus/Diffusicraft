package io.github.elpideus.diffusicraft.worldgen.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaveFeaturesTest {
	private static final int FLOOR = 0;
	private static final Path FEATURES = Path.of("src/main/resources/data/diffusicraft/worldgen/feature");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** A cavern of air over a stone floor at {@link #FLOOR}, under a stone ceiling, that remembers what is set. */
	private static final class Cavern {
		final int ceiling;
		final Map<BlockPos, BlockState> placed = new HashMap<>();
		final WorldGenLevel level;

		Cavern(int ceiling) {
			this.ceiling = ceiling;
			InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
				case "getBlockState" -> get((BlockPos) args[0]);
				case "isEmptyBlock" -> get((BlockPos) args[0]).isAir();
				case "setBlock" -> {
					placed.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
					yield true;
				}
				case "getMinY" -> -64;
				case "getHeight" -> 384;
				default -> {
					if (method.isDefault()) {
						yield InvocationHandler.invokeDefault(proxy, method, args);
					}
					throw new UnsupportedOperationException(method.getName());
				}
			};
			level = (WorldGenLevel) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{WorldGenLevel.class}, handler);
		}

		BlockState get(BlockPos pos) {
			BlockState state = placed.get(pos);
			if (state != null) {
				return state;
			}
			return pos.getY() < FLOOR || pos.getY() >= ceiling ? Blocks.STONE.defaultBlockState()
				: Blocks.AIR.defaultBlockState();
		}

		/** How many blocks a layer of the placed blocks holds. */
		int layer(int y) {
			return (int) placed.keySet().stream().filter(p -> p.getY() == y).count();
		}
	}

	/**
	 * A spire as the data sets it up, which checks that the data decodes. Diffusicraft's blocks are not registered in a
	 * plain test, so a spire of one of them is made of calcite.
	 */
	private static SpireFeature spire(String name) throws IOException {
		String data = Files.readString(FEATURES.resolve(name + ".json"));
		JsonElement json = JsonParser.parseString(data.replaceAll("diffusicraft:\\w+\"", "minecraft:calcite\""));
		return SpireFeature.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow();
	}

	@Test
	void iceSpiresTaperUpFromTheFloorAndDownFromTheCeiling() throws IOException {
		SpireFeature ice = spire("ice_spire");
		for (int seed = 0; seed < 40; seed++) {
			Cavern cavern = new Cavern(60);
			assertTrue(ice.place(cavern.level, null, RandomSource.create(seed), new BlockPos(0, FLOOR, 0)));
			int top = cavern.placed.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow();
			assertTrue(top >= FLOOR + 3 && top < FLOOR + 24, "top at " + top);
			for (int y = FLOOR + 1; y <= top; y++) {
				assertTrue(cavern.layer(y) <= cavern.layer(y - 1) + 2, "wider at " + y);
			}
			assertTrue(cavern.layer(top) <= 2, cavern.layer(top) + " blocks at the point");
			for (Map.Entry<BlockPos, BlockState> e : cavern.placed.entrySet()) {
				assertTrue(e.getKey().getY() >= FLOOR, "in the floor at " + e.getKey());
				assertEquals(Blocks.PACKED_ICE.defaultBlockState(), e.getValue());
			}

			Cavern above = new Cavern(60);
			assertTrue(ice.place(above.level, null, RandomSource.create(seed), new BlockPos(0, 59, 0)));
			int bottom = above.placed.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow();
			assertTrue(bottom <= 59 - 3 && bottom > 59 - 24, "bottom at " + bottom);
			assertTrue(above.placed.keySet().stream().allMatch(p -> p.getY() < 60), "in the ceiling");
		}
	}

	@Test
	void aThinSpireIsWholeFromTheCeilingToItsPoint() throws IOException {
		SpireFeature straw = spire("salt_stalactite");
		for (int seed = 0; seed < 200; seed++) {
			Cavern cavern = new Cavern(60);
			assertTrue(straw.place(cavern.level, null, RandomSource.create(seed), new BlockPos(0, 59, 0)));
			// Every block hangs from one in the layer above it, so no piece of it floats.
			for (BlockPos p : cavern.placed.keySet()) {
				assertTrue(p.getY() == 59 || cavern.placed.keySet().stream().anyMatch(q -> q.getY() == p.getY() + 1
					&& Math.abs(q.getX() - p.getX()) <= 1 && Math.abs(q.getZ() - p.getZ()) <= 1), "loose at " + p);
			}
		}
	}

	@Test
	void aSpireAcrossALowCavernBecomesAPillar() throws IOException {
		SpireFeature ice = spire("ice_spire");
		int pillars = 0;
		for (int seed = 0; seed < 40; seed++) {
			Cavern cavern = new Cavern(FLOOR + 4);
			ice.place(cavern.level, null, RandomSource.create(seed), new BlockPos(0, FLOOR, 0));
			pillars += cavern.layer(FLOOR + 3) > 0 ? 1 : 0;
		}
		assertTrue(pillars > 30, pillars + " of 40 reached the ceiling");
	}

	@Test
	void haliteGrowsInCubesOutOfTheRock() {
		// Diffusicraft's blocks are not registered in a plain test; any block does.
		BlockState salt = Blocks.CALCITE.defaultBlockState();
		HaliteFeature halite = new HaliteFeature(salt);
		for (int seed = 0; seed < 40; seed++) {
			Cavern cavern = new Cavern(40);
			BlockPos origin = new BlockPos(0, FLOOR, 0);
			assertTrue(halite.place(cavern.level, null, RandomSource.create(seed), origin));
			assertEquals(salt, cavern.placed.get(origin));
			// The first cube alone is at least three blocks on a side.
			assertTrue(cavern.placed.size() >= 27, cavern.placed.size() + " blocks");
			for (Map.Entry<BlockPos, BlockState> e : cavern.placed.entrySet()) {
				BlockPos p = e.getKey();
				assertEquals(salt, e.getValue());
				assertTrue(Math.abs(p.getX()) <= 8 && Math.abs(p.getZ()) <= 8, "far out at " + p);
				assertTrue(p.getY() >= FLOOR - 3 && p.getY() < FLOOR + 14, "at " + p);
			}
		}
	}
}
