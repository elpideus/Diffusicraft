package io.github.elpideus.diffusicraft.worldgen.biome;

import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaBiomeTableTest {
	/** Without mixins, as here, the copy of the builder builds the same table as the game's. */
	@Test
	void theCopyBuildsTheGamesTable() throws Exception {
		assertEquals(MultiNoiseBiomeSourceParameterList.knownPresets()
				.get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD).values(), VanillaBiomeTable.pristine());
	}
}
