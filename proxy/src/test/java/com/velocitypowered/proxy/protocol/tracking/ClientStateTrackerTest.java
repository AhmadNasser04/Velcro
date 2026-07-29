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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientStateTrackerTest {

  private static final Set<ProtocolVersion> EXPECTED_SUPPORTED = EnumSet.range(
      ProtocolVersion.MINECRAFT_1_20_5, ProtocolVersion.MINECRAFT_26_2);

  @Test
  void supportsEveryCookieCapableVersion() {
    for (final ProtocolVersion version : ProtocolVersion.values()) {
      assertEquals(EXPECTED_SUPPORTED.contains(version),
          ClientStateTracker.isSupported(version),
          "unexpected support for " + version);
    }
  }

  /**
   * Guards the generated tables against generator regressions: protocol 773's IDs were
   * verified by hand against minecraft-data and live traffic.
   */
  @Test
  void protocol773TableMatchesHandVerifiedIds() {
    final TrackedPackets table = ClientStateTracker.tableFor(ProtocolVersion.MINECRAFT_1_21_9);
    assertEquals(new TrackedPackets(0x01, -1, 0x4B, 0x82, 0x4C, 0x2C, 0x25, 0x5C, 0x5D, 0x83,
        0x84, 0x68, 0x6B, 0x46, 0x09), table);
    assertEquals(table, ClientStateTracker.tableFor(ProtocolVersion.MINECRAFT_1_21_11));
  }

  @Test
  void scoreboardRemovalPacketsRoundTrip() {
    final TrackedPackets table = ClientStateTracker.tableFor(ProtocolVersion.MINECRAFT_1_21_9);
    final io.netty.buffer.ByteBuf remove = ClientStateTracker.createRemoveObjectivePacket(
        ProtocolVersion.MINECRAFT_1_21_9, "sidebar",
        io.netty.buffer.UnpooledByteBufAllocator.DEFAULT);
    assertEquals(table.setObjectiveId(),
        com.velocitypowered.proxy.protocol.ProtocolUtils.readVarInt(remove));
    assertEquals("sidebar", com.velocitypowered.proxy.protocol.ProtocolUtils.readString(remove));
    assertEquals(ClientStateTracker.SCOREBOARD_MODE_REMOVE, remove.readByte());
    assertEquals(0, remove.readableBytes(), "removal must carry nothing after the mode byte");
    remove.release();
  }

  @Test
  void unsupportedVersionsHaveNoTable() {
    assertFalse(ClientStateTracker.isSupported(ProtocolVersion.MINECRAFT_1_20_3));
    assertTrue(ClientStateTracker.tableFor(ProtocolVersion.MINECRAFT_1_20_2) == null);
  }
}
