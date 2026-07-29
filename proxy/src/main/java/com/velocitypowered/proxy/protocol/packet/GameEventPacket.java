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

package com.velocitypowered.proxy.protocol.packet;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import io.netty.buffer.ByteBuf;

public class GameEventPacket implements MinecraftPacket {
  public static final int CHANGE_GAME_MODE = 3;
  public static final int LEVEL_CHUNKS_LOAD_START = 13;

  private int event;
  private float value;

  public GameEventPacket() {
  }

  public GameEventPacket(int event, float value) {
    this.event = event;
    this.value = value;
  }

  public int getEvent() {
    return event;
  }

  public float getValue() {
    return value;
  }

  @Override
  public void decode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    this.event = buf.readUnsignedByte();
    this.value = buf.readFloat();
  }

  @Override
  public void encode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    buf.writeByte(event);
    buf.writeFloat(value);
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    return handler.handle(this);
  }

  @Override
  public int encodeSizeHint(Direction direction, ProtocolVersion version) {
    return 5;
  }
}
