/*
 * Copyright (C) 2024 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.event.player.configuration;

import com.velocitypowered.api.network.ProtocolState;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import org.jetbrains.annotations.NotNull;

/**
 * This event is executed when a player has finished the configuration state.
 * <p>From this moment on, the {@link Player#getProtocolState()} method
 * will return {@link ProtocolState#PLAY}.</p>
 * <p>This event is not executed for server switches that skip the client's configuration phase
 * (see the {@code seamless-server-switches} option in the proxy configuration).</p>
 *
 * @param player The player who has finished the configuration state.
 * @param server The server that has (re-)configured the player.
 * @since 3.3.0
 * @sinceMinecraft 1.20.2
 */
public record PlayerFinishedConfigurationEvent(@NotNull Player player, @NotNull ServerConnection server) {
}
