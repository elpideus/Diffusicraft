package io.github.elpideus.diffusicraft.worldgen.lod;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LodGridCodecTest {
	private static final HolderOwner<Biome> OWNER = new HolderOwner<>() {
	};
	private static Holder<Biome> sea;
	private static Holder<Biome> forest;
	private static StreamCodec<FriendlyByteBuf, LodGrid> codec;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		sea = biome("ocean");
		forest = biome("forest");
		List<Holder<Biome>> biomes = List.of(sea, forest);
		codec = LodGrid.codec(ByteBufCodecs.idMapper(biomes::get, biomes::indexOf));
	}

	private static Holder<Biome> biome(String name) {
		return Holder.Reference.createStandAlone(OWNER,
			ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(name)));
	}

	/** A sea, a wood, a column with nothing in it and a peak above the ceiling. */
	private static LodGrid grid() {
		LodGrid grid = new LodGrid(2);
		LodColumn column = new LodColumn();
		column.reset(sea);
		column.add(Blocks.STONE.defaultBlockState(), -64, 40);
		column.add(Blocks.SAND.defaultBlockState(), 40, 41);
		column.markGround();
		column.add(Blocks.WATER.defaultBlockState(), 41, 63);
		column.waterDepth(22);
		grid.add(column, 4096);
		column.reset(forest);
		column.add(Blocks.STONE.defaultBlockState(), -64, 63);
		column.add(Blocks.GRASS_BLOCK.defaultBlockState(), 63, 64);
		column.markGround();
		column.add(Blocks.AIR.defaultBlockState(), 64, 67);
		column.add(Blocks.OAK_LEAVES.defaultBlockState(), 67, 70);
		grid.add(column, 4096);
		column.reset(sea);
		grid.add(column, 4096);
		column.reset(forest);
		column.add(Blocks.STONE.defaultBlockState(), -64, 5000);
		column.markGround();
		column.add(Blocks.SNOW_BLOCK.defaultBlockState(), 5000, 5001);
		grid.add(column, 4096);
		return grid;
	}

	private static LodGrid roundTrip(LodGrid grid) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		codec.encode(buf, grid);
		LodGrid read = codec.decode(buf);
		assertEquals(0, buf.readableBytes());
		return read;
	}

	@Test
	void aGridReadsBackAsItWasWritten() {
		LodGrid grid = grid();
		LodGrid read = roundTrip(grid);
		assertEquals(grid.width(), read.width());
		assertEquals(grid.highest(), read.highest());
		for (int column = 0; column < 4; column++) {
			assertSame(grid.biome(column), read.biome(column));
			for (int[] cut : new int[][]{{-96, 16, 16}, {30, 1, 50}, {3968, 4, 40}}) {
				BlockState[] expectedStates = new BlockState[cut[2]];
				byte[] expectedSky = new byte[cut[2]];
				grid.voxels(column, cut[0], cut[1], expectedStates, expectedSky);
				BlockState[] states = new BlockState[cut[2]];
				byte[] sky = new byte[cut[2]];
				read.voxels(column, cut[0], cut[1], states, sky);
				assertArrayEquals(expectedStates, states, "column " + column);
				assertArrayEquals(expectedSky, sky, "column " + column);
			}
		}
	}

	@Test
	void aGridCutShortIsRefused() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		codec.encode(buf, grid());
		FriendlyByteBuf cut = new FriendlyByteBuf(buf.copy(0, buf.writerIndex() - 4));
		assertThrows(RuntimeException.class, () -> codec.decode(cut));
	}

	@Test
	void aGridTooWideIsRefused() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(LodGrid.MAX_WIDTH + 1);
		assertThrows(DecoderException.class, () -> codec.decode(buf));
	}
}
