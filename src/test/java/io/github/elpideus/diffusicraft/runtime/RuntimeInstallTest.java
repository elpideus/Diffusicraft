package io.github.elpideus.diffusicraft.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Installs a GPU runtime from the real manifest into the shared storage directory, like the in-game button does.
 * Downloads hundreds of megabytes, so it only runs on request:
 * <pre>
 * ./gradlew test -PwithModels --tests '*RuntimeInstallTest' -Pdiffusicraft.installRuntime=cuda
 * </pre>
 */
@Tag("models")
class RuntimeInstallTest {
	@Test
	@EnabledIfSystemProperty(named = "diffusicraft.installRuntime", matches = ".+")
	void install(@TempDir Path game) throws Exception {
		String id = System.getProperty("diffusicraft.installRuntime");
		Resources resources = Resources.init(game, game.resolve("config"));
		RuntimeManifest.Runtime runtime = resources.manifest().runtime(id).orElseThrow();
		print(resources.installer().status(runtime));

		Job job = resources.installRuntime(id);
		while (!job.state().finished()) {
			TimeUnit.SECONDS.sleep(5);
			System.out.println(job);
		}
		System.out.printf("job %s: %s %s%n", job.id(), job.state(), job.error() == null ? "" : job.error());
		RuntimeInstaller.RuntimeStatus status = resources.installer().status(runtime);
		print(status);
		assertEquals(RuntimeInstaller.RuntimeState.READY, status.state());
	}

	private static void print(RuntimeInstaller.RuntimeStatus status) {
		System.out.printf("%s: %s (native dir %s)%n", status.runtime().id(), status.state(), status.nativeDirectory());
		for (RuntimeInstaller.ComponentStatus component : status.components()) {
			System.out.printf("  %-28s %-9s %s%n", component.component().id(), component.state(), component.libraries());
		}
	}
}
