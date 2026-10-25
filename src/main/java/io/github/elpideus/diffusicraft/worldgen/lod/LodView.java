package io.github.elpideus.diffusicraft.worldgen.lod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The tiles of far terrain a viewer sees, while those it moves towards are still being built. The view draws each
 * tile it wants once its mesh is ready. Until then the tiles already built stand in for it, so the far terrain
 * neither shows holes nor draws one place twice:
 *
 * <ul>
 *     <li>A coarse tile the viewer has come closer to stays drawn until every finer tile wanted within it is ready,
 *     and those wait hidden until then.</li>
 *     <li>Finer tiles the viewer has moved away from stay drawn until the coarse tile that replaces them is ready.</li>
 * </ul>
 *
 * <p>A mesh that is neither wanted nor standing in for one is handed back to be freed. Not thread-safe: use it from
 * the render thread.
 *
 * @param <M> a tile's mesh
 */
public final class LodView<M> {
	private final Consumer<M> free;
	private final Map<LodTiles.Tile, M> ready = new HashMap<>();
	private Set<LodTiles.Tile> wanted = new LinkedHashSet<>();
	private final List<M> drawn = new ArrayList<>();
	private boolean changed;

	/** A view that hands each mesh it drops to {@code free}. */
	public LodView(Consumer<M> free) {
		this.free = free;
	}

	/** Sets the tiles the viewer wants now, nearest first. Returns those it wants that are not built yet. */
	public List<LodTiles.Tile> want(List<LodTiles.Tile> tiles) {
		wanted = new LinkedHashSet<>(tiles);
		changed = true;
		List<LodTiles.Tile> missing = new ArrayList<>();
		for (LodTiles.Tile tile : wanted) {
			if (!ready.containsKey(tile)) {
				missing.add(tile);
			}
		}
		return missing;
	}

	/** Whether the view still wants a tile, so that it is worth building. */
	public boolean wants(LodTiles.Tile tile) {
		return wanted.contains(tile);
	}

	/** Takes the mesh of a tile that was built. A tile no longer wanted, or built anew, frees its other mesh. */
	public void built(LodTiles.Tile tile, M mesh) {
		M old = ready.put(tile, mesh);
		if (old != null && old != mesh) {
			free.accept(old);
		}
		changed = true;
	}

	/** Drops the mesh of a tile, which is to be built again, as when the terrain under it changed. */
	public void forget(LodTiles.Tile tile) {
		M old = ready.remove(tile);
		if (old != null) {
			free.accept(old);
			changed = true;
		}
	}

	/** How many tiles have a mesh, drawn or not. */
	public int size() {
		return ready.size();
	}

	/** The meshes to draw, coarsest first. */
	public List<M> drawn() {
		if (changed) {
			update();
			changed = false;
		}
		return drawn;
	}

	/** Frees every mesh. */
	public void clear() {
		ready.values().forEach(free);
		ready.clear();
		drawn.clear();
		wanted = new LinkedHashSet<>();
	}

	private void update() {
		Set<LodTiles.Tile> standing = new HashSet<>();
		Set<LodTiles.Tile> covering = new HashSet<>();
		for (LodTiles.Tile tile : wanted) {
			if (ready.containsKey(tile)) {
				continue;
			}
			LodTiles.Tile ancestor = readyAncestor(tile);
			if (ancestor != null) {
				covering.add(ancestor);
			} else {
				for (LodTiles.Tile built : ready.keySet()) {
					if (built.level() < tile.level() && within(built, tile)) {
						standing.add(built);
					}
				}
			}
		}
		Set<LodTiles.Tile> shown = new HashSet<>(covering);
		shown.addAll(standing);
		for (LodTiles.Tile tile : wanted) {
			if (ready.containsKey(tile) && !covered(tile, covering)) {
				shown.add(tile);
			}
		}
		// What lies under a covering tile, a stand-in or a coarse tile itself, is hidden too.
		shown.removeIf(tile -> covered(tile, covering));
		List<LodTiles.Tile> order = new ArrayList<>(shown);
		order.sort((a, b) -> Integer.compare(b.level(), a.level()));
		drawn.clear();
		for (LodTiles.Tile tile : order) {
			drawn.add(ready.get(tile));
		}
		List<LodTiles.Tile> unused = new ArrayList<>();
		for (LodTiles.Tile tile : ready.keySet()) {
			if (!shown.contains(tile) && !wanted.contains(tile)) {
				unused.add(tile);
			}
		}
		for (LodTiles.Tile tile : unused) {
			free.accept(ready.remove(tile));
		}
	}

	private LodTiles.Tile readyAncestor(LodTiles.Tile tile) {
		for (int level = tile.level() + 1; level < LodStore.LEVELS; level++) {
			int shift = level - tile.level();
			LodTiles.Tile ancestor = new LodTiles.Tile(level, tile.x() >> shift, tile.z() >> shift);
			if (ready.containsKey(ancestor)) {
				return ancestor;
			}
		}
		return null;
	}

	private static boolean covered(LodTiles.Tile tile, Set<LodTiles.Tile> covering) {
		for (int level = tile.level() + 1; level < LodStore.LEVELS; level++) {
			int shift = level - tile.level();
			if (covering.contains(new LodTiles.Tile(level, tile.x() >> shift, tile.z() >> shift))) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code inner}, of a finer level, lies within {@code outer}. */
	private static boolean within(LodTiles.Tile inner, LodTiles.Tile outer) {
		int shift = outer.level() - inner.level();
		return inner.x() >> shift == outer.x() && inner.z() >> shift == outer.z();
	}
}
