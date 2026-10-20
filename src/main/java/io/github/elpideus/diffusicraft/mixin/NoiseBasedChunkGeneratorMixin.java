package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.elpideus.diffusicraft.worldgen.CarverRoof;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.TerrainCeiling;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainFields;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Puts the lava level of Diffusicraft worlds ten blocks above the bottom, where vanilla has it in its own height.
 * Vanilla fixes it at y=-54, which in a world that goes down to y=-256 would flood every cave in 200 blocks of lava.
 * Also starts only one in six of vanilla's cave carvers, which with its noise caves would leave the ground as full
 * of holes as vanilla's, and keeps carvers under a roof where their holes would fill with water (see
 * {@link CarverRoof}) and under sand and gravel, which would hang over them. And fills chunks, and the columns
 * structures ask about, only up to the highest block their ground can reach; see {@link TerrainCeiling}.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {
	/** The share of vanilla's cave carvers that start in Diffusicraft worlds. Ravines all start, as rare as they are. */
	private static final float CAVE_CARVERS_KEPT = 1 / 6f;

	@Inject(method = "createFluidPicker", at = @At("HEAD"), cancellable = true)
	private static void diffusicraft$lavaAboveBottom(NoiseGeneratorSettings settings,
													 CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
		if (!DiffusicraftWorldgen.isDiffusicraft(settings)) {
			return;
		}
		int lavaLevel = settings.noiseSettings().minY() + 10;
		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
		int seaLevel = settings.seaLevel();
		Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
		Aquifer.FluidStatus empty = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
		int lavaBelow = Math.min(lavaLevel, seaLevel);
		cir.setReturnValue((x, y, z) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? empty
			: y < lavaBelow ? lava : sea);
	}

	/**
	 * Narrows a Diffusicraft chunk's fill to the blocks its ground, its rivers, the sea and its terrain-adapting
	 * structures can reach. The structures' density only acts within 24 blocks of their pieces (see
	 * {@link net.minecraft.world.level.levelgen.Beardifier}). Chunks blended with old ones keep the full fill.
	 */
	@ModifyVariable(method = "createNoiseChunk", at = @At("HEAD"), argsOnly = true)
	private NoiseSettings diffusicraft$fillToTheGround(NoiseSettings noise, @Local(argsOnly = true) ChunkAccess chunk,
													   @Local(argsOnly = true) StructureManager structures,
													   @Local(argsOnly = true) Blender blender,
													   @Local(argsOnly = true) RandomState randomState) {
		NoiseGeneratorSettings settings = ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value();
		WorldScale scale = DiffusicraftWorldgen.scale(settings);
		if (scale == null || blender != Blender.empty()) {
			return noise;
		}
		TerrainField field = TerrainFields.forState(randomState);
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
		int top = TerrainCeiling.ofChunk(field, Rivers.of(field), scale, settings.seaLevel(), minX, minZ);
		for (StructureStart start : structures.startsForStructure(chunk.getPos().x(), chunk.getPos().z(),
			structure -> structure.terrainAdaptation() != TerrainAdjustment.NONE)) {
			top = Math.max(top, start.getBoundingBox().maxY() + 24);
		}
		return narrowed(noise, top);
	}

	/** The same for the single columns that structures and spawning ask {@code getBaseHeight} about. */
	@ModifyExpressionValue(method = "iterateNoiseColumn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/"
		+ "level/levelgen/NoiseSettings;clampToHeightAccessor(Lnet/minecraft/world/level/LevelHeightAccessor;)"
		+ "Lnet/minecraft/world/level/levelgen/NoiseSettings;"))
	private NoiseSettings diffusicraft$columnToTheGround(NoiseSettings noise,
														 @Local(argsOnly = true) RandomState randomState,
														 @Local(argsOnly = true, ordinal = 0) int blockX,
														 @Local(argsOnly = true, ordinal = 1) int blockZ) {
		NoiseGeneratorSettings settings = ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value();
		WorldScale scale = DiffusicraftWorldgen.scale(settings);
		if (scale == null) {
			return noise;
		}
		TerrainField field = TerrainFields.forState(randomState);
		return narrowed(noise, TerrainCeiling.ofColumn(field, Rivers.of(field), scale, settings.seaLevel(), blockX,
			blockZ));
	}

	/** {@code noise} cut down to whole sections from its bottom up to block {@code top}, if that is lower. */
	private static NoiseSettings narrowed(NoiseSettings noise, int top) {
		int height = Math.max(16, Math.ceilDiv(top + 1 - noise.minY(), 16) * 16);
		return height < noise.height() ? new NoiseSettings(noise.minY(), height) : noise;
	}

	@WrapOperation(method = "generateCarvers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/"
		+ "carver/WorldCarver;isStartChunk(Lnet/minecraft/util/RandomSource;)Z"))
	private boolean diffusicraft$fewerCaveCarvers(WorldCarver carver, RandomSource random, Operation<Boolean> original) {
		boolean start = original.call(carver, random);
		if (!start || !(carver instanceof CaveWorldCarver)
			|| !DiffusicraftWorldgen.isDiffusicraft(((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value())) {
			return start;
		}
		// The random is seeded for this carver and chunk just before, so the same carvers start every time.
		return random.nextFloat() < CAVE_CARVERS_KEPT;
	}

	@WrapOperation(method = "generateCarvers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/"
		+ "NoiseBasedChunkGenerator;applyCarvingMask(Lnet/minecraft/world/level/chunk/ChunkAccess;"
		+ "Lnet/minecraft/world/level/chunk/CarvingMask;Lnet/minecraft/world/level/levelgen/RandomState;"
		+ "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;"
		+ "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/NoiseChunk;"
		+ "Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/CarvingMask$Filter;)V"))
	private void diffusicraft$roofCarvers(NoiseBasedChunkGenerator generator, ChunkAccess chunk, CarvingMask mask,
										  RandomState randomState, MaterialRule materialRule,
										  WorldGenerationContext context, NoiseChunk noiseChunk,
										  Function<BlockPos, Holder<Biome>> biomes, CarvingMask.Filter filter,
										  Operation<Void> original) {
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		WorldScale scale = DiffusicraftWorldgen.scale(settings);
		if (scale != null) {
			Rivers rivers = Rivers.of(TerrainFields.forState(randomState));
			CarvingMask.Filter roofed = CarverRoof.filter(chunk, scale, rivers, settings.seaLevel(), filter);
			// The aquifer knows only the filled blocks (see diffusicraft$fillToTheGround); above them is air.
			int top = noiseChunk.volume().maxBlockY();
			// Sand and gravel would hang over the hole: the carver leaves a block of roof under them. It carves each
			// column top down, so the block above is already carved where the hole goes on up.
			int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
			BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
			filter = (x, y, z) -> y <= top && roofed.test(x, y, z)
				&& !(chunk.getBlockState(above.set(minX + x, y + 1, minZ + z)).getBlock() instanceof FallingBlock);
		}
		original.call(generator, chunk, mask, randomState, materialRule, context, noiseChunk, biomes, filter);
	}
}
