/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.netty;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.SeamlessWorldTracker;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.tracking.ClientStateTracker;
import com.velocitypowered.proxy.protocol.tracking.TrackedPackets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Outbound tap on the player connection tracking what a seamless switch must reconcile:
 * entities, effects, scoreboard names, and content hashes for chunk/recipe/tag dedup.
 */
public class PlayerEntityTrackerHandler extends ChannelOutboundHandlerAdapter {

  public static final String NAME = "player-entity-tracker";

  private static final Logger logger = LogManager.getLogger(PlayerEntityTrackerHandler.class);

  private static final int RELATIVE_EVERYTHING = 0xFF;

  private final ConnectedPlayer player;
  private final MinecraftConnection connection;
  private final TrackedPackets table;
  private boolean parseFailureLogged;

  /**
   * Creates the tracker; the player's protocol version must have a tracked packet table.
   */
  public PlayerEntityTrackerHandler(
      final ConnectedPlayer player,
      final MinecraftConnection connection
  ) {
    this.player = player;
    this.connection = connection;
    this.table = Objects.requireNonNull(
        ClientStateTracker.tableFor(player.getProtocolVersion()),
        "no tracked packet table for " + player.getProtocolVersion());
  }

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg,
      final ChannelPromise promise) throws Exception {
    if (msg instanceof ByteBuf buf && connection.getState() == StateRegistry.PLAY) {
      try {
        // Packet id parsed once; helpers only read further when their own id matched.
        final ByteBuf work = buf.duplicate();
        final int packetId = ProtocolUtils.readVarInt(work);
        if (handleWorldState(packetId, work)) {
          buf.release();
          promise.trySuccess();
          return;
        }
        final ByteBuf transparentSync = handlePositionSync(ctx, packetId, work);
        if (transparentSync != null) {
          buf.release();
          super.write(ctx, transparentSync, promise);
          return;
        }
        ClientStateTracker.trackOutbound(table, packetId, work, player.getClientEntityId(),
            player.getKnownEntityIds(), player.getActiveSelfEffects());
        handleScoreboard(ctx, packetId, work);
      } catch (final Exception e) {
        // Never block delivery, but a parse failure means tracked state is drifting
        // (usually a packet format change), so log it.
        if (!parseFailureLogged) {
          parseFailureLogged = true;
          logger.warn("Failed to track an outbound packet for {} on {}; their seamless "
              + "switches may desync", player.getUsername(), player.getProtocolVersion(), e);
        } else {
          logger.debug("Failed to track an outbound packet for {}", player.getUsername(), e);
        }
      }
    }
    super.write(ctx, msg, promise);
  }

  private boolean handleWorldState(final int packetId, final ByteBuf work) {
    final SeamlessWorldTracker tracker = player.getWorldTracker();
    if (packetId == table.chunkDataId()) {
      final int x = work.readInt();
      final int z = work.readInt();
      return tracker.onChunkData(chunkKey(x, z), hashContents(work));
    } else if (packetId == table.unloadChunkId()) {
      // Forget-level-chunk encodes the position as a single long: z in the high, x in the low
      // bits, so big-endian wire order is z first.
      final int z = work.readInt();
      final int x = work.readInt();
      tracker.onUnloadChunk(chunkKey(x, z));
    } else if (packetId == table.setChunkCenterId()) {
      final int x = ProtocolUtils.readVarInt(work);
      final int z = ProtocolUtils.readVarInt(work);
      tracker.onChunkCenter(x, z);
    } else if (packetId == table.setChunkRadiusId()) {
      tracker.onViewDistance(ProtocolUtils.readVarInt(work));
    } else if (packetId == table.updateRecipesId()) {
      return tracker.onRecipes(hashContents(work));
    } else if (packetId == table.updateTagsId()) {
      return tracker.onTags(hashContents(work));
    }
    return false;
  }

  private void handleScoreboard(final ChannelHandlerContext ctx, final int packetId,
      final ByteBuf work) {
    final boolean objective = packetId == table.setObjectiveId();
    if (!objective && packetId != table.setPlayerTeamId()) {
      return;
    }
    final String name = ProtocolUtils.readString(work);
    final int mode = work.readByte();
    final var known = objective ? player.getKnownObjectives() : player.getKnownTeams();
    final var stale = objective ? player.getStaleObjectives() : player.getStaleTeams();
    if (mode == ClientStateTracker.SCOREBOARD_MODE_CREATE) {
      if (stale.remove(name)) {
        ctx.write(objective
            ? ClientStateTracker.createRemoveObjectivePacket(player.getProtocolVersion(), name,
                ctx.alloc())
            : ClientStateTracker.createRemoveTeamPacket(player.getProtocolVersion(), name,
                ctx.alloc()));
      }
      known.add(name);
    } else if (mode == ClientStateTracker.SCOREBOARD_MODE_REMOVE) {
      known.remove(name);
      stale.remove(name);
    }
  }

  private ByteBuf handlePositionSync(final ChannelHandlerContext ctx, final int packetId,
      final ByteBuf work) {
    if (packetId != table.playerPositionId()
        || player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_2)) {
      return null;
    }
    if (!player.consumePositionSyncTransparency()) {
      return null;
    }
    final int teleportId = ProtocolUtils.readVarInt(work);
    final ByteBuf rewritten = ctx.alloc().buffer();
    ProtocolUtils.writeVarInt(rewritten, table.playerPositionId());
    ProtocolUtils.writeVarInt(rewritten, teleportId);
    for (int i = 0; i < 6; i++) {
      rewritten.writeDouble(0); // position, then delta movement — all relative zero
    }
    rewritten.writeFloat(0); // yaw
    rewritten.writeFloat(0); // pitch
    rewritten.writeInt(RELATIVE_EVERYTHING);
    return rewritten;
  }

  private static long chunkKey(final int x, final int z) {
    return ((long) x << 32) | (z & 0xFFFFFFFFL);
  }

  private static long hashContents(final ByteBuf body) {
    final Hasher hasher = Hashing.murmur3_128().newHasher();
    if (body.nioBufferCount() > 0) {
      for (final ByteBuffer nioBuffer : body.nioBuffers()) {
        hasher.putBytes(nioBuffer);
      }
    } else {
      hasher.putBytes(ByteBufUtil.getBytes(body, body.readerIndex(), body.readableBytes(),
          false));
    }
    return hasher.hash().asLong();
  }
}
