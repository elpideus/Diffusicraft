package io.github.elpideus.diffusicraft.height;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The set of empty sections the renderer copies for every search of what the camera can see. Under a tall sky nearly
 * every section is empty, so vanilla's set held millions of numbers, and copying them a few times a second held up the
 * render thread and filled the heap with arrays too large for the collector to move. Here a column is one bit per row,
 * and a copy shares the columns until one side writes to them.
 *
 * <p>A copy may be read on another thread while the original changes: a written column is replaced, never changed,
 * unless it was made since the last copy.
 */
public final class SectionSet extends LongOpenHashSet {
	/** Every row a {@code BlockPos} can hold. */
	private static final int ROW_BITS = 12;
	private static final int ROWS = 1 << ROW_BITS;
	private static final int FIRST_ROW = -(ROWS >> 1);

	private static final class Column {
		final long[] rows;
		final int generation;
		int count;

		Column(long[] rows, int generation, int count) {
			this.rows = rows;
			this.generation = generation;
			this.count = count;
		}
	}

	private final Long2ObjectOpenHashMap<Column> columns;
	/** Columns of an older generation are shared with a copy. */
	private int generation;
	private int count;

	public SectionSet() {
		this(new Long2ObjectOpenHashMap<>(), 0, 0);
	}

	private SectionSet(Long2ObjectOpenHashMap<Column> columns, int generation, int count) {
		this.columns = columns;
		this.generation = generation;
		this.count = count;
	}

	private static int row(long section) {
		return SectionPos.y(section) - FIRST_ROW;
	}

	private Column writable(long chunk, Column column) {
		Column owned = column == null
			? new Column(new long[ROWS / Long.SIZE], generation, 0)
			: new Column(column.rows.clone(), generation, column.count);
		columns.put(chunk, owned);
		return owned;
	}

	@Override
	public boolean contains(long section) {
		Column column = columns.get(ChunkPos.fromSectionNode(section));
		if (column == null) {
			return false;
		}
		int row = row(section);
		return (column.rows[row >>> 6] & 1L << row) != 0;
	}

	@Override
	public boolean add(long section) {
		long chunk = ChunkPos.fromSectionNode(section);
		Column column = columns.get(chunk);
		int row = row(section);
		if (column != null && (column.rows[row >>> 6] & 1L << row) != 0) {
			return false;
		}
		if (column == null || column.generation != generation) {
			column = writable(chunk, column);
		}
		column.rows[row >>> 6] |= 1L << row;
		column.count++;
		count++;
		return true;
	}

	@Override
	public boolean remove(long section) {
		long chunk = ChunkPos.fromSectionNode(section);
		Column column = columns.get(chunk);
		int row = row(section);
		if (column == null || (column.rows[row >>> 6] & 1L << row) == 0) {
			return false;
		}
		count--;
		if (column.count == 1) {
			columns.remove(chunk);
			return true;
		}
		if (column.generation != generation) {
			column = writable(chunk, column);
		}
		column.rows[row >>> 6] &= ~(1L << row);
		column.count--;
		return true;
	}

	@Override
	public boolean addAll(LongCollection sections) {
		boolean changed = false;
		LongIterator iterator = sections.iterator();
		while (iterator.hasNext()) {
			changed |= add(iterator.nextLong());
		}
		return changed;
	}

	@Override
	public void clear() {
		columns.clear();
		count = 0;
	}

	@Override
	public int size() {
		return count;
	}

	@Override
	public boolean isEmpty() {
		return count == 0;
	}

	@Override
	public LongIterator iterator() {
		LongArrayList sections = new LongArrayList(count);
		for (Long2ObjectMap.Entry<Column> entry : columns.long2ObjectEntrySet()) {
			long[] rows = entry.getValue().rows;
			int x = ChunkPos.getX(entry.getLongKey());
			int z = ChunkPos.getZ(entry.getLongKey());
			for (int word = 0; word < rows.length; word++) {
				for (long left = rows[word]; left != 0; left &= left - 1) {
					sections.add(SectionPos.asLong(x, word * Long.SIZE + Long.numberOfTrailingZeros(left) + FIRST_ROW, z));
				}
			}
		}
		return sections.iterator();
	}

	/** Takes as long as there are columns, not sections. */
	@Override
	public SectionSet clone() {
		generation++;
		return new SectionSet(columns.clone(), generation, count);
	}
}
