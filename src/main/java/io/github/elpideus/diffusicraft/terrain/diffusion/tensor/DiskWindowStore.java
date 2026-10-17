package io.github.elpideus.diffusicraft.terrain.diffusion.tensor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stores each window as a raw little-endian float file at {@code <root>/<tensorId>/<wi>/<wj>.bin}.
 * Writes go to a temporary file that is atomically renamed, so readers see either nothing or a whole window.
 * A file with an unexpected length is treated as absent.
 */
public final class DiskWindowStore implements WindowStore {
	private final Path root;

	public DiskWindowStore(Path root) {
		this.root = root;
	}

	public Path root() {
		return root;
	}

	private Path file(String tensorId, int wi, int wj) {
		return root.resolve(tensorId).resolve(Integer.toString(wi)).resolve(wj + ".bin");
	}

	@Override
	public Tile load(String tensorId, int wi, int wj, int channels, int size) throws IOException {
		Path file = file(tensorId, wi, wj);
		int floats = channels * size * size;
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			if (channel.size() != (long) floats * Float.BYTES) {
				return null;
			}
			ByteBuffer bytes = ByteBuffer.allocate(floats * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
			while (bytes.hasRemaining()) {
				if (channel.read(bytes) < 0) {
					return null;
				}
			}
			bytes.flip();
			float[] data = new float[floats];
			bytes.asFloatBuffer().get(data);
			return new Tile(channels, size, size, data);
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	@Override
	public void save(String tensorId, int wi, int wj, Tile tile) throws IOException {
		Path file = file(tensorId, wi, wj);
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
		ByteBuffer bytes = ByteBuffer.allocate(tile.data.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		bytes.asFloatBuffer().put(tile.data);
		try {
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				while (bytes.hasRemaining()) {
					channel.write(bytes);
				}
			}
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
