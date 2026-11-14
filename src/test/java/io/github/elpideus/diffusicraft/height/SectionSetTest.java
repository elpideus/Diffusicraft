package io.github.elpideus.diffusicraft.height;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Random;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

class SectionSetTest {
	private static long section(Random random) {
		return SectionPos.asLong(random.nextInt(8) - 4, random.nextInt(4096) - 2048, random.nextInt(8) - 4);
	}

	@Test
	void behavesLikeTheSetItReplaces() {
		Random random = new Random(1);
		SectionSet set = new SectionSet();
		LongOpenHashSet expected = new LongOpenHashSet();
		for (int i = 0; i < 200_000; i++) {
			long section = section(random);
			if (random.nextInt(3) == 0) {
				assertEquals(expected.remove(section), set.remove(section));
			} else {
				assertEquals(expected.add(section), set.add(section));
			}
		}
		assertEquals(expected.size(), set.size());
		for (int i = 0; i < 100_000; i++) {
			long section = section(random);
			assertEquals(expected.contains(section), set.contains(section));
		}
		LongOpenHashSet listed = new LongOpenHashSet();
		for (LongIterator iterator = set.iterator(); iterator.hasNext(); ) {
			listed.add(iterator.nextLong());
		}
		assertEquals(expected, listed);
	}

	@Test
	void aCopyKeepsWhatItSaw() {
		Random random = new Random(2);
		SectionSet set = new SectionSet();
		LongOpenHashSet added = new LongOpenHashSet();
		for (int i = 0; i < 20_000; i++) {
			long section = section(random);
			set.add(section);
			added.add(section);
		}
		SectionSet copy = set.clone();
		SectionSet copyOfCopy = copy.clone();
		LongOpenHashSet changed = new LongOpenHashSet();
		for (int i = 0; i < 40_000; i++) {
			long section = section(random);
			if (added.contains(section)) {
				set.remove(section);
			} else {
				set.add(section);
			}
			changed.add(section);
		}
		copy.remove(added.iterator().nextLong());
		copy.add(added.iterator().nextLong());

		for (LongIterator iterator = added.iterator(); iterator.hasNext(); ) {
			long section = iterator.nextLong();
			assertTrue(copy.contains(section));
			assertTrue(copyOfCopy.contains(section));
		}
		assertEquals(added.size(), copy.size());
		for (LongIterator iterator = changed.iterator(); iterator.hasNext(); ) {
			long section = iterator.nextLong();
			assertEquals(added.contains(section), copyOfCopy.contains(section));
			assertEquals(!added.contains(section), set.contains(section));
		}
	}

	@Test
	void forgetsColumnsItNoLongerHolds() {
		SectionSet set = new SectionSet();
		long section = SectionPos.asLong(1, 5, 1);
		set.add(section);
		set.remove(section);
		assertFalse(set.contains(section));
		assertTrue(set.isEmpty());
		assertFalse(set.iterator().hasNext());
	}
}
