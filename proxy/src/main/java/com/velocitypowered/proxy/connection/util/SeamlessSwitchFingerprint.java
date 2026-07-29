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

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.EndBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.ShortBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A digest of the configuration-phase payload a client received from a backend server: the
 * synchronized registries, tags, enabled feature flags and the known-packs query. Registries and
 * feature flags can only be delivered in the CONFIGURATION state, so a server switch may only skip
 * re-entering that state when the target backend's fingerprint matches what the client already
 * holds.
 */
public final class SeamlessSwitchFingerprint {

  private final ProtocolVersion protocolVersion;
  private final HashCode registriesHash;
  private final HashCode tagsHash;
  private final HashCode featuresHash;
  private final @Nullable List<KnownPacksPacket.KnownPack> knownPacksQuery;

  private SeamlessSwitchFingerprint(final Builder builder) {
    this.protocolVersion = builder.protocolVersion;
    this.registriesHash = builder.registriesHasher.hash();
    this.tagsHash = builder.tagsHasher.hash();
    this.featuresHash = builder.featuresHasher.hash();
    this.knownPacksQuery = builder.knownPacksQuery;
  }

  public ProtocolVersion getProtocolVersion() {
    return protocolVersion;
  }

  public @Nullable List<KnownPacksPacket.KnownPack> getKnownPacksQuery() {
    return knownPacksQuery;
  }

  /**
   * Returns whether the configuration payload described by this fingerprint is identical to the
   * one described by {@code other}, meaning the configuration phase may safely be skipped when
   * switching between the servers that produced them.
   */
  public boolean matches(final SeamlessSwitchFingerprint other) {
    return this.protocolVersion == other.protocolVersion
        && this.registriesHash.equals(other.registriesHash)
        && this.tagsHash.equals(other.tagsHash)
        && this.featuresHash.equals(other.featuresHash)
        && Objects.equals(this.knownPacksQuery, other.knownPacksQuery);
  }

  @Override
  public String toString() {
    return "SeamlessSwitchFingerprint{"
        + "protocolVersion=" + protocolVersion
        + ", registriesHash=" + registriesHash
        + ", tagsHash=" + tagsHash
        + ", featuresHash=" + featuresHash
        + ", knownPacksQuery=" + knownPacksQuery
        + '}';
  }

  public static Builder builder(final ProtocolVersion protocolVersion) {
    return new Builder(protocolVersion);
  }

  /**
   * Accumulates the configuration-phase packets observed during a single configuration session.
   * Packets must be added in the order they arrive on the wire, as registry entry IDs are
   * order-dependent. A builder may only be {@link #build() built} once.
   */
  public static final class Builder {

    private final ProtocolVersion protocolVersion;
    private final Hasher registriesHasher = Hashing.murmur3_128().newHasher();
    private final Hasher tagsHasher = Hashing.murmur3_128().newHasher();
    private final Hasher featuresHasher = Hashing.murmur3_128().newHasher();
    private @Nullable List<KnownPacksPacket.KnownPack> knownPacksQuery;

    private Builder(final ProtocolVersion protocolVersion) {
      this.protocolVersion = protocolVersion;
    }

    /**
     * Adds a registry data packet. The payload is parsed and hashed canonically: entry order is
     * preserved (it defines the numeric registry IDs) while NBT compound keys are sorted, since
     * their serialization order carries no meaning and varies between server instances (most
     * notably in ViaVersion's rewritten registry data for older clients). Unparseable payloads
     * fall back to exact byte hashing. The packet's reader index and reference count are left
     * untouched.
     */
    public void addRegistry(final RegistrySyncPacket packet) {
      final ByteBuf contents = packet.content().duplicate();
      try {
        if (protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
          // One packet per registry: key, then id + optional NBT per entry, in ID order.
          final String registryKey = ProtocolUtils.readString(contents);
          final int entryCount = ProtocolUtils.readVarInt(contents);
          final List<String> entryIds = new ArrayList<>(Math.min(entryCount, 4096));
          final List<@Nullable BinaryTag> entryData = new ArrayList<>(Math.min(entryCount, 4096));
          for (int i = 0; i < entryCount; i++) {
            entryIds.add(ProtocolUtils.readString(contents));
            entryData.add(contents.readBoolean()
                ? ProtocolUtils.readBinaryTag(contents, protocolVersion, BinaryTagIO.reader())
                : null);
          }
          if (contents.isReadable()) {
            throw new IllegalStateException("Trailing bytes in registry payload");
          }
          putString(registriesHasher, registryKey);
          registriesHasher.putInt(entryCount);
          for (int i = 0; i < entryCount; i++) {
            putString(registriesHasher, entryIds.get(i));
            final BinaryTag data = entryData.get(i);
            registriesHasher.putBoolean(data != null);
            if (data != null) {
              putCanonicalTag(registriesHasher, data);
            }
          }
        } else {
          // 1.20.2-1.20.4: the whole payload is a single NBT compound.
          final BinaryTag tag =
              ProtocolUtils.readBinaryTag(contents, protocolVersion, BinaryTagIO.reader());
          if (contents.isReadable()) {
            throw new IllegalStateException("Trailing bytes in registry payload");
          }
          putCanonicalTag(registriesHasher, tag);
        }
      } catch (final Exception e) {
        putByteBuf(registriesHasher, packet.content());
      }
    }

    /**
     * Adds a configuration-phase tags update packet. Tags map names to sets of registry IDs and
     * their serialization order varies between server instances without carrying any meaning, so
     * they are hashed in a canonical (sorted) form.
     */
    public void addTags(final TagsUpdatePacket packet) {
      final Map<String, Map<String, int[]>> tags = packet.getTags();
      tagsHasher.putInt(tags.size());
      for (final Map.Entry<String, Map<String, int[]>> registry
          : new TreeMap<>(tags).entrySet()) {
        putString(tagsHasher, registry.getKey());
        tagsHasher.putInt(registry.getValue().size());
        for (final Map.Entry<String, int[]> tag : new TreeMap<>(registry.getValue()).entrySet()) {
          putString(tagsHasher, tag.getKey());
          final int[] ids = tag.getValue().clone();
          Arrays.sort(ids);
          tagsHasher.putInt(ids.length);
          for (final int id : ids) {
            tagsHasher.putInt(id);
          }
        }
      }
    }

    /**
     * Adds an enabled feature flags packet.
     */
    public void addFeatures(final ActiveFeaturesPacket packet) {
      final Key[] features = packet.getActiveFeatures();
      featuresHasher.putInt(features.length);
      for (final Key feature : features) {
        putString(featuresHasher, feature.asString());
      }
    }

    /**
     * Records the backend's clientbound known-packs query (1.20.5+).
     */
    public void knownPacksQuery(final List<KnownPacksPacket.KnownPack> query) {
      this.knownPacksQuery = List.copyOf(query);
    }

    public SeamlessSwitchFingerprint build() {
      return new SeamlessSwitchFingerprint(this);
    }

    private static void putString(final Hasher hasher, final String value) {
      hasher.putInt(value.length());
      hasher.putUnencodedChars(value);
    }

    /**
     * Hashes an NBT tag with compound keys in sorted order, so that two serializations of the
     * same data (differing only in compound key order) hash identically. List order is
     * preserved, as it is semantically meaningful.
     */
    private static void putCanonicalTag(final Hasher hasher, final BinaryTag tag) {
      hasher.putInt(tag.type().id());
      if (tag instanceof CompoundBinaryTag compound) {
        final List<String> keys = new ArrayList<>(compound.keySet());
        Collections.sort(keys);
        hasher.putInt(keys.size());
        for (final String key : keys) {
          putString(hasher, key);
          putCanonicalTag(hasher, Objects.requireNonNull(compound.get(key)));
        }
      } else if (tag instanceof ListBinaryTag list) {
        hasher.putInt(list.size());
        for (final BinaryTag element : list) {
          putCanonicalTag(hasher, element);
        }
      } else if (tag instanceof StringBinaryTag string) {
        putString(hasher, string.value());
      } else if (tag instanceof ByteBinaryTag byteTag) {
        hasher.putByte(byteTag.value());
      } else if (tag instanceof ShortBinaryTag shortTag) {
        hasher.putShort(shortTag.value());
      } else if (tag instanceof IntBinaryTag intTag) {
        hasher.putInt(intTag.value());
      } else if (tag instanceof LongBinaryTag longTag) {
        hasher.putLong(longTag.value());
      } else if (tag instanceof FloatBinaryTag floatTag) {
        hasher.putFloat(floatTag.value());
      } else if (tag instanceof DoubleBinaryTag doubleTag) {
        hasher.putDouble(doubleTag.value());
      } else if (tag instanceof ByteArrayBinaryTag byteArray) {
        final byte[] value = byteArray.value();
        hasher.putInt(value.length);
        hasher.putBytes(value);
      } else if (tag instanceof IntArrayBinaryTag intArray) {
        final int[] value = intArray.value();
        hasher.putInt(value.length);
        for (final int element : value) {
          hasher.putInt(element);
        }
      } else if (tag instanceof LongArrayBinaryTag longArray) {
        final long[] value = longArray.value();
        hasher.putInt(value.length);
        for (final long element : value) {
          hasher.putLong(element);
        }
      } else if (!(tag instanceof EndBinaryTag)) {
        throw new IllegalStateException("Unknown NBT tag type " + tag.type());
      }
    }

    private static void putByteBuf(final Hasher hasher, final ByteBuf buf) {
      hasher.putInt(buf.readableBytes());
      if (buf.nioBufferCount() > 0) {
        for (final ByteBuffer nioBuffer : buf.nioBuffers()) {
          hasher.putBytes(nioBuffer);
        }
      } else {
        hasher.putBytes(ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes(), false));
      }
    }
  }
}
