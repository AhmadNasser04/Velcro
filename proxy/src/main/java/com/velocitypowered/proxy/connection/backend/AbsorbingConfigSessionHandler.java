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

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.event.player.CookieStoreEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.forge.modern.ModernForgeConnectionType;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.connection.util.NetworkEntityIdCookie;
import com.velocitypowered.proxy.connection.util.SeamlessSwitchAbortedException;
import com.velocitypowered.proxy.connection.util.SeamlessSwitchFingerprint;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStoreCookiePacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PingIdentifyPacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.ClientboundCustomReportDetailsPacket;
import com.velocitypowered.proxy.protocol.packet.config.ClientboundServerLinksPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Runs a backend's config phase on the client's behalf, aborting back to a normal switch on
 * any mismatch. Nothing reaches the client before the handoff, so an abort is invisible.
 */
public class AbsorbingConfigSessionHandler implements MinecraftSessionHandler {
  private static final Logger logger = LogManager.getLogger(AbsorbingConfigSessionHandler.class);

  private static final int TIMEOUT_SECONDS = 10;

  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final CompletableFuture<Impl> resultFuture;
  private SeamlessSwitchFingerprint.Builder fingerprintBuilder;
  private boolean finished = false;
  private @Nullable ScheduledFuture<?> timeoutTask;

  AbsorbingConfigSessionHandler(
      final VelocityServer server,
      final VelocityServerConnection serverConn,
      final CompletableFuture<Impl> resultFuture
  ) {
    this.server = server;
    this.serverConn = serverConn;
    this.resultFuture = resultFuture;
  }

  /** Pre-check only; the absorbed phase still verifies the actual data. */
  public static boolean canSwitchSeamlessly(
      final VelocityServer server,
      final VelocityServerConnection serverConn,
      final ConnectedPlayer player
  ) {
    if (!server.getConfiguration().isSeamlessServerSwitches()) {
      return false;
    }
    if (!serverConn.isSeamlessAllowed()) {
      return declined(serverConn, "retrying with a normal reconfiguration");
    }
    if (player.getConnection().getType() == ConnectionTypes.LEGACY_FORGE || player.getConnection().getType() instanceof ModernForgeConnectionType) {
      return declined(serverConn, "modded client");
    }
    final SeamlessSwitchFingerprint applied = player.getAppliedConfigFingerprint();
    if (applied == null || applied.getProtocolVersion() != player.getProtocolVersion()) {
      return declined(serverConn, "no configuration fingerprint for the client");
    }
    if (player.getProtocolVersion() == ProtocolVersion.MINECRAFT_1_20_2
        && player.resourcePackHandler().getFirstAppliedPack() != null) {
      return declined(serverConn, "1.20.2 client with an applied resource pack");
    }
    final SeamlessSwitchFingerprint cached = server.getSeamlessSwitchFingerprintCache()
        .get(serverConn.getServerInfo().getName(), player.getProtocolVersion());
    if (cached != null && !cached.matches(applied)) {
      return declined(serverConn, "server is known to serve different configuration data");
    }
    return true;
  }

  private static boolean declined(final VelocityServerConnection serverConn, final String reason) {
    logger.debug("Not attempting a seamless switch to {} for {}: {}",
        serverConn.getServerInfo().getName(), serverConn.getPlayer().getUsername(), reason);
    return false;
  }

  @Override
  public void activated() {
    logger.debug("Absorbing configuration phase of {} for {}", serverConn.getServerInfo().getName(), serverConn.getPlayer().getUsername());
    fingerprintBuilder =
        SeamlessSwitchFingerprint.builder(serverConn.getPlayer().getProtocolVersion());
    // A seamless attempt must never wedge the connection request; bail out to the normal
    // reconfiguration path if the backend's configuration phase stalls.
    timeoutTask = serverConn.ensureConnected().eventLoop().schedule(() -> {
      if (!finished) {
        abort("timed out waiting for the backend configuration phase");
      }
    }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public boolean beforeHandle() {
    if (!serverConn.isActive()) {
      serverConn.disconnect();
      return true;
    }
    return false;
  }

  @Override
  public boolean handle(RegistrySyncPacket packet) {
    fingerprintBuilder.addRegistry(packet);
    return true;
  }

  @Override
  public boolean handle(TagsUpdatePacket packet) {
    fingerprintBuilder.addTags(packet);
    return true;
  }

  @Override
  public boolean handle(ActiveFeaturesPacket packet) {
    fingerprintBuilder.addFeatures(packet);
    return true;
  }

  @Override
  public boolean handle(KnownPacksPacket packet) {
    final ConnectedPlayer player = serverConn.getPlayer();
    final List<KnownPacksPacket.KnownPack> query = packet.getPacks();
    fingerprintBuilder.knownPacksQuery(query);

    final SeamlessSwitchFingerprint applied = player.getAppliedConfigFingerprint();
    final KnownPacksPacket clientResponse = player.getClientKnownPacksResponse();
    if (applied == null || clientResponse == null || !Objects.equals(query, applied.getKnownPacksQuery())) {
      return abort("known packs query differs from the client's last negotiation");
    }
    serverConn.ensureConnected().write(clientResponse);
    return true;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(PingIdentifyPacket packet) {
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    if (PluginMessageUtil.isMcBrand(packet)) {
      serverConn.getPlayer().getConnection().write(
          PluginMessageUtil.rewriteMinecraftBrand(packet, server.getVersion(),
              serverConn.getPlayer().getProtocolVersion()));
      return true;
    }
    return abort("unexpected plugin message on channel " + packet.getChannel());
  }

  @Override
  public boolean handle(ClientboundCookieRequestPacket packet) {
    if (NetworkEntityIdCookie.KEY.equals(packet.getKey())) {
      serverConn.ensureConnected().write(new ServerboundCookieResponsePacket(
          NetworkEntityIdCookie.KEY,
          NetworkEntityIdCookie.encode(serverConn.getPlayer().getNetworkEntityId())));
      return true;
    }
    return abort("cookie request for " + packet.getKey() + " needs a client response");
  }

  @Override
  public boolean handle(ClientboundStoreCookiePacket packet) {
    server.getEventManager()
        .fire(new CookieStoreEvent(serverConn.getPlayer(), packet.getKey(), packet.getPayload()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();
            final byte[] resultedData = event.getResult().getData() == null
                ? event.getOriginalData() : event.getResult().getData();

            serverConn.getPlayer().getConnection()
                .write(new ClientboundStoreCookiePacket(resultedKey, resultedData));
          }
        }, serverConn.ensureConnected().eventLoop());
    return true;
  }

  @Override
  public boolean handle(ClientboundCustomReportDetailsPacket packet) {
    serverConn.getPlayer().getConnection().write(packet);
    return true;
  }

  @Override
  public boolean handle(ClientboundServerLinksPacket packet) {
    serverConn.getPlayer().getConnection().write(packet);
    return true;
  }

  @Override
  public boolean handle(FinishedUpdatePacket packet) {
    final SeamlessSwitchFingerprint fingerprint = fingerprintBuilder.build();
    server.getSeamlessSwitchFingerprintCache()
        .put(serverConn.getServerInfo().getName(), fingerprint);

    final SeamlessSwitchFingerprint applied = serverConn.getPlayer().getAppliedConfigFingerprint();
    if (applied == null || !fingerprint.matches(applied)) {
      if (logger.isDebugEnabled()) {
        logger.debug("Seamless switch to {} for {} not possible: server={}, client={}",
            serverConn.getServerInfo().getName(), serverConn.getPlayer().getUsername(),
            fingerprint, applied);
      }
      return abort("configuration data differs from the client's");
    }

    commit();
    return true;
  }

  @Override
  public boolean handle(DisconnectPacket packet) {
    serverConn.disconnect();
    resultFuture.complete(ConnectionRequestResults.forDisconnect(packet, serverConn.getServer()));
    return true;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    abort("unexpected configuration packet " + packet.getClass().getSimpleName());
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    abort("unknown configuration packet");
  }

  @Override
  public void disconnected() {
    cancelTimeout();
    resultFuture.complete(ConnectionRequestResults.forDisconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, serverConn.getServer()));
  }

  private void commit() {
    finished = true;
    cancelTimeout();
    final MinecraftConnection smc = serverConn.ensureConnected();
    final ConnectedPlayer player = serverConn.getPlayer();

    logger.debug("Seamless switch to {} for {}: configuration data matches, skipping the "
        + "client's configuration phase", serverConn.getServerInfo().getName(),
        player.getUsername());
    serverConn.setSeamlessJoin(true);

    final String brand = player.getClientBrand();
    if (brand != null) {
      final ByteBuf buf = Unpooled.buffer();
      ProtocolUtils.writeString(buf, brand);
      smc.write(new PluginMessagePacket("minecraft:brand", buf));
    }

    smc.getChannel().pipeline().get(MinecraftVarintFrameDecoder.class).setState(StateRegistry.PLAY);
    smc.getChannel().pipeline().get(MinecraftDecoder.class).setState(StateRegistry.PLAY);
    smc.write(FinishedUpdatePacket.INSTANCE);
    smc.setActiveSessionHandler(StateRegistry.PLAY, new TransitionSessionHandler(server, serverConn, resultFuture));
    smc.addSessionHandler(StateRegistry.CONFIG, new ConfigSessionHandler(server, serverConn, resultFuture));
  }

  private boolean abort(final String reason) {
    if (!finished) {
      finished = true;
      cancelTimeout();
      logger.debug("Aborting seamless switch to {} for {}: {}",
          serverConn.getServerInfo().getName(), serverConn.getPlayer().getUsername(), reason);
      resultFuture.completeExceptionally(new SeamlessSwitchAbortedException(reason));
      serverConn.disconnect();
    }
    return true;
  }

  private void cancelTimeout() {
    if (timeoutTask != null) {
      timeoutTask.cancel(false);
      timeoutTask = null;
    }
  }
}
