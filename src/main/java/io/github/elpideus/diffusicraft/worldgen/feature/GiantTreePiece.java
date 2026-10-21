package io.github.elpideus.diffusicraft.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

/**
 * The one piece of a {@link GiantTreeStructure}: the whole tree, which it writes a chunk at a time. It keeps the draws
 * of the tree rather than its blocks, and plans the tree again for each chunk, laying only the blocks of that chunk and
 * of the {@value GiantTree#LEAF_REACH} blocks around it, which the leaves need to know how far they lie from the wood.
 */
public final class GiantTreePiece extends StructurePiece {
	public static final StructurePieceType.ContextlessType TYPE = GiantTreePiece::new;

	private final GiantTreeFeature.Form form;
	private final BlockState wood;
	private final BlockState leaves;
	/** The foot of the trunk, the block just over the ground. */
	private final BlockPos base;
	private final int height;
	private final int reach;
	/** The seed the tree's shape is drawn from. */
	private final long seed;

	GiantTreePiece(GiantTreeFeature.Form form, BlockState wood, BlockState leaves, BlockPos base, int height,
				   int reach, long seed, BoundingBox box) {
		super(TYPE, 0, box);
		this.form = form;
		this.wood = wood;
		this.leaves = leaves;
		this.base = base;
		this.height = height;
		this.reach = reach;
		this.seed = seed;
	}

	private GiantTreePiece(CompoundTag tag) {
		super(TYPE, tag);
		form = tag.read("form", GiantTreeFeature.Form.CODEC).orElse(GiantTreeFeature.Form.OAK);
		wood = tag.read("wood", BlockState.CODEC).orElseThrow();
		leaves = tag.read("leaves", BlockState.CODEC).orElseThrow();
		base = tag.read("base", BlockPos.CODEC).orElseThrow();
		height = tag.getIntOr("height", 0);
		reach = tag.getIntOr("reach", 0);
		seed = tag.getLongOr("seed", 0L);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		tag.store("form", GiantTreeFeature.Form.CODEC, form);
		tag.store("wood", BlockState.CODEC, wood);
		tag.store("leaves", BlockState.CODEC, leaves);
		tag.store("base", BlockPos.CODEC, base);
		tag.putInt("height", height);
		tag.putInt("reach", reach);
		tag.putLong("seed", seed);
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
							RandomSource random, BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos) {
		place(level, chunkBB);
	}

	/** Writes the tree's blocks within {@code box}: one chunk's, as the world is decorated. */
	public void place(WorldGenLevel level, BoundingBox box) {
		GiantTree plan = GiantTree.plan(form, height, reach, RandomSource.create(seed));
		BoundingBox local = new BoundingBox(box.minX() - base.getX(), box.minY() - base.getY(),
			box.minZ() - base.getZ(), box.maxX() - base.getX(), box.maxY() - base.getY(), box.maxZ() - base.getZ());
		GiantTree.Blocks blocks = plan.blocks(local.inflatedBy(GiantTree.LEAF_REACH));
		GiantTreeFeature.write(level, base, blocks, wood, leaves, box::isInside);
	}
}
