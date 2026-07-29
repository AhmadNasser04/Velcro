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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class SeamlessSwitchFingerprintTest {

  private static RegistrySyncPacket registry(final String data) {
    final RegistrySyncPacket packet = new RegistrySyncPacket();
    final ByteBuf buf = Unpooled.copiedBuffer(data, StandardCharsets.UTF_8);
    packet.replace(buf);
    return packet;
  }

  private static TagsUpdatePacket tags(final Map<String, Map<String, int[]>> tags) {
    return new TagsUpdatePacket(tags);
  }

  /**
   * Encodes a 1.20.5+ registry payload: registry key, then entries of id + a compound of string
   * fields written in the given order (order of the outer array = wire order of compound keys).
   */
  private static RegistrySyncPacket registryWithEntries(final String registryKey,
      final Object[][] entries) {
    final ByteBuf buf = Unpooled.buffer();
    ProtocolUtils.writeString(buf, registryKey);
    ProtocolUtils.writeVarInt(buf, entries.length);
    for (final Object[] entry : entries) {
      ProtocolUtils.writeString(buf, (String) entry[0]);
      buf.writeBoolean(true);
      buf.writeByte(10); // compound
      for (int i = 1; i < entry.length; i += 2) {
        buf.writeByte(8); // string tag
        writeShortString(buf, (String) entry[i]);
        writeShortString(buf, (String) entry[i + 1]);
      }
      buf.writeByte(0); // end
    }
    final RegistrySyncPacket packet = new RegistrySyncPacket();
    packet.replace(buf);
    return packet;
  }

  private static void writeShortString(final ByteBuf buf, final String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    buf.writeShort(bytes.length);
    buf.writeBytes(bytes);
  }

  @Test
  void identicalPayloadsMatch() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    for (final SeamlessSwitchFingerprint.Builder builder : new SeamlessSwitchFingerprint.Builder[]{first, second}) {
      builder.addRegistry(registry("minecraft:dimension_type"));
      builder.addRegistry(registry("minecraft:worldgen/biome"));
      builder.addTags(tags(Map.of("minecraft:block", Map.of("minecraft:logs", new int[]{1, 2}))));
      builder.addFeatures(new ActiveFeaturesPacket(new Key[]{Key.key("minecraft:vanilla")}));
    }

    assertTrue(first.build().matches(second.build()));
  }

  @Test
  void registryContentDifferenceMismatches() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addRegistry(registry("minecraft:dimension_type"));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addRegistry(registry("minecraft:dimension_type_modified"));

    assertFalse(first.build().matches(second.build()));
  }

  @Test
  void registryOrderMatters() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addRegistry(registry("aaa"));
    first.addRegistry(registry("bbb"));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addRegistry(registry("bbb"));
    second.addRegistry(registry("aaa"));

    assertFalse(first.build().matches(second.build()));
  }

  @Test
  void registryPacketBoundariesMatter() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addRegistry(registry("aaabbb"));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addRegistry(registry("aaa"));
    second.addRegistry(registry("bbb"));

    assertFalse(first.build().matches(second.build()));
  }

  @Test
  void hashingDoesNotConsumeTheRegistryBuffer() {
    final RegistrySyncPacket packet = registry("minecraft:dimension_type");
    final int readableBefore = packet.content().readableBytes();

    final SeamlessSwitchFingerprint.Builder builder =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    builder.addRegistry(packet);

    assertTrue(packet.content().readableBytes() == readableBefore,
        "hashing must not move the reader index");
  }

  @Test
  void registryCompoundKeyOrderIsIrrelevant() {
    // ViaVersion and other reserializers emit NBT compound keys in unstable order; identical
    // registry content must fingerprint identically regardless.
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addRegistry(registryWithEntries("minecraft:dimension_type",
        new Object[][]{{"minecraft:overworld", "height", "384", "min_y", "-64"}}));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addRegistry(registryWithEntries("minecraft:dimension_type",
        new Object[][]{{"minecraft:overworld", "min_y", "-64", "height", "384"}}));

    assertTrue(first.build().matches(second.build()));
  }

  @Test
  void registryValueDifferenceMismatches() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addRegistry(registryWithEntries("minecraft:dimension_type",
        new Object[][]{{"minecraft:overworld", "height", "384"}}));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addRegistry(registryWithEntries("minecraft:dimension_type",
        new Object[][]{{"minecraft:overworld", "height", "256"}}));

    assertFalse(first.build().matches(second.build()));
  }

  @Test
  void registryEntryOrderStillMatters() {
    // Entry order defines the numeric registry IDs and must remain significant.
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addRegistry(registryWithEntries("minecraft:wolf_variant", new Object[][]{
        {"minecraft:pale", "texture", "a"}, {"minecraft:ashen", "texture", "b"}}));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addRegistry(registryWithEntries("minecraft:wolf_variant", new Object[][]{
        {"minecraft:ashen", "texture", "b"}, {"minecraft:pale", "texture", "a"}}));

    assertFalse(first.build().matches(second.build()));
  }

  @Test
  void tagOrderIsIrrelevant() {
    // Servers serialize tags from hash-keyed maps whose iteration order differs per instance;
    // identical content in a different order must still match.
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addTags(tags(new java.util.LinkedHashMap<>(Map.of(
        "minecraft:block", Map.of("minecraft:logs", new int[]{2, 1}),
        "minecraft:item", Map.of("minecraft:planks", new int[]{5, 4})))));

    final java.util.LinkedHashMap<String, Map<String, int[]>> reordered =
        new java.util.LinkedHashMap<>();
    reordered.put("minecraft:item", Map.of("minecraft:planks", new int[]{4, 5}));
    reordered.put("minecraft:block", Map.of("minecraft:logs", new int[]{1, 2}));
    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addTags(tags(reordered));

    assertTrue(first.build().matches(second.build()));
  }

  @Test
  void tagDifferenceMismatches() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addTags(tags(Map.of("minecraft:block", Map.of("minecraft:logs", new int[]{1, 2}))));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addTags(tags(Map.of("minecraft:block", Map.of("minecraft:logs", new int[]{1, 3}))));

    assertFalse(first.build().matches(second.build()));
  }

  @Test
  void featureDifferenceMismatches() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.addFeatures(new ActiveFeaturesPacket(new Key[]{Key.key("minecraft:vanilla")}));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.addFeatures(new ActiveFeaturesPacket(
        new Key[]{Key.key("minecraft:vanilla"), Key.key("minecraft:trade_rebalance")}));

    assertFalse(first.build().matches(second.build()));
  }

  @Test
  void protocolVersionDifferenceMismatches() {
    final SeamlessSwitchFingerprint first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_20_2).build();
    final SeamlessSwitchFingerprint second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21).build();

    assertFalse(first.matches(second));
  }

  @Test
  void knownPacksQueryDifferenceMismatches() {
    final SeamlessSwitchFingerprint.Builder first =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    first.knownPacksQuery(
        java.util.List.of(new KnownPacksPacket.KnownPack("minecraft", "core", "1.21")));

    final SeamlessSwitchFingerprint.Builder second =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    second.knownPacksQuery(
        java.util.List.of(new KnownPacksPacket.KnownPack("minecraft", "core", "1.21.1")));

    assertFalse(first.build().matches(second.build()));

    final SeamlessSwitchFingerprint.Builder absent =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21);
    assertFalse(absent.build().matches(first.build()));
  }

  @Test
  void cacheIsKeyedByServerAndVersion() {
    final SeamlessSwitchFingerprintCache cache = new SeamlessSwitchFingerprintCache();
    final SeamlessSwitchFingerprint fingerprint =
        SeamlessSwitchFingerprint.builder(ProtocolVersion.MINECRAFT_1_21).build();

    cache.put("lobby", fingerprint);

    assertTrue(cache.get("lobby", ProtocolVersion.MINECRAFT_1_21).matches(fingerprint));
    assertNull(cache.get("lobby", ProtocolVersion.MINECRAFT_1_20_2));
    assertNull(cache.get("survival", ProtocolVersion.MINECRAFT_1_21));
  }
}
