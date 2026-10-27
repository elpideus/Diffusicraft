package io.github.elpideus.diffusicraft.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.config.IrisConfig;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Iris with a shader pack. The test pack ({@code src/gametest/resources/diffusicraft-gametest/shaderpack}) casts
 * shadows, which has Iris walk the sections round the player a second time, from the sun, and draws Distant Horizons'
 * far terrain through programs of its own, as packs that support it do, into its shadow map as well. Installs the pack
 * and turns it on, checks in a flat world that Iris runs it and draws its shadow pass, and saves a screenshot
 * ({@code diffusicraft-shaders-flat.png}).
 * {@code -Pdiffusicraft.shaderPack=bliss-shader.zip} turns on a pack from the game's {@code shaderpacks} folder in its
 * place, such as one the dev runtime put there ({@code -PdevMods=sodium,iris,bliss-shader}). Gradle turns that pack
 * on in Iris's config before the game starts too, so it is on for every test, this one or not.
 *
 * <p>The pack stays on for the tests that run after this one: {@code -Pdiffusicraft.gametests=Shaders,TallDimension}
 * runs the tall dimension's checks under it. Runs only when named so, and with Iris installed
 * ({@code -PdevMods=sodium,iris}).
 */
public class ShadersGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Diffusicraft shaders test");
	private static final String PACK = "diffusicraft-test";
	private static final String[] FILES = {
		"gbuffers_textured_lit.vsh", "gbuffers_textured_lit.fsh", "shadow.vsh", "shadow.fsh", "composite.vsh",
		"composite.fsh", "dh_terrain.vsh", "dh_terrain.fsh", "dh_water.vsh", "dh_water.fsh", "dh_shadow.vsh",
		"dh_shadow.fsh",
	};

	@Override
	public void runTest(ClientGameTestContext context) {
		// Only when named: it leaves the pack on for every test after it.
		if (!GameTests.named(this)) {
			return;
		}
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			LOGGER.info("Skipping ShadersGameTest: Iris is not installed (-PdevMods=sodium,iris)");
			return;
		}
		install();
		String pack = System.getProperty("diffusicraft.shaderPack", PACK);
		if (!Files.exists(FabricLoader.getInstance().getGameDir().resolve("shaderpacks").resolve(pack))) {
			throw new AssertionError("No shader pack " + pack + " in the game's shaderpacks folder");
		}
		LOGGER.info("Shader pack: {}", pack);
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> IrisPack.enable(pack));

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			BlockPos ground = context.computeOnClient(mc -> mc.player.blockPosition().below());
			GameTests.waitForRender(context, world.getConnection(), ground);
			if (!context.computeOnClient(mc -> IrisPack.inUse())) {
				throw new AssertionError("Iris is not running " + pack + ": see the log for why it could not load it");
			}
			int before = IrisPack.shadowPasses();
			context.waitTicks(20);
			int passes = IrisPack.shadowPasses() - before;
			LOGGER.info("Iris drew {} shadow passes in 20 ticks", passes);
			if (passes == 0) {
				throw new AssertionError("Iris drew no shadow pass");
			}
			LOGGER.info("Screenshot: {}", context.takeScreenshot("diffusicraft-shaders-flat").toAbsolutePath());
		}
		LOGGER.info("{} stays on for the tests that follow", pack);
	}

	/** Copies the test pack into the game's {@code shaderpacks} folder. */
	private static void install() {
		Path shaders = FabricLoader.getInstance().getGameDir().resolve("shaderpacks").resolve(PACK).resolve("shaders");
		try {
			Files.createDirectories(shaders);
			for (String name : FILES) {
				try (InputStream in = ShadersGameTest.class
					.getResourceAsStream("/diffusicraft-gametest/shaderpack/shaders/" + name)) {
					if (in == null) {
						throw new IllegalStateException("The test pack has no " + name);
					}
					Files.copy(in, shaders.resolve(name), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Everything that touches Iris, so that its classes load only when it is installed. Render thread. */
	private static final class IrisPack {
		private static final AtomicInteger SHADOW_PASSES = new AtomicInteger();
		private static boolean listening;

		static void enable(String pack) {
			if (!listening) {
				IrisApi.getInstance().registerShadowRenderCallback(
					(modelView, projection, x, y, z, tickDelta) -> SHADOW_PASSES.incrementAndGet());
				listening = true;
			}
			IrisConfig config = Iris.getIrisConfig();
			config.setShaderPackName(pack);
			config.setShadersEnabled(true);
			try {
				config.save();
				// Reads the configuration back and loads the pack.
				Iris.reload();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		static boolean inUse() {
			return IrisApi.getInstance().isShaderPackInUse();
		}

		static int shadowPasses() {
			return SHADOW_PASSES.get();
		}
	}
}
