package io.github.elpideus.diffusicraft.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes the cache of a structure template's blocks by kind safe to fill from several threads. Vanilla keeps it in a
 * plain map, which every thread placing that template fills as it goes; the far terrain places structures on threads
 * of its own beside the chunks' (see {@code FarStructures}), and two threads filling it at once threw, or could lose
 * the cache of the real chunks.
 */
@Mixin(StructureTemplate.Palette.class)
abstract class StructureTemplatePaletteMixin {
	@Shadow
	@Final
	@Mutable
	private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void diffusicraft$concurrentCache(CallbackInfo info) {
		cache = new ConcurrentHashMap<>();
	}
}
