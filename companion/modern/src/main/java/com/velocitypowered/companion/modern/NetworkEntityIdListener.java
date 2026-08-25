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

package com.velocitypowered.companion.modern;

import com.velocitypowered.companion.EntityIdCookie;
import com.velocitypowered.companion.VelcroCompanionPlugin;

import io.papermc.paper.connection.PaperPlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;

import java.util.UUID;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class NetworkEntityIdListener implements Listener {
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

        EntityIdCookie
            .retrieve(connection, playerId, plugin.getSLF4JLogger())
            .ifPresent(connection::setInternalPluginDefinedEntityId);
    }
}
