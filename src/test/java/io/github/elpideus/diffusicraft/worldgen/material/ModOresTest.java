package io.github.elpideus.diffusicraft.worldgen.material;

import io.github.elpideus.diffusicraft.worldgen.material.ModOres.Candidate;
import io.github.elpideus.diffusicraft.worldgen.material.ModOres.Choice;
import io.github.elpideus.diffusicraft.worldgen.material.ModOres.Ground;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModOresTest {
	private static Candidate<String> ore(String id, Ground ground) {
		return new Candidate<>(id, id, ground);
	}

	@Test
	void picksTheOresInStoneAndDeepslate() {
		Choice<String> choice = ModOres.choose(List.of(ore("create:deepslate_zinc_ore", Ground.DEEPSLATE),
			ore("create:zinc_ore", Ground.STONE)));
		assertEquals(new Choice<>("create:zinc_ore", "create:deepslate_zinc_ore"), choice);
	}

	@Test
	void theFirstModByIdWins() {
		Choice<String> choice = ModOres.choose(List.of(ore("techreborn:tin_ore", Ground.STONE),
			ore("techreborn:deepslate_tin_ore", Ground.DEEPSLATE), ore("mekanism:tin_ore", Ground.STONE),
			ore("mekanism:deepslate_tin_ore", Ground.DEEPSLATE)));
		assertEquals(new Choice<>("mekanism:tin_ore", "mekanism:deepslate_tin_ore"), choice);
	}

	@Test
	void eitherGroundStandsInForTheOther() {
		assertEquals(new Choice<>("a:deep", "a:deep"), ModOres.choose(List.of(ore("a:deep", Ground.DEEPSLATE))));
		assertEquals(new Choice<>("a:ore", "a:ore"), ModOres.choose(List.of(ore("a:nether", Ground.OTHER),
			ore("a:ore", Ground.STONE))));
	}

	@Test
	void netherAndEndOresAreNeverPlaced() {
		assertNull(ModOres.choose(List.of(ore("a:nether_silver_ore", Ground.OTHER),
			ore("a:end_silver_ore", Ground.OTHER))));
		assertNull(ModOres.choose(List.<Candidate<String>>of()));
	}
}
