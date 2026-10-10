package cn.xiaotao29.HighSpeedIEM.audio

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.*
import org.junit.Test

class RouteModelTest {
    private fun add(model: RouteModel, input: String?, output: String?): Long {
        val id = model.rows.last().id
        model.setSide(id, true, input)
        model.setSide(id, false, output)
        return id
    }

    private fun row(model: RouteModel, id: Long) = model.rows.first { it.id == id }

    @Test fun startsWithSingleBlankRow() {
        val model = RouteModel()
        assertEquals(1, model.rows.size)
        assertTrue(model.rows.single().blank())
        assertEquals(model.rows.single().id, model.compactRow.id)
        assertEquals(0, model.validCount())
    }

    @Test fun compactBuildsPairInEitherSelectionOrder() {
        for (inputFirst in listOf(true, false)) {
            val model = RouteModel()
            val id = model.compactRow.id
            model.setCompactSide(inputFirst, if (inputFirst) "i1" else "o1")
            assertEquals(2, model.rows.size)
            assertEquals(0, model.validCount())
            model.setCompactSide(!inputFirst, if (inputFirst) "o1" else "i1")
            assertEquals(id, model.compactRow.id)
            assertEquals("i1", model.compactRow.input)
            assertEquals("o1", model.compactRow.output)
            assertEquals(1, model.validCount())
        }
    }

    @Test fun eitherFirstSideImmediatelyCreatesInsertionRow() {
        for (input in listOf(true, false)) {
            val model = RouteModel()
            val id = model.rows.first().id
            model.setSide(id, input, "channel")
            assertEquals(2, model.rows.size)
            assertEquals(id, model.rows.first().id)
            assertTrue(model.rows.last().blank())
        }
    }

    @Test fun sameSelectionDoesNotPublishOrAddRows() {
        val model = RouteModel()
        val id = add(model, "i1", null)
        val before = model.rows
        assertFalse(model.setSide(id, true, "i1"))
        assertSame(before, model.rows)
        assertEquals(2, model.rows.size)
    }

    @Test fun previousSnapshotsRemainUnchangedAfterEditsAndRemoval() {
        val model = RouteModel()
        val empty = model.rows
        val id = add(model, "i1", "o1")
        val complete = model.rows
        model.setSide(id, true, null)
        val partial = model.rows
        model.setSide(id, false, null)
        assertTrue(empty.single().blank())
        assertEquals("i1", complete[0].input)
        assertEquals("o1", complete[0].output)
        assertNull(partial[0].input)
        assertEquals("o1", partial[0].output)
        assertTrue(complete[1].blank()) // a cached two-row interval remains safe
        assertEquals(1, model.rows.size)
    }

    @Test fun clearsOnlyPickedSideAndRebindsAfterBothCleared() {
        for (inputFirst in listOf(true, false)) {
            val model = RouteModel()
            val mapped = add(model, "i1", "o1")
            val other = add(model, "i2", "o2")
            val insertion = model.rows.last().id
            model.setCompactSide(inputFirst, null)
            assertEquals(mapped, model.compactRow.id)
            assertEquals(if (inputFirst) null else "i1", model.compactRow.input)
            assertEquals(if (inputFirst) "o1" else null, model.compactRow.output)
            model.setCompactSide(!inputFirst, null)
            assertEquals(insertion, model.compactRow.id)
            assertEquals(other, model.rows.first().id)
            assertFalse(model.rows.any { it.id == mapped })
            model.setCompactSide(false, "o3")
            model.setCompactSide(true, "i3")
            assertEquals(insertion, model.compactRow.id)
            assertEquals(2, model.validCount())
        }
    }

    @Test fun blankPickRetainsOppositePartialSelection() {
        val model = RouteModel()
        model.setCompactSide(true, "i1")
        model.setCompactSide(false, null)
        assertEquals("i1", model.compactRow.input)
        assertEquals(2, model.rows.size)
    }

    @Test fun duplicateCompletePairKeepsFirstIdAndInsertion() {
        val model = RouteModel()
        val first = add(model, "i1", "o1")
        val second = add(model, "i1", "o1")
        assertEquals(1, model.validCount())
        assertEquals(2, model.rows.size)
        assertEquals(first, model.rows.first().id)
        assertFalse(model.rows.any { it.id == second })
    }

    @Test fun editingEarlierRowToDuplicateKeepsEarlierId() {
        val model = RouteModel()
        val first = add(model, "i1", "o1")
        val second = add(model, "i2", "o1")
        model.setSide(first, true, "i2")
        assertEquals(first, model.compactRow.id)
        assertEquals(first, model.rows.first().id)
        assertFalse(model.rows.any { it.id == second })
        assertEquals(1, model.validCount())
    }

    @Test fun duplicateMappedRowRebindsToBlankInsteadOfExistingPair() {
        val model = RouteModel()
        val mapped = add(model, "i1", "o1")
        val survivor = add(model, "i2", "o2")
        model.setSide(mapped, true, null)
        model.setSide(mapped, false, null)
        val conflicting = model.compactRow.id
        model.setCompactSide(false, "o2")
        model.setCompactSide(true, "i2")
        assertNotEquals(conflicting, model.compactRow.id)
        assertTrue(model.compactRow.blank())
        assertEquals(survivor, model.rows.first().id)
        assertEquals(1, model.validCount())
    }

    @Test fun sameInputWithDifferentOutputsCoexists() {
        val model = RouteModel()
        add(model, "i1", "o1")
        add(model, "i1", "o2")
        assertEquals(2, model.validCount())
    }

    @Test fun sameOutputWithDifferentInputsCoexists() {
        val model = RouteModel()
        add(model, "i1", "o1")
        add(model, "i2", "o1")
        assertEquals(2, model.validCount())
    }

    @Test fun partialDuplicatesCoexist() {
        val model = RouteModel()
        add(model, "i1", null)
        add(model, "i1", null)
        assertEquals(3, model.rows.size)
        assertEquals(0, model.validCount())
    }

    @Test fun pruneDropsCompleteAndPartialRowsWithUnavailableEndpoints() {
        val model = RouteModel()
        val mapped = add(model, "i1", "o1")
        val survivor = add(model, "i2", "o2")
        add(model, "i1", null)
        model.prune(setOf("i2"), setOf("o1", "o2"))
        assertFalse(model.rows.any { it.id == mapped })
        assertEquals(survivor, model.rows.first().id)
        assertEquals(model.rows.last().id, model.compactRow.id)
        assertEquals(1, model.validCount())
        assertEquals(2, model.rows.size)
    }

    @Test fun unrelatedPruningPreservesBindingAndIds() {
        val model = RouteModel()
        val mapped = add(model, "i1", "o1")
        add(model, "i2", "o2")
        val insertion = model.rows.last().id
        model.prune(setOf("i1"), setOf("o1"))
        assertEquals(mapped, model.compactRow.id)
        assertEquals(insertion, model.rows.last().id)
    }

    @Test fun emptyEnabledSetsLeaveOnlyInsertionRow() {
        val model = RouteModel()
        add(model, "i1", "o1")
        val insertion = model.rows.last().id
        model.prune(emptySet(), emptySet())
        assertEquals(insertion, model.rows.single().id)
        assertTrue(model.compactRow.blank())
    }

    @Test fun phoneEditsUseSameBindingAndLatestOppositeSide() {
        val model = RouteModel()
        val stale = model.compactRow
        model.setSide(stale.id, true, "i1")
        model.setSide(stale.id, false, "o1")
        model.setSide(stale.id, true, "i2")
        assertEquals(stale.id, model.compactRow.id)
        assertEquals("i2", model.compactRow.input)
        assertEquals("o1", model.compactRow.output)
    }

    @Test fun removedRowCallbacksCannotChangeAnotherRowOrResurrectOldOne() {
        val model = RouteModel()
        val removed = add(model, "i1", "o1")
        val survivor = add(model, "i2", "o2")
        model.setSide(removed, true, null)
        model.setSide(removed, false, null)
        val before = model.rows
        assertFalse(model.setSide(removed, true, "i3"))
        assertSame(before, model.rows)
        assertEquals("i2", row(model, survivor).input)
    }

    @Test fun clearRetainsInsertionAndNeverReusesRemovedIds() {
        val model = RouteModel()
        val old = add(model, "i1", "o1")
        val insertion = model.rows.last().id
        model.clear()
        assertEquals(insertion, model.rows.single().id)
        assertEquals(insertion, model.compactRow.id)
        val next = add(model, "i2", "o2")
        assertNotEquals(old, next)
    }

    @Test fun tableChangesNotifySnapshotReadersWithoutSessionVersion() {
        val model = RouteModel()
        Snapshot.sendApplyNotifications()
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads("table", { _: String -> invalidations++ }) {
                assertEquals(1, model.rows.size)
                assertTrue(model.compactRow.blank())
            }
            model.setCompactSide(true, "i1")
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            assertEquals(2, model.rows.size)
            assertEquals("i1", model.compactRow.input)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test fun repeatedEditsKeepTableInvariantsAndOldSnapshotsReadable() {
        val model = RouteModel()
        repeat(200) {
            val id = add(model, "i1", "o1")
            val old = model.rows
            add(model, "i1", "o1")
            add(model, "i2", "o1")
            model.setSide(id, true, null)
            model.setSide(id, false, null)
            model.prune(emptySet(), emptySet())
            assertTrue(old[1].blank())
            assertTrue(model.rows.single().blank())
            assertEquals(model.rows.single().id, model.compactRow.id)
        }
    }
}
