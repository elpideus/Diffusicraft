package io.github.elpideus.diffusicraft.mixin;

import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The world's root random. Density functions derive their seeds from it with {@code fromHashOf}; the strata rule
 * needs the same seeds, which {@link RandomState}'s public methods do not give out.
 */
@Mixin(RandomState.class)
public interface RandomStateAccessor {
	@Accessor("random")
	PositionalRandomFactory diffusicraft$random();
}
