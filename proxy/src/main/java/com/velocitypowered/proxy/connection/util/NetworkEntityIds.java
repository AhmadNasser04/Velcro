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

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out the proxy-owned entity ids. They start at 1.6b so they can't collide with backend
 * ids counting up from 0, and every proxy takes them from its own block, so two proxies on one
 * network never give two players the same id.
 */
public final class NetworkEntityIds {
  public static final int MAX_PROXY_INDEX = 31;

  private static final int FIRST_ID = 1_600_000_000;
  private static final int BLOCK_SIZE = 1 << 24;

  private final int blockStart;
  private final AtomicInteger issued = new AtomicInteger();

  /**
   * Creates the allocator for one proxy.
   *
   * @param proxyIndex this proxy's number on the network, unique among its proxies
   */
  public NetworkEntityIds(final int proxyIndex) {
    Preconditions.checkArgument(proxyIndex >= 0 && proxyIndex <= MAX_PROXY_INDEX,
        "proxy index must be between 0 and %s, got %s", MAX_PROXY_INDEX, proxyIndex);
    this.blockStart = FIRST_ID + proxyIndex * BLOCK_SIZE;
  }

  /**
   * Returns the next id of this proxy's block. A block holds 16.7m ids and starts over when it
   * runs out, long after the player who had the first one is gone.
   */
  public int next() {
    return blockStart + (issued.getAndIncrement() & (BLOCK_SIZE - 1));
  }
}
