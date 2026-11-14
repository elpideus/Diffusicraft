package io.github.elpideus.diffusicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.elpideus.diffusicraft.worldgen.UnloadedChunks;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;

/**
 * Runs the functions data packs tick, which often look at each player's biome, without waiting; see
 * {@link UnloadedChunks}. Their load functions still wait: one may force a chunk to load and then place something in
 * it, as Incendium does.
 */
@Mixin(ServerFunctionManager.class)
abstract class ServerFunctionManagerMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ServerFunctionManager;"
		+ "executeTagFunctions(Ljava/util/Collection;Lnet/minecraft/resources/Identifier;)V", ordinal = 1))
	private void diffusicraft$neverWaitForChunks(ServerFunctionManager manager, Collection<?> functions, Identifier tag,
		Operation<Void> original) {
		UnloadedChunks.around(server.overworld(), () -> original.call(manager, functions, tag));
	}
}
