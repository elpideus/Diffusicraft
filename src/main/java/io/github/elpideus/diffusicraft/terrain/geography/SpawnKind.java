package io.github.elpideus.diffusicraft.terrain.geography;

/**
 * The kind of place where a world's spawn lies. Each seed draws one, most often a coast and least often the depths or
 * the heights, and {@link Geography} puts such a place at the origin of the world.
 */
public enum SpawnKind {
	/** On the coast of a continent, by the sea. */
	COAST(0.55),
	/** Inside a continent, most often on a plain. */
	INLAND(0.33),
	/** On a small island far out at sea. */
	ISLAND(0.08),
	/** On the floor of an Ancient Grand Hollow, deep under a continent. */
	HOLLOW(0.02),
	/** Near the summit of a high mountain. */
	PEAK(0.02);

	private final double chance;

	SpawnKind(double chance) {
		this.chance = chance;
	}

	/** The chance that a seed draws this kind. */
	public double chance() {
		return chance;
	}

	/** The kind for a uniform value in [0, 1). */
	static SpawnKind draw(double u) {
		double sum = 0;
		for (SpawnKind kind : values()) {
			sum += kind.chance;
			if (u < sum) {
				return kind;
			}
		}
		return INLAND;
	}
}
