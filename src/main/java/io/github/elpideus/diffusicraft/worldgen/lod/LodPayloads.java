package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.Diffusicraft;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** What a server and its clients say to each other about far terrain (see {@link LodFeed}). */
public final class LodPayloads {
	private LodPayloads() {
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(Offer.TYPE, Offer.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Reply.TYPE, Reply.CODEC);
	}

	/**
	 * Sent to a client as it joins: how far from the player, in blocks, the server sends far terrain, how many
	 * requests it may have waiting at once, and the levels whose far terrain it sends.
	 */
	public record Offer(int radius, int maxInFlight, List<Served> levels) implements CustomPacketPayload {
		public static final Type<Offer> TYPE = new Type<>(Diffusicraft.id("far_terrain_offer"));
		static final StreamCodec<FriendlyByteBuf, Offer> CODEC = StreamCodec.ofMember(Offer::write, Offer::new);

		private Offer(FriendlyByteBuf buf) {
			this(buf.readVarInt(), buf.readVarInt(), readLevels(buf));
		}

		private static List<Served> readLevels(FriendlyByteBuf buf) {
			int count = buf.readVarInt();
			if (count < 0 || count > buf.readableBytes()) {
				throw new DecoderException("An offer of far terrain for " + count + " levels");
			}
			List<Served> levels = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				levels.add(new Served(buf));
			}
			return levels;
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(radius);
			buf.writeVarInt(maxInFlight);
			buf.writeVarInt(levels.size());
			for (Served served : levels) {
				served.write(buf);
			}
		}

		@Override
		public Type<Offer> type() {
			return TYPE;
		}
	}

	/** A level whose far terrain the server sends, and the lowest block Y its grids reach. */
	public record Served(ResourceKey<Level> level, int minY) {
		private Served(FriendlyByteBuf buf) {
			this(buf.readResourceKey(Registries.DIMENSION), buf.readVarInt());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeResourceKey(level);
			buf.writeVarInt(minY);
		}
	}

	/**
	 * Asks for the grid that {@link LodGrid#sample} samples from {@code level} with these arguments. {@code id}
	 * matches the reply to it.
	 */
	public record Request(int id, ResourceKey<Level> level, int minX, int minZ, int width, int step, int ceiling)
		implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(Diffusicraft.id("far_terrain_request"));
		static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.ofMember(Request::write, Request::new);

		private Request(FriendlyByteBuf buf) {
			this(buf.readVarInt(), buf.readResourceKey(Registries.DIMENSION), buf.readInt(), buf.readInt(),
				buf.readVarInt(), buf.readVarInt(), buf.readInt());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(id);
			buf.writeResourceKey(level);
			buf.writeInt(minX);
			buf.writeInt(minZ);
			buf.writeVarInt(width);
			buf.writeVarInt(step);
			buf.writeInt(ceiling);
		}

		@Override
		public Type<Request> type() {
			return TYPE;
		}
	}

	/** The answer to request {@code id}: its grid, or none if the server refused it or could not sample it. */
	public record Reply(int id, @Nullable LodGrid grid) implements CustomPacketPayload {
		public static final Type<Reply> TYPE = new Type<>(Diffusicraft.id("far_terrain_reply"));
		static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.ofMember(Reply::write, Reply::new);

		private Reply(RegistryFriendlyByteBuf buf) {
			this(buf.readVarInt(), FriendlyByteBuf.readNullable(buf, LodGrid.STREAM_CODEC));
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeVarInt(id);
			FriendlyByteBuf.writeNullable(buf, grid, LodGrid.STREAM_CODEC);
		}

		@Override
		public Type<Reply> type() {
			return TYPE;
		}
	}
}
