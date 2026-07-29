/*
 * Copyright (C) 2019-2023 Velocity Contributors
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

package com.velocitypowered.proxy.connection.backend;

import static com.velocitypowered.proxy.connection.backend.BackendConnectionPhases.IN_TRANSITION;
import static com.velocitypowered.proxy.connection.forge.legacy.LegacyForgeHandshakeBackendPhase.HELLO;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.connection.util.SeamlessSwitchAbortedException;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.BundleDelimiterPacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A special session handler that catches "last minute" disconnects.
 */
public class TransitionSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(TransitionSessionHandler.class);

  private static final int SEAMLESS_JOIN_TIMEOUT_SECONDS = 10;

  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final CompletableFuture<Impl> resultFuture;
  private final BungeeCordMessageResponder bungeecordMessageResponder;
  private final List<Object> queuedPackets = new ArrayList<>();
  private boolean joinGameReceived = false;
  private @Nullable ScheduledFuture<?> joinGameTimeout;

  /**
   * Creates the new transition handler.
   *
   * @param server       the Velocity server instance
   * @param serverConn   the server connection
   * @param resultFuture the result future
   */
  TransitionSessionHandler(VelocityServer server,
      VelocityServerConnection serverConn,
      CompletableFuture<Impl> resultFuture) {
    this.server = server;
    this.serverConn = serverConn;
    this.resultFuture = resultFuture;
    this.bungeecordMessageResponder = new BungeeCordMessageResponder(server,
        serverConn.getPlayer());
  }

  @Override
  public void activated() {
    if (serverConn.isSeamlessJoin()) {
      // The client saw nothing of a seamless switch yet, so a backend that never sends its
      // JoinGame must not wedge the connection request; retry with a normal reconfiguration.
      joinGameTimeout = serverConn.ensureConnected().eventLoop().schedule(() -> {
        if (!joinGameReceived) {
          logger.debug("Timed out waiting for JoinGame from {} for {} on a seamless switch",
              serverConn.getServerInfo().getName(), serverConn.getPlayer().getUsername());
          resultFuture.completeExceptionally(
              new SeamlessSwitchAbortedException("timed out waiting for JoinGame"));
          serverConn.disconnect();
        }
      }, SEAMLESS_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  @Override
  public boolean beforeHandle() {
    if (!serverConn.isActive()) {
      // Obsolete connection
      serverConn.disconnect();
      return true;
    }
    return false;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(JoinGamePacket packet) {
    final MinecraftConnection smc = serverConn.ensureConnected();
    final RegisteredServer previousServer = serverConn.getPreviousServer().orElse(null);
    final ConnectedPlayer player = serverConn.getPlayer();
    final VelocityServerConnection existingConnection = player.getConnectedServer();

    joinGameReceived = true;
    cancelJoinGameTimeout();
    logger.debug("Received JoinGame from {} for {} (seamless join: {})",
        serverConn.getServerInfo().getName(), player.getUsername(), serverConn.isSeamlessJoin());

    if (existingConnection != null) {
      // Shut down the existing server connection.
      player.setConnectedServer(null);
      existingConnection.disconnect();

      // Send keep alive to try to avoid timeouts
      player.sendKeepAlive();

      // The old server can no longer close a bundle it left open; close it before the client
      // receives the JoinGame/Respawn sequence.
      if (player.getBundleHandler().isInBundleSession()) {
        player.getBundleHandler().toggleBundleSession();
        player.getConnection().write(BundleDelimiterPacket.INSTANCE);
      }
    }

    if (serverConn.isSeamlessJoin()) {
      // The client never re-entered the configuration state, so reset the chat state here; the
      // client resets its own 'last seen' state when it processes the JoinGame packet.
      player.discardChatQueue();
    }

    // Reset Tablist header and footer to prevent desync
    player.clearPlayerListHeaderAndFooter();

    // Override online mode
    packet.setOnlineMode(player.isOnlineMode());

    // The goods are in hand! We got JoinGame. Let's transition completely to the new state.
    smc.setAutoReading(false);
    server.getEventManager()
        .fire(new ServerConnectedEvent(player, serverConn.getServer(), previousServer))
        .thenRunAsync(() -> {
          // Make sure we can still transition (player might have disconnected here).
          if (!serverConn.isActive()) {
            // Connection is obsolete. Complete the result so the connection request never hangs.
            serverConn.disconnect();
            resultFuture.complete(ConnectionRequestResults.forDisconnect(
                ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, serverConn.getServer()));
            return;
          }

          // Change the client to use the ClientPlaySessionHandler if required.
          ClientPlaySessionHandler playHandler;
          if (player.getConnection()
              .getActiveSessionHandler() instanceof ClientPlaySessionHandler sessionHandler) {
            playHandler = sessionHandler;
          } else {
            playHandler = new ClientPlaySessionHandler(server, player);
            player.getConnection().setActiveSessionHandler(StateRegistry.PLAY, playHandler);
          }
          assert playHandler != null;
          playHandler.handleBackendJoinGame(packet, serverConn);

          // Set the new play session handler for the server. We will have nothing more to do
          // with this connection once this task finishes up.
          final BackendPlaySessionHandler backendHandler = new BackendPlaySessionHandler(server, serverConn);
          smc.setActiveSessionHandler(StateRegistry.PLAY, backendHandler);

          // Now set the connected server.
          serverConn.getPlayer().setConnectedServer(serverConn);

          // Packets that arrived in the same read batch as the JoinGame (common on low-latency
          // links) were queued instead of dropped; run them through the new handler in order
          // before resuming reads.
          replayQueuedPackets(backendHandler);

          // Clean up disabling auto-read while the connected event was being processed.
          // Do this after setting the connection, so no incoming packets are processed before
          // the API knows which server the player is connected to.
          smc.setAutoReading(true);

          // Send client settings. In 1.20.2+ this is done in the config state.
          if (smc.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_2)
              && player.getClientSettingsPacket() != null) {
            serverConn.ensureConnected().write(player.getClientSettingsPacket());
          }

          // We're done! :)
          server.getEventManager().fireAndForget(new ServerPostConnectEvent(player,
              previousServer));
          resultFuture.complete(ConnectionRequestResults.successful(serverConn.getServer()));
        }, smc.eventLoop()).exceptionally(exc -> {
          logger.error("Unable to switch to new server {} for {}",
              serverConn.getServerInfo().getName(),
              player.getUsername(), exc);
          releaseQueuedPackets();
          player.disconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR);
          resultFuture.completeExceptionally(exc);
          return null;
        });

    return true;
  }

  @Override
  public boolean handle(DisconnectPacket packet) {
    final MinecraftConnection connection = serverConn.ensureConnected();
    serverConn.disconnect();

    // If we were in the middle of the Forge handshake, it is not safe to proceed. We must kick
    // the client.
    if (connection.getType() == ConnectionTypes.LEGACY_FORGE
        && !serverConn.getPhase().consideredComplete()) {
      resultFuture.complete(ConnectionRequestResults.forUnsafeDisconnect(packet,
          serverConn.getServer()));
    } else {
      resultFuture.complete(ConnectionRequestResults.forDisconnect(packet, serverConn.getServer()));
    }

    rescueFromLimbo();
    return true;
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    if (bungeecordMessageResponder.process(packet)) {
      return true;
    }

    // We always need to handle plugin messages, for Forge compatibility.
    if (serverConn.getPhase().handle(serverConn, serverConn.getPlayer(), packet)) {
      // Handled, but check the server connection phase.
      if (serverConn.getPhase() == HELLO) {
        VelocityServerConnection existingConnection = serverConn.getPlayer().getConnectedServer();
        if (existingConnection != null && existingConnection.getPhase() != IN_TRANSITION) {
          // Indicate that this connection is "in transition"
          existingConnection.setConnectionPhase(IN_TRANSITION);

          // Tell the player that we're leaving and we just aren't coming back.
          existingConnection.getPhase().onDepartForNewServer(existingConnection,
              serverConn.getPlayer());
        }
      }
      return true;
    }

    serverConn.getPlayer().getConnection().write(packet.retain());
    return true;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    if (joinGameReceived) {
      ReferenceCountUtil.retain(packet);
      queuedPackets.add(packet);
    }
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    if (joinGameReceived) {
      queuedPackets.add(buf.retain());
    }
  }

  @Override
  public void disconnected() {
    cancelJoinGameTimeout();
    releaseQueuedPackets();
    resultFuture.complete(ConnectionRequestResults.forDisconnect(
        ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, serverConn.getServer()));
    rescueFromLimbo();
  }

  private void rescueFromLimbo() {
    final ConnectedPlayer player = serverConn.getPlayer();
    if (joinGameReceived && player.getConnectedServer() == null
        && player.getConnection().getChannel().isActive()) {
      logger.debug("Backend {} died mid-transition for {}; running kick handling",
          serverConn.getServerInfo().getName(), player.getUsername());
      player.handleConnectionException(
          serverConn.getServer(),
          DisconnectPacket.create(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR,
              player.getProtocolVersion(), player.getConnection().getState()),
          true
      );
    }
  }

  private void cancelJoinGameTimeout() {
    if (joinGameTimeout != null) {
      joinGameTimeout.cancel(false);
      joinGameTimeout = null;
    }
  }

  private void replayQueuedPackets(MinecraftSessionHandler handler) {
    for (final Object msg : queuedPackets) {
      try {
        if (msg instanceof MinecraftPacket packet) {
          if (!packet.handle(handler)) {
            handler.handleGeneric(packet);
          }
        } else if (msg instanceof ByteBuf buf) {
          handler.handleUnknown(buf);
        }
      } finally {
        ReferenceCountUtil.release(msg);
      }
    }
    queuedPackets.clear();
  }

  private void releaseQueuedPackets() {
    for (final Object msg : queuedPackets) {
      ReferenceCountUtil.release(msg);
    }
    queuedPackets.clear();
  }
}
