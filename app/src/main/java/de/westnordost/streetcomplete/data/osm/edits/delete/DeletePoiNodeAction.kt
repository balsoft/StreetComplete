package de.westnordost.streetcomplete.data.osm.edits.delete

import de.westnordost.streetcomplete.data.osm.edits.ElementEditAction
import de.westnordost.streetcomplete.data.osm.edits.ElementIdProvider
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataRepository
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.upload.ConflictException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Action that deletes a POI node.
 *
 *  This is different from a generic element deletion seen in other editors in as ...
 *
 *  1. it only works on nodes. This is mainly to reduce complexity, because when deleting ways, it
 *     is expected to implicitly also delete all nodes of that way that are not part of any other
 *     way (or relation).
 *
 *  2. if that node is a vertex in a way or has a role in a relation, the node is not deleted but
 *     just "degraded" to be a vertex, i.e. the tags are cleared.
 *
 *  The original node version is passed because if the node changed in the meantime, it should be
 *  considered as a conflict. For example,
 *  the node may have been moved to the real location of the POI, the tagging may have been
 *  corrected to reflect what the POI really is, it may have been re-purposed to be something
 *  else now, etc.
 *  */
@Serializable
object DeletePoiNodeAction : ElementEditAction {

    override fun createUpdates(
        originalElement: Element,
        element: Element,
        mapDataRepository: MapDataRepository,
        idProvider: ElementIdProvider
    ): Collection<Element> {
        var node = element as Node

        if (node.version > originalElement.version) throw ConflictException()

        // delete free-floating node
        if (mapDataRepository.getWaysForNode(node.id).isEmpty() &&
            mapDataRepository.getRelationsForNode(node.id).isEmpty()) {
            node = node.copy().apply { isDeleted = true }
        }
        // if it is a vertex in a way or has a role in a relation: just clear the tags then
        else {
            node = node.copy(tags = emptyMap())
        }

        return listOf(node)
    }
}
