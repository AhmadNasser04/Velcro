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

package com.velocitypowered.proxy.protocol.tracking;

/**
 * The clientbound PLAY packet IDs of one packet-ID generation that carry the client-visible state
 * a seamless server switch must track and reconcile: the entities and potion effects the client
 * knows (removed explicitly when the switch skips the world reset) and the chunks, recipes and
 * tags it holds (so byte-identical re-sends can be dropped).
 *
 * @param addEntityId the add-entity packet ID
 * @param addExperienceOrbId the add-experience-orb packet ID, or -1 for versions where orbs
 *     spawn through add-entity instead (1.21.5+)
 * @param removeEntitiesId the remove-entities packet ID
 * @param entityEffectId the entity-effect packet ID
 * @param removeEntityEffectId the remove-entity-effect packet ID
 * @param chunkDataId the level-chunk-with-light packet ID
 * @param unloadChunkId the forget-level-chunk packet ID
 * @param setChunkCenterId the set-chunk-cache-center packet ID
 * @param setChunkRadiusId the set-chunk-cache-radius packet ID
 * @param updateRecipesId the update-recipes packet ID
 * @param updateTagsId the PLAY-state update-tags packet ID
 * @param setObjectiveId the update-objectives packet ID
 * @param setPlayerTeamId the update-teams packet ID
 * @param playerPositionId the player-position (synchronize position) packet ID
 * @param chatSessionUpdateId the serverbound chat-session-update packet ID
 */
public record TrackedPackets(
    int addEntityId,
    int addExperienceOrbId,
    int removeEntitiesId,
    int entityEffectId,
    int removeEntityEffectId,
    int chunkDataId,
    int unloadChunkId,
    int setChunkCenterId,
    int setChunkRadiusId,
    int updateRecipesId,
    int updateTagsId,
    int setObjectiveId,
    int setPlayerTeamId,
    int playerPositionId,
    int chatSessionUpdateId
) {
}
