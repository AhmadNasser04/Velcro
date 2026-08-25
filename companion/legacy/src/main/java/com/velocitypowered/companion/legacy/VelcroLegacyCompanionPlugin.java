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

import com.velocitypowered.companion.VelcroCompanionPlugin;

public final class VelcroLegacyCompanionPlugin extends VelcroCompanionPlugin {
    @Override
    public void onEnable() {
        if (hasPluginDefinedEntityIdApi()) {
            getSLF4JLogger().warn(
                "This Paper build supports PaperPlayerConfigurationConnection#setInternalPluginDefinedEntityId; "
                    + "prefer the velcro-companion-modern jar."
            );
        }
        super.onEnable();
        getServer().getPluginManager().registerEvents(new NetworkEntityIdListener(this), this);
    }
}
