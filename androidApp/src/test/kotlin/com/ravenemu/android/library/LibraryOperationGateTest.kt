package com.ravenemu.android.library

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LibraryOperationGateTest {
    @Test fun importExcludesLaunchUntilBothDiskAndUiUpdatesFinish() {
        val gate = LibraryOperationGate()
        val writing = CountDownLatch(1)
        val finishDisk = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val transfer = requireNotNull(gate.tryAcquire())
        try {
            val disk = worker.submit {
                writing.countDown() // The previous core has been released.
                check(finishDisk.await(5, TimeUnit.SECONDS))
            }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            assertNull("A launch must not read the old save", gate.tryAcquire())
            finishDisk.countDown()
            disk.get(5, TimeUnit.SECONDS)
            assertNull("The final UI update still owns the operation", gate.tryAcquire())
            transfer.close()
            requireNotNull(gate.tryAcquire()).close()
            assertFalse(gate.isBusy())
        } finally {
            finishDisk.countDown()
            transfer.close()
            worker.shutdownNow()
        }
    }

    @Test fun cancelledPickerOrFailedOperationReleasesItsReservation() {
        val gate = LibraryOperationGate()
        try {
            requireNotNull(gate.tryAcquire()).use {
                assertNull(gate.tryAcquire())
                throw IllegalStateException("cancelled picker or failed import")
            }
        } catch (_: IllegalStateException) { }
        requireNotNull(gate.tryAcquire()).close()
        assertFalse(gate.isBusy())
    }

    @Test fun lateCompletionCannotReleaseAnotherOperation() {
        val gate = LibraryOperationGate()
        val first = requireNotNull(gate.tryAcquire())
        first.close()
        val second = requireNotNull(gate.tryAcquire())
        first.close()
        assertNull(gate.tryAcquire())
        second.close()
        assertFalse(gate.isBusy())
    }
}
