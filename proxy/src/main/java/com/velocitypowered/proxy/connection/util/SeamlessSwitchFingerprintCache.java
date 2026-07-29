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

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Remembers the last configuration-phase fingerprint each backend server produced per protocol
 * version. Used to skip attempting a seamless switch towards servers already known to serve
 * different configuration data. Entries are only an optimization hint: a stale entry either causes
 * one unnecessary fallback reconfiguration or one aborted seamless attempt, never an unsafe
 * switch, and is refreshed whenever any configuration session towards that server completes.
 */
public final class SeamlessSwitchFingerprintCache {

  private record CacheKey(String serverName, ProtocolVersion protocolVersion) {
  }

  private final Map<CacheKey, SeamlessSwitchFingerprint> fingerprints = new ConcurrentHashMap<>();

  public @Nullable SeamlessSwitchFingerprint get(final String serverName,
      final ProtocolVersion protocolVersion) {
    return fingerprints.get(new CacheKey(serverName, protocolVersion));
  }

  public void put(final String serverName, final SeamlessSwitchFingerprint fingerprint) {
    fingerprints.put(new CacheKey(serverName, fingerprint.getProtocolVersion()), fingerprint);
  }
}
