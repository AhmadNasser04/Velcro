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

import com.velocitypowered.proxy.util.except.QuietRuntimeException;

/**
 * Thrown when a seamless switch attempt aborts before anything reached the client, so the
 * connection request can be retried with a normal reconfiguration.
 */
public final class SeamlessSwitchAbortedException extends QuietRuntimeException {
  public SeamlessSwitchAbortedException(final String message) {
    super(message);
  }
}
