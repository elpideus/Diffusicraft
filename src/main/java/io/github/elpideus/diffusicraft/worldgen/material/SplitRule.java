package io.github.elpideus.diffusicraft.worldgen.material;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * A Diffusicraft world's own rule ({@link WorldRule}), and the rule other mods made of it ({@code all}), which puts
 * theirs in front of it. Only the own rule runs; its {@link OtherModsRule} runs the rest of {@code all}, the other
 * mods' additions, for their biomes. So the ground of vanilla's biomes and Diffusicraft's stays as Diffusicraft has
 * it, whatever rules other mods add.
 *
 * <p>Built for each chunk from the rule its noise settings return (see
 * {@link io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen#materialRule}); never saved.
 */
public record SplitRule(MaterialRule own, MaterialRule all) implements MaterialRule {
	/** A compiled rule that places nothing. */
	public static final RuleEvaluator NOTHING = (x, y, z) -> null;
	/** The rule whose additions an {@link OtherModsRule} compiles, while its world's rule compiles. */
	private static final ThreadLocal<@Nullable MaterialRule> ADDITIONS = new ThreadLocal<>();
	/** Whether the {@link WorldRule}s compiled now give nothing. */
	private static final ThreadLocal<Boolean> MUTED = ThreadLocal.withInitial(() -> false);

	@Override
	public MapCodec<SplitRule> codec() {
		throw new UnsupportedOperationException("Diffusicraft's split rule is never saved");
	}

	@Override
	public RuleEvaluator compile(MaterialRuleContext context) {
		MaterialRule previous = ADDITIONS.get();
		ADDITIONS.set(all);
		try {
			return own.compile(context);
		} finally {
			ADDITIONS.set(previous);
		}
	}

	/**
	 * The other mods' additions to the rule now compiling, compiled without Diffusicraft's own rule, or {@code null}
	 * when they added none.
	 */
	static @Nullable RuleEvaluator additions(MaterialRuleContext context) {
		MaterialRule all = ADDITIONS.get();
		if (all == null || MUTED.get()) {
			return null;
		}
		MUTED.set(true);
		try {
			return all.compile(context);
		} finally {
			MUTED.set(false);
		}
	}

	static boolean muted() {
		return MUTED.get();
	}
}
