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

package com.velocitypowered.companion.legacy;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.velocitypowered.companion.EntityIdCookie;
import com.velocitypowered.companion.VelcroCompanionPlugin;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Pre-{@code setInternalPluginDefinedEntityId} fallback: the cookie is resolved during the
 * configuration phase, then the ID is forced onto the freshly created ServerPlayer at
 * {@link PlayerSpawnLocationEvent} (LOWEST, so before other plugins observe the entity), which
 * fires before the login packet is sent. PlayerLoginEvent would be earlier still but routes
 * through Paper's legacy-login shim, which disables reenterConfiguration() server-wide. The
 * reflective {@code getHandle().setId(int)} call is safe on every supported version because
 * Paper runtimes are Mojang-mapped since 1.20.5.
 */
public final class NetworkEntityIdListener implements Listener {
    private final VelcroCompanionPlugin plugin;
    private final Map<UUID, Integer> pendingIds = new ConcurrentHashMap<>();

    NetworkEntityIdListener(final VelcroCompanionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onConnectionConfigure(final AsyncPlayerConnectionConfigureEvent event) {
        final PlayerConfigurationConnection connection = event.getConnection();
        final UUID playerId = connection.getProfile().getId();
        if (playerId == null) {
            return;
        }

        EntityIdCookie.retrieve(connection, playerId, plugin.getSLF4JLogger()).ifPresent(id -> pendingIds.put(playerId, id));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(final PlayerSpawnLocationEvent event) {
        final Integer id = pendingIds.remove(event.getPlayer().getUniqueId());
        if (id != null) {
            applyEntityId(event.getPlayer(), id);
        }
    }

    @EventHandler
    public void onConnectionClose(final PlayerConnectionCloseEvent event) {
        pendingIds.remove(event.getPlayerUniqueId());
    }

    private void applyEntityId(final Player player, final int id) {
        try {
            final Object handle = player.getClass().getMethod("getHandle").invoke(player);
            handle.getClass().getMethod("setId", int.class).invoke(handle, id);
        } catch (final ReflectiveOperationException e) {
            plugin.getSLF4JLogger().error(
                "Failed to pin the network entity ID for {}; their server switches will not be seamless",
                player.getUniqueId(), e
            );
        }
    }
}
