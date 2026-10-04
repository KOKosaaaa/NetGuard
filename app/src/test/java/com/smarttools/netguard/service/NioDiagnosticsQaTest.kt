package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NioDiagnosticsQaTest {
    private class Pairing : AutoCloseable {
        val external: SocketChannel
        val relay: SocketChannel
        init {
            ServerSocketChannel.open().use { listener ->
                listener.bind(InetSocketAddress("127.0.0.1",0))
                external=SocketChannel.open(listener.localAddress)
                relay=listener.accept()
            }
            external.socket().soTimeout=15000
            external.socket().receiveBufferSize=4096; relay.socket().sendBufferSize=4096
            external.socket().tcpNoDelay=true; relay.socket().tcpNoDelay=true
        }
        override fun close() { runCatching { external.close() }; runCatching { relay.close() } }
    }
    private fun receive(channel: SocketChannel, slow: Boolean): ByteArray {
        val result=ByteArrayOutputStream(); val buffer=ByteArray(4096)
        val input=channel.socket().getInputStream()
        while(true) { val n=input.read(buffer); if(n<0)break; result.write(buffer,0,n); if(slow)Thread.sleep(1) }
        return result.toByteArray()
    }
    @Test(timeout=30000) fun fullDuplexSlowReceiversCountWritesExactlyIncludingInitialBytesOnce() {
        val initial=ByteArray(16381) { (it%251).toByte() }
        val upload=ByteArray(524311) { ((it*17)%251).toByte() }
        val download=ByteArray(786439) { ((it*19+7)%251).toByte() }
        val up=AtomicLong(); val down=AtomicLong(); val closed=AtomicInteger()
        val progress=CountDownLatch(1); val done=CountDownLatch(1); val start=CountDownLatch(1)
        Pairing().use { client -> Pairing().use { server -> NioSiteRelay().use { relay ->
            relay.attach(client.relay,server.relay,initial,progress={u,d->up.addAndGet(u);down.addAndGet(d);progress.countDown()}) {
                closed.incrementAndGet();done.countDown()
            }
            assertTrue("active connection must report progress before close",progress.await(3,TimeUnit.SECONDS))
            assertEquals(0,closed.get()); assertTrue(up.get()>0)
            assertEquals(0L,down.get())
            val pool=Executors.newFixedThreadPool(4)
            try {
                val readUp=pool.submit<ByteArray> { start.await();receive(server.external,true) }
                val readDown=pool.submit<ByteArray> { start.await();receive(client.external,true) }
                val writeUp=pool.submit { start.await();client.external.socket().getOutputStream().write(upload);client.external.socket().shutdownOutput() }
                val writeDown=pool.submit { start.await();server.external.socket().getOutputStream().write(download);server.external.socket().shutdownOutput() }
                start.countDown()
                writeUp.get(20,TimeUnit.SECONDS);writeDown.get(20,TimeUnit.SECONDS)
                assertArrayEquals(initial+upload,readUp.get(20,TimeUnit.SECONDS))
                assertArrayEquals(download,readDown.get(20,TimeUnit.SECONDS))
                assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(1,closed.get())
                assertEquals((initial.size+upload.size).toLong(),up.get());assertEquals(download.size.toLong(),down.get())
            } finally { start.countDown();pool.shutdownNow() }
        } } }
    }
    @Test(timeout=12000) fun diagnosticCallbackExceptionCannotCorruptOrAbortUserBytes() {
        val payload=ByteArray(131099) { (it%239).toByte() };val calls=AtomicInteger();val closes=AtomicInteger()
        Pairing().use { client -> Pairing().use { server -> NioSiteRelay().use { relay ->
            relay.attach(client.relay,server.relay,progress={_,_->calls.incrementAndGet();throw IllegalStateException("diagnostics only")}) {
                closes.incrementAndGet();throw IllegalStateException("closed observer")
            }
            val pool=Executors.newFixedThreadPool(2)
            try {
                val remote=pool.submit<ByteArray> { receive(server.external,false).also { server.external.socket().shutdownOutput() } }
                val writer=pool.submit { client.external.socket().getOutputStream().write(payload);client.external.socket().shutdownOutput() }
                writer.get(8,TimeUnit.SECONDS);assertArrayEquals(payload,remote.get(8,TimeUnit.SECONDS))
                assertEquals(0,receive(client.external,false).size)
                assertTrue(calls.get()>0)
            } finally { pool.shutdownNow() }
        } } }
        assertEquals(1,closes.get())
    }
    @Test fun rejectedAdmissionMustNotCountBufferedInitialBytesAsTraffic() {
        val progress=AtomicLong();val closed=AtomicInteger()
        Pairing().use { client -> Pairing().use { server -> NioSiteRelay().use { relay ->
            relay.attach(client.relay,server.relay,ByteArray(32774),progress={u,d->progress.addAndGet(u+d)}) { closed.incrementAndGet() }
            assertEquals(0L,progress.get());assertEquals(1,closed.get())
            assertFalse(client.relay.isOpen);assertFalse(server.relay.isOpen)
        } } }
    }
    @Test fun idleOpenConnectionNeverInventsTraffic() {
        val up=AtomicLong();val down=AtomicLong();val closed=CountDownLatch(1)
        Pairing().use { client -> Pairing().use { server -> NioSiteRelay().use { relay ->
            relay.attach(client.relay,server.relay,progress={u,d->up.addAndGet(u);down.addAndGet(d)}) { closed.countDown() }
            assertEquals(0L,up.get());assertEquals(0L,down.get());assertEquals(1L,closed.count)
            client.external.socket().shutdownOutput();server.external.socket().shutdownOutput()
            assertTrue(closed.await(3,TimeUnit.SECONDS));assertEquals(0L,up.get());assertEquals(0L,down.get())
        } } }
    }
}
