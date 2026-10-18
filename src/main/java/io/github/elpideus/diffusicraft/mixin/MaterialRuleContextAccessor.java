package io.github.elpideus.diffusicraft.mixin;

import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the strata rule find the world's random state, and through it the terrain field the chunk was built from. */
@Mixin(MaterialRuleContext.class)
public interface MaterialRuleContextAccessor {
	@Accessor("randomState")
	RandomState diffusicraft$randomState();
}
