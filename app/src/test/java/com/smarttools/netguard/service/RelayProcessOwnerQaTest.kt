package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelayProcessOwnerQaTest {
    private class Child : Process() {
        val destroyed = CountDownLatch(1)
        override fun destroy() { destroyed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue() = 0
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
    }

    @Test fun childCreatedDuringStopCannotBePublishedAndIsTerminated() {
        val owner = RelayProcessOwner()
        val child = Child() // Models pb.start() completing after the stop snapshot.
        owner.stop()
        assertFalse(owner.admit(child, BufferedWriter(StringWriter())))
        assertTrue(child.destroyed.await(1, TimeUnit.SECONDS))
        assertNull(owner.process)
        assertNull(owner.writer)
    }

    @Test fun admittedChildIsDetachedAndTerminatedExactlyAsStopCompletes() {
        val owner = RelayProcessOwner()
        val child = Child()
        assertTrue(owner.admit(child, BufferedWriter(StringWriter())))
        owner.stop()
        assertNull(owner.process)
        assertNull(owner.writer)
        assertTrue(child.destroyed.await(1, TimeUnit.SECONDS))
        owner.stop()
        assertTrue(owner.stopped)
    }

    @Test fun blockedWriterCloseDoesNotHoldTheLifecycleLockOrStopCaller() {
        val owner = RelayProcessOwner()
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val writer = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {}
            override fun flush() {}
            override fun close() { closeStarted.countDown(); releaseClose.await(2, TimeUnit.SECONDS) }
        }
        try {
            assertTrue(owner.admit(Child(), BufferedWriter(writer)))
            owner.stop()
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
            assertFalse(owner.admit(Child(), BufferedWriter(StringWriter())))
            assertNull(owner.process)
        } finally { releaseClose.countDown() }
    }
}
