package de.westnordost.streetcomplete.data.osm.edits.upload

import android.util.Log
import de.westnordost.streetcomplete.data.osm.edits.ElementEdit
import de.westnordost.streetcomplete.data.osm.edits.ElementEditsController
import de.westnordost.streetcomplete.data.osm.edits.ElementIdProvider
import de.westnordost.streetcomplete.data.osm.edits.IsRevertAction
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
import de.westnordost.streetcomplete.data.osm.mapdata.MapData
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataApi
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataController
import de.westnordost.streetcomplete.data.osm.mapdata.MutableMapData
import de.westnordost.streetcomplete.data.osmnotes.edits.NoteEditsController
import de.westnordost.streetcomplete.data.upload.ConflictException
import de.westnordost.streetcomplete.data.upload.OnUploadedChangeListener
import de.westnordost.streetcomplete.data.user.statistics.StatisticsController
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ElementEditsUploader(
    private val elementEditsController: ElementEditsController,
    private val noteEditsController: NoteEditsController,
    private val mapDataController: MapDataController,
    private val singleUploader: ElementEditUploader,
    private val mapDataApi: MapDataApi,
    private val statisticsController: StatisticsController
) {
    var uploadedChangeListener: OnUploadedChangeListener? = null

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("ElementEditsUploader"))

    suspend fun upload() = mutex.withLock { withContext(Dispatchers.IO) {
        while (true) {
            val edit = elementEditsController.getOldestUnsynced() ?: break
            val getIdProvider: () -> ElementIdProvider = { elementEditsController.getIdProvider(edit.id) }
            /* the sync of local change -> API and its response should not be cancellable because
             * otherwise an inconsistency in the data would occur. E.g. no "star" for an uploaded
             * change, a change could be uploaded twice etc */
            withContext(scope.coroutineContext) { uploadEdit(edit, getIdProvider) }
        }
    } }

    private suspend fun uploadEdit(edit: ElementEdit, getIdProvider: () -> ElementIdProvider) {
        val editActionClassName = edit.action::class.simpleName!!

        try {
            val updates = singleUploader.upload(edit, getIdProvider)

            Log.d(TAG, "Uploaded a $editActionClassName")
            uploadedChangeListener?.onUploaded(edit.type.name, edit.position)

            elementEditsController.markSynced(edit)
            mapDataController.updateAll(updates)
            noteEditsController.updateElementIds(updates.idUpdates)

            if (edit.action is IsRevertAction) {
                statisticsController.subtractOne(edit.type.name, edit.position)
            } else {
                statisticsController.addOne(edit.type.name, edit.position)
            }
        } catch (e: ConflictException) {
            Log.d(TAG, "Dropped a $editActionClassName: ${e.message}")
            uploadedChangeListener?.onDiscarded(edit.type.name, edit.position)

            elementEditsController.markSyncFailed(edit)
        }
    }

    companion object {
        private const val TAG = "ElementEditsUploader"
    }
}
