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

import net.kyori.adventure.key.Key;

/**
 * Cookie contract with the companion backend plugin: backends request this during their config
 * phase and the proxy answers with the player's proxy-owned entity id, which the plugin pins
 * on the player entity. The client is never involved.
 */
public final class NetworkEntityIdCookie {
  public static final Key KEY = Key.key("velcro", "entity_id");

  private NetworkEntityIdCookie() {
    throw new AssertionError();
  }

  /**
   * Encodes an entity ID as the 4-byte big-endian cookie payload.
   */
  public static byte[] encode(final int entityId) {
    return new byte[] {
        (byte) (entityId >> 24),
        (byte) (entityId >> 16),
        (byte) (entityId >> 8),
        (byte) entityId,
    };
  }
}
