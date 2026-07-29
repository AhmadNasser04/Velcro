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

import io.papermc.paper.connection.PaperPlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class NetworkEntityIdListener implements Listener {
    private static final NamespacedKey COOKIE_KEY = new NamespacedKey("velcro", "entity_id");
    private static final int PAYLOAD_LENGTH = 4;
    private static final long COOKIE_TIMEOUT_SECONDS = 2;

    private final VelcroCompanionPlugin plugin;

    NetworkEntityIdListener(final VelcroCompanionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onConnectionConfigure(final AsyncPlayerConnectionConfigureEvent event) {
        final PaperPlayerConfigurationConnection connection = (PaperPlayerConfigurationConnection) event.getConnection();
        final UUID playerId = connection.getProfile().getId();
        if (playerId == null) {
            return;
        }

        try {
            final byte[] payload = connection.retrieveCookie(COOKIE_KEY).get(COOKIE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (payload.length != PAYLOAD_LENGTH) {
                plugin.getSLF4JLogger().warn(
                    "Ignoring malformed {} cookie for {} ({} bytes, expected {})",
                    COOKIE_KEY, playerId, payload.length, PAYLOAD_LENGTH
                );
                return;
            }
            connection.setInternalPluginDefinedEntityId(decode(payload));
        } catch (final TimeoutException e) {
            plugin.getSLF4JLogger().warn(
                "Timed out retrieving the {} cookie for {}; their server switches will not be seamless",
                COOKIE_KEY, playerId
            );
        } catch (final ExecutionException e) {
            plugin.getSLF4JLogger().warn("Failed to retrieve the {} cookie for {}", COOKIE_KEY, playerId, e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int decode(final byte[] payload) {
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
    }
}
