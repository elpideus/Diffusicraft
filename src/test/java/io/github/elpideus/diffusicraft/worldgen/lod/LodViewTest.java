package io.github.elpideus.diffusicraft.worldgen.lod;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodViewTest {
	private static final LodTiles.Tile COARSE = new LodTiles.Tile(3, 1, 2);
	private static final List<LodTiles.Tile> FINE = List.of(
		new LodTiles.Tile(2, 2, 4), new LodTiles.Tile(2, 3, 4), new LodTiles.Tile(2, 2, 5), new LodTiles.Tile(2, 3, 5));

	private final List<LodTiles.Tile> freed = new ArrayList<>();
	private final LodView<LodTiles.Tile> view = new LodView<>(freed::add);

	@Test
	void aTileShowsOnceBuiltAndIsFreedOnceNoLongerWanted() {
		assertEquals(List.of(COARSE), view.want(List.of(COARSE)));
		assertEquals(List.of(), view.drawn());
		view.built(COARSE, COARSE);
		assertEquals(List.of(COARSE), view.drawn());
		assertEquals(List.of(), view.want(List.of(COARSE)));
		view.want(List.of());
		assertEquals(List.of(), view.drawn());
		assertEquals(List.of(COARSE), freed);
	}

	@Test
	void aCoarseTileStaysUntilEveryFinerTileWithinItIsBuilt() {
		view.want(List.of(COARSE));
		view.built(COARSE, COARSE);
		assertEquals(FINE, view.want(FINE));
		for (int i = 0; i < FINE.size() - 1; i++) {
			view.built(FINE.get(i), FINE.get(i));
			// The finer tiles built so far wait hidden, so nothing is drawn twice.
			assertEquals(List.of(COARSE), view.drawn());
		}
		view.built(FINE.getLast(), FINE.getLast());
		assertEquals(Set.copyOf(FINE), Set.copyOf(view.drawn()));
		assertEquals(List.of(COARSE), freed);
	}

	@Test
	void finerTilesStayUntilTheCoarseTileReplacingThemIsBuilt() {
		view.want(FINE);
		FINE.forEach(tile -> view.built(tile, tile));
		assertEquals(List.of(COARSE), view.want(List.of(COARSE)));
		assertEquals(Set.copyOf(FINE), Set.copyOf(view.drawn()));
		assertEquals(List.of(), freed);
		view.built(COARSE, COARSE);
		assertEquals(List.of(COARSE), view.drawn());
		assertEquals(Set.copyOf(FINE), Set.copyOf(freed));
	}

	@Test
	void theCoarsestTileStandingInHidesTheOnesWithinIt() {
		LodTiles.Tile coarser = new LodTiles.Tile(4, 0, 1);
		LodTiles.Tile other = new LodTiles.Tile(3, 0, 3);
		view.want(List.of(coarser));
		view.built(coarser, coarser);
		view.want(List.of(COARSE, other));
		view.built(COARSE, COARSE);
		// The viewer wants finer tiles under both: the coarser one stands in, and the one within it goes.
		view.want(List.of(FINE.get(0), FINE.get(1), FINE.get(2), FINE.get(3), other));
		assertEquals(List.of(coarser), view.drawn());
		assertTrue(freed.contains(COARSE), freed.toString());
	}

	@Test
	void aTileBuiltAgainFreesItsOldMesh() {
		view.want(List.of(COARSE));
		LodTiles.Tile old = new LodTiles.Tile(3, 1, 2);
		view.built(COARSE, old);
		view.built(COARSE, COARSE);
		assertTrue(freed.size() == 1 && freed.getFirst() == old, freed.toString());
		view.forget(COARSE);
		assertEquals(List.of(), view.drawn());
		assertEquals(List.of(COARSE), view.want(List.of(COARSE)));
	}
}
