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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeamlessWorldTrackerTest {

  @Test
  void identicalChunkResendIsDropped() {
    final SeamlessWorldTracker tracker = new SeamlessWorldTracker();
    assertFalse(tracker.onChunkData(1L, 100L), "first send must pass");
    assertTrue(tracker.onChunkData(1L, 100L), "identical re-send must be dropped");
  }

  @Test
  void changedChunkContentIsForwarded() {
    final SeamlessWorldTracker tracker = new SeamlessWorldTracker();
    tracker.onChunkData(1L, 100L);
    assertFalse(tracker.onChunkData(1L, 200L),
        "differing content at the same position must be forwarded");
    assertTrue(tracker.onChunkData(1L, 200L), "and the new content becomes the baseline");
  }

  @Test
  void unloadForgetsTheChunk() {
    final SeamlessWorldTracker tracker = new SeamlessWorldTracker();
    tracker.onChunkData(1L, 100L);
    tracker.onUnloadChunk(1L);
    assertFalse(tracker.onChunkData(1L, 100L), "a re-send after unload must be forwarded");
  }

  @Test
  void resetForgetsEverything() {
    final SeamlessWorldTracker tracker = new SeamlessWorldTracker();
    tracker.onChunkData(1L, 100L);
    tracker.onRecipes(50L);
    tracker.reset();
    assertFalse(tracker.onChunkData(1L, 100L));
    assertFalse(tracker.onRecipes(50L));
  }

  @Test
  void centerMoveForgetsChunksOutsideClientStorage() {
    final SeamlessWorldTracker tracker = new SeamlessWorldTracker();
    tracker.onViewDistance(8);
    tracker.onChunkCenter(0, 0);
    tracker.onChunkData(chunkKey(0, 0), 100L);
    tracker.onChunkData(chunkKey(10, 0), 200L);

    // The client keeps chunks within max(2, view) + 3 = 11 of the new center and silently drops
    // the rest; a center far away must forget everything previously tracked.
    tracker.onChunkCenter(1000, 1000);
    assertFalse(tracker.onChunkData(chunkKey(0, 0), 100L),
        "chunk dropped by the client's storage move must be re-sendable");

    // Chunks within range of an unchanged center stay deduplicated.
    tracker.onChunkCenter(1000, 1000);
    tracker.onChunkData(chunkKey(1000, 1000), 300L);
    tracker.onChunkCenter(1001, 1000);
    assertTrue(tracker.onChunkData(chunkKey(1000, 1000), 300L),
        "chunks still in client storage after a small center move stay deduplicated");
  }

  @Test
  void viewDistanceShrinkForgetsChunksOutsideClientStorage() {
    final SeamlessWorldTracker tracker = new SeamlessWorldTracker();
    tracker.onChunkCenter(0, 0);
    tracker.onViewDistance(16);
    tracker.onChunkData(chunkKey(12, 0), 100L);
    tracker.onViewDistance(2);
    assertFalse(tracker.onChunkData(chunkKey(12, 0), 100L),
        "chunk outside the shrunken storage must be re-sendable");
  }

  private static long chunkKey(final int x, final int z) {
    return ((long) x << 32) | (z & 0xFFFFFFFFL);
  }

  @Test
  void bulkPayloadsDedupByContent() {
    final SeamlessWorldTracker tracker = new SeamlessWorldTracker();
    assertFalse(tracker.onRecipes(50L));
    assertTrue(tracker.onRecipes(50L));
    assertFalse(tracker.onRecipes(51L), "changed recipes must be forwarded");
    assertFalse(tracker.onTags(70L));
    assertTrue(tracker.onTags(70L));
  }
}
