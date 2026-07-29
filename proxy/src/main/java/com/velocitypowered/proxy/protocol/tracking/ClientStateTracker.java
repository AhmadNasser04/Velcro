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

package com.velocitypowered.proxy.protocol.tracking;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tracks the client-visible state a seamless server switch must reconcile (the entity IDs the
 * client knows and the potion effects on its own player) and crafts the raw removal packets that
 * reconciliation sends. Driven by per-protocol {@link TrackedPackets packet-ID tables}; versions
 * without a table cannot switch without a world reset.
 */
public final class ClientStateTracker {

  private static final Map<ProtocolVersion, TrackedPackets> TABLES = TrackedPacketTables.TABLES;

  /**
   * The scoreboard modes shared by the update-objectives and update-teams packets: a leading
   * string name followed by a byte selecting create (0) or remove (1). Stable across every
   * supported protocol version.
   */
  public static final int SCOREBOARD_MODE_CREATE = 0;
  public static final int SCOREBOARD_MODE_REMOVE = 1;

  private ClientStateTracker() {
    throw new AssertionError();
  }

  /**
   * Returns whether client-state tracking (and therefore switching without a world reset) is
   * available for the given protocol version.
   */
  public static boolean isSupported(final ProtocolVersion version) {
    return TABLES.containsKey(version);
  }

  /**
   * Returns the packet table for the given protocol version, or {@code null} when unsupported.
   */
  public static @Nullable TrackedPackets tableFor(final ProtocolVersion version) {
    return TABLES.get(version);
  }

  /**
   * Updates the tracked client-visible state from an outbound packet as the client will see it:
   * the entity IDs it knows and the potion effects on its own player. The buffer must be
   * positioned right after the packet ID and is only read when the ID matches a tracked packet.
   */
  public static void trackOutbound(final TrackedPackets table, final int packetId,
      final ByteBuf work, final int clientEntityId, final IntSet knownEntities,
      final IntSet selfEffects) {
    if (packetId == table.addEntityId()
        || (table.addExperienceOrbId() != -1 && packetId == table.addExperienceOrbId())) {
      knownEntities.add(ProtocolUtils.readVarInt(work));
    } else if (packetId == table.removeEntitiesId()) {
      final int count = ProtocolUtils.readVarInt(work);
      for (int i = 0; i < count; i++) {
        knownEntities.remove(ProtocolUtils.readVarInt(work));
      }
    } else if (packetId == table.entityEffectId()) {
      if (ProtocolUtils.readVarInt(work) == clientEntityId) {
        selfEffects.add(ProtocolUtils.readVarInt(work));
      }
    } else if (packetId == table.removeEntityEffectId()) {
      if (ProtocolUtils.readVarInt(work) == clientEntityId) {
        selfEffects.remove(ProtocolUtils.readVarInt(work));
      }
    }
  }

  /**
   * Builds a raw clientbound update-objectives packet removing the given objective. Removing an
   * objective also clears any display slot showing it and all its scores.
   */
  public static ByteBuf createRemoveObjectivePacket(final ProtocolVersion version,
      final String objective, final ByteBufAllocator alloc) {
    return createScoreboardRemovePacket(requireTable(version).setObjectiveId(), objective, alloc);
  }

  /**
   * Builds a raw clientbound update-teams packet removing the given team.
   */
  public static ByteBuf createRemoveTeamPacket(final ProtocolVersion version,
      final String team, final ByteBufAllocator alloc) {
    return createScoreboardRemovePacket(requireTable(version).setPlayerTeamId(), team, alloc);
  }

  private static ByteBuf createScoreboardRemovePacket(final int packetId, final String name,
      final ByteBufAllocator alloc) {
    final ByteBuf buf = alloc.buffer(name.length() + 8);
    ProtocolUtils.writeVarInt(buf, packetId);
    ProtocolUtils.writeString(buf, name);
    buf.writeByte(SCOREBOARD_MODE_REMOVE);
    return buf;
  }

  /**
   * Builds a raw clientbound remove-entity-effect packet.
   */
  public static ByteBuf createRemoveEffectPacket(final ProtocolVersion version,
      final int entityId, final int effectId, final ByteBufAllocator alloc) {
    final ByteBuf buf = alloc.buffer(8);
    ProtocolUtils.writeVarInt(buf, requireTable(version).removeEntityEffectId());
    ProtocolUtils.writeVarInt(buf, entityId);
    ProtocolUtils.writeVarInt(buf, effectId);
    return buf;
  }

  /**
   * Builds a raw clientbound remove-entities packet for the given IDs.
   */
  public static ByteBuf createRemoveEntitiesPacket(final ProtocolVersion version,
      final IntCollection entityIds, final ByteBufAllocator alloc) {
    final ByteBuf buf = alloc.buffer(entityIds.size() * 3 + 4);
    ProtocolUtils.writeVarInt(buf, requireTable(version).removeEntitiesId());
    ProtocolUtils.writeVarInt(buf, entityIds.size());
    for (final IntIterator it = entityIds.iterator(); it.hasNext(); ) {
      ProtocolUtils.writeVarInt(buf, it.nextInt());
    }
    return buf;
  }

  private static TrackedPackets requireTable(final ProtocolVersion version) {
    final TrackedPackets table = TABLES.get(version);
    if (table == null) {
      throw new IllegalStateException("No tracked packet table for " + version);
    }
    return table;
  }
}
