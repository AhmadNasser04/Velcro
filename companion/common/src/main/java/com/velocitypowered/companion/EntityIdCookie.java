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

package com.velocitypowered.companion;

import io.papermc.paper.connection.ReadablePlayerCookieConnection;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

public final class EntityIdCookie {
    public static final NamespacedKey KEY = new NamespacedKey("velcro", "entity_id");
    private static final int PAYLOAD_LENGTH = 4;
    private static final long TIMEOUT_SECONDS = 2;

    private EntityIdCookie() {}

    public static OptionalInt retrieve(final ReadablePlayerCookieConnection connection, final UUID playerId, final Logger logger) {
        try {
            final byte[] payload = connection.retrieveCookie(KEY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (payload.length != PAYLOAD_LENGTH) {
                logger.warn(
                    "Ignoring malformed {} cookie for {} ({} bytes, expected {})",
                    KEY, playerId, payload.length, PAYLOAD_LENGTH
                );
                return OptionalInt.empty();
            }
            return OptionalInt.of(decode(payload));
        } catch (final TimeoutException e) {
            logger.warn(
                "Timed out retrieving the {} cookie for {}; their server switches will not be seamless",
                KEY, playerId
            );
        } catch (final ExecutionException e) {
            logger.warn("Failed to retrieve the {} cookie for {}", KEY, playerId, e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return OptionalInt.empty();
    }

    private static int decode(final byte[] payload) {
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
    }
}
