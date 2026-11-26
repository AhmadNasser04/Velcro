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

package com.velocitypowered.proxy.connection.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NetworkEntityIdsTest {

  private static final int BLOCK_SIZE = 1 << 24;

  @Test
  void everyProxyIndexOwnsItsOwnBlock() {
    int previousBlockEnd = 1_600_000_000;
    for (int index = 0; index <= NetworkEntityIds.MAX_PROXY_INDEX; index++) {
      final int first = new NetworkEntityIds(index).next();
      assertEquals(previousBlockEnd, first, "block " + index + " must start where the last ended");
      previousBlockEnd = first + BLOCK_SIZE;
      assertTrue(previousBlockEnd > first, "block " + index + " must fit below the int limit");
    }
  }

  @Test
  void idsStartOverInsideTheBlock() {
    final NetworkEntityIds ids = new NetworkEntityIds(3);
    final int first = ids.next();
    int last = first;
    for (int i = 1; i < BLOCK_SIZE; i++) {
      last = ids.next();
    }
    assertEquals(first + BLOCK_SIZE - 1, last);
    assertEquals(first, ids.next());
  }

  @Test
  void rejectsIndexesOutsideTheRange() {
    assertThrows(IllegalArgumentException.class, () -> new NetworkEntityIds(-1));
    assertThrows(IllegalArgumentException.class,
        () -> new NetworkEntityIds(NetworkEntityIds.MAX_PROXY_INDEX + 1));
  }
}
