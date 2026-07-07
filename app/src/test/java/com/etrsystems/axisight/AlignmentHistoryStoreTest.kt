package com.etrsystems.axisight

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Unit tests for [AlignmentHistoryStore]'s append-only CSV round trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AlignmentHistoryStoreTest {

    private fun newStore(): AlignmentHistoryStore {
        val store = AlignmentHistoryStore(ApplicationProvider.getApplicationContext())
        // Each test gets a clean slate — Robolectric reuses the app sandbox across tests.
        store.readAll().let { if (it.isNotEmpty()) File(store.exportPath()).delete() }
        return store
    }

    @Test
    fun `readAll on empty store returns empty list`() {
        val store = newStore()
        assertTrue(store.readAll().isEmpty())
    }

    @Test
    fun `append then readAll round-trips a record`() {
        val store = newStore()
        val record = AlignmentRecord(
            timestampMs = 1_700_000_000_000L,
            toolLabel = "Tool 7",
            cameraSource = "INTERNAL",
            dxIn = 0.0012,
            dyIn = -0.0034,
            fitRmsPx = 0.85
        )
        store.append(record)

        val all = store.readAll()
        assertEquals(1, all.size)
        val loaded = all[0]
        assertEquals("Tool 7", loaded.toolLabel)
        assertEquals("INTERNAL", loaded.cameraSource)
        assertEquals(0.0012, loaded.dxIn, 1e-9)
        assertEquals(-0.0034, loaded.dyIn, 1e-9)
        assertEquals(0.85, loaded.fitRmsPx!!, 1e-9)
    }

    @Test
    fun `append accumulates multiple records across calls`() {
        val store = newStore()
        store.append(AlignmentRecord(1L, "A", "INTERNAL", 0.001, 0.002, 0.5))
        store.append(AlignmentRecord(2L, "B", "USB", -0.001, 0.0, null))

        val all = store.readAll()
        assertEquals(2, all.size)
        assertEquals("A", all[0].toolLabel)
        assertEquals("B", all[1].toolLabel)
        assertNull(all[1].fitRmsPx)
    }

    @Test
    fun `tool label commas are sanitized so the CSV stays well-formed`() {
        val store = newStore()
        store.append(AlignmentRecord(1L, "Tool, 7", "INTERNAL", 0.0, 0.0, null))

        val all = store.readAll()
        assertEquals(1, all.size)
        assertFalse(all[0].toolLabel.contains(","))
    }
}
