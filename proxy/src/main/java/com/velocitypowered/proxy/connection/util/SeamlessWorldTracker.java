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

import java.util.HashMap;
import java.util.Map;

/**
 * Content hashes of the world data the client holds (chunks, recipes, tags) so byte-identical
 * re-sends after a seamless switch can be dropped. Trimmed like the client's own chunk cache
 * so it never withholds data the client discarded.
 */
public final class SeamlessWorldTracker {
  private static final int CLIENT_STORAGE_MARGIN = 3;
  private static final int CLIENT_STORAGE_MIN_RADIUS = 2;
  private static final int MAX_VIEW_DISTANCE = 32;

  private final Map<Long, Long> chunkHashes = new HashMap<>();
  private long lastRecipesHash;
  private boolean hasRecipesHash;
  private long lastTagsHash;
  private boolean hasTagsHash;
  private int viewDistance = MAX_VIEW_DISTANCE;
  private int centerX;
  private int centerZ;
  private boolean hasCenter;

  /**
   * Records an outbound chunk; returns true when it is a byte-identical duplicate to drop.
   */
  public boolean onChunkData(final long chunkKey, final long contentHash) {
    final Long previous = chunkHashes.put(chunkKey, contentHash);
    return previous != null && previous == contentHash;
  }

  public void onUnloadChunk(final long chunkKey) {
    chunkHashes.remove(chunkKey);
  }

  /**
   * Records an outbound recipes payload; returns true when it duplicates the client's copy.
   */
  public boolean onRecipes(final long contentHash) {
    final boolean duplicate = hasRecipesHash && lastRecipesHash == contentHash;
    lastRecipesHash = contentHash;
    hasRecipesHash = true;
    return duplicate;
  }

  /**
   * Records an outbound tags payload; returns true when it duplicates the client's copy.
   */
  public boolean onTags(final long contentHash) {
    final boolean duplicate = hasTagsHash && lastTagsHash == contentHash;
    lastTagsHash = contentHash;
    hasTagsHash = true;
    return duplicate;
  }

  /**
   * Records the client's view distance and drops chunks its shrunken storage no longer holds.
   */
  public void onViewDistance(final int viewDistance) {
    this.viewDistance = viewDistance;
    trimToClientStorage();
  }

  /**
   * Records the client's chunk-cache center and forgets chunks it silently discarded.
   */
  public void onChunkCenter(final int chunkX, final int chunkZ) {
    centerX = chunkX;
    centerZ = chunkZ;
    hasCenter = true;
    trimToClientStorage();
  }

  private void trimToClientStorage() {
    if (!hasCenter || chunkHashes.isEmpty()) {
      return;
    }
    final int keepRange = Math.max(CLIENT_STORAGE_MIN_RADIUS, viewDistance)
        + CLIENT_STORAGE_MARGIN;
    chunkHashes.keySet().removeIf(key -> {
      final int x = (int) (key >> 32);
      final int z = (int) (long) key;
      return Math.abs(x - centerX) > keepRange || Math.abs(z - centerZ) > keepRange;
    });
  }

  /**
   * Clears everything after a world reset (a forwarded JoinGame); it must all be re-sent.
   */
  public void reset() {
    chunkHashes.clear();
    hasRecipesHash = false;
    hasTagsHash = false;
    viewDistance = MAX_VIEW_DISTANCE;
    hasCenter = false;
  }
}
