package com.smarttools.netguard.service

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback sockets and SOCKS/stripe frames. No external rooms or Android VPN. */
class StripeLifecycleQaTest {
    private class Relay : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val sockets = ConcurrentLinkedQueue<Socket>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val udpOpened = CountDownLatch(1)
        val udpClosed = CountDownLatch(1)
        val opens = AtomicInteger()
        val closing = AtomicBoolean()
        private val workers = ConcurrentLinkedQueue<Thread>()
        init {
            val accept = Thread({
                while (!closing.get()) try {
                    val socket = server.accept(); sockets.add(socket)
                    Thread({ serve(socket) }, "qa-stripe-peer").apply { isDaemon=true;workers.add(this);start() }
                } catch (t: Throwable) { if(!closing.get())errors.add(t);break }
            }, "qa-stripe-accept").apply { isDaemon=true;start() }
            workers.add(accept)
        }
        private fun serve(socket:Socket) {
            try {
                val input=DataInputStream(socket.getInputStream());val out=socket.getOutputStream()
                check(input.readUnsignedByte()==5)
                val methods=ByteArray(input.readUnsignedByte());input.readFully(methods)
                check(methods.contains(0.toByte()))
                out.write(byteArrayOf(5,0));out.flush()
                check(input.readUnsignedByte()==5);val command=input.readUnsignedByte();input.readUnsignedByte()
                when(input.readUnsignedByte()) {
                    1->input.readFully(ByteArray(4));4->input.readFully(ByteArray(16))
                    3->input.readFully(ByteArray(input.readUnsignedByte()));else->error("Unknown SOCKS address")
                }
                input.readUnsignedShort()
                out.write(byteArrayOf(5,0,0,1,127,0,0,1,1,1));out.flush()
                if(command==3) {
                    udpOpened.countDown()
                    while(input.read()!=-1) { }
                    udpClosed.countDown()
                } else {
                    check(command==1)
                    while(true) {
                        val frame=StripeFrame.read(input)
                        when(frame.type) {
                            StripeProtocol.HELLO->{out.write(frame.encode());out.flush()}
                            StripeProtocol.OPEN->opens.incrementAndGet()
                        }
                    }
                }
            } catch (_: EOFException) {
                // Normal mux teardown terminates the physical stream.
            } catch (t: SocketException) { if(!closing.get() && !t.message.orEmpty().contains("reset",true))errors.add(t) }
            catch(t:Throwable){if(!closing.get())errors.add(t)}
            finally {runCatching{socket.close()};sockets.remove(socket)}
        }
        override fun close() {
            closing.set(true);server.close();sockets.forEach{runCatching{it.close()}}
            workers.forEach{it.join(1000)}
        }
    }
    private fun freePort()=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use{it.localPort}
    private fun connect(port:Int)=Socket().apply{soTimeout=1500;connect(InetSocketAddress("127.0.0.1",port),1500)}
    private fun greeting(socket:Socket) {
        socket.getOutputStream().write(byteArrayOf(5,1,0))
        val input=DataInputStream(socket.getInputStream())
        assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte())
    }
    private fun request(socket:Socket,cmd:Int) {
        socket.getOutputStream().write(byteArrayOf(5,cmd.toByte(),0,1,127,0,0,1,0,80))
        val input=DataInputStream(socket.getInputStream());val reply=ByteArray(10);input.readFully(reply)
        assertEquals(5,reply[0].toInt());assertEquals(0,reply[1].toInt())
    }
    private fun assertClosed(socket:Socket) {
        socket.soTimeout=1000
        try { assertEquals("Stopped mux left a client socket open",-1,socket.getInputStream().read()) }
        catch(t:SocketTimeoutException){throw AssertionError("Stopped mux did not close within one second",t)}
        catch(_:SocketException){/* RST is also terminal; timeout above is never accepted. */}
    }
    private fun fixture(block:(StripeMux,Int,Relay)->Unit)=runBlocking {
        Relay().use { relay ->
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val port=freePort()
            val mux=StripeMux(port,listOf(InetSocketAddress("127.0.0.1",relay.server.localPort)),"127.0.0.1",38500,{})
            try { assertTrue(mux.start(scope));block(mux,port,relay) }
            finally {mux.stop();scope.cancel()}
            assertTrue("Fake relay assertions failed: ${relay.errors.map{it.javaClass.simpleName}}",relay.errors.isEmpty())
        }
    }
    @Test(timeout=12_000) fun stopClosesAcceptedSocketWaitingForRequest()=fixture {mux,port,_->
        connect(port).use { client ->
            greeting(client) // Proves server handler is admitted; then withhold the CONNECT request.
            mux.stop()
            assertClosed(client)
        }
    }
    @Test(timeout=12_000) fun stopClosesBothEndsOfEstablishedUdpAssociation()=fixture {mux,port,relay->
        connect(port).use { client ->
            greeting(client);request(client,3)
            assertTrue(relay.udpOpened.await(1,TimeUnit.SECONDS))
            mux.stop()
            assertClosed(client)
            assertTrue("UDP relay control socket leaked after stop",relay.udpClosed.await(1,TimeUnit.SECONDS))
        }
    }
    @Test(timeout=12_000) fun lateAcceptedHandlerCannotPublishAfterStop()=fixture {mux,_,relay->
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { ingress ->
            connect(ingress.localPort).use { client ->
                ingress.accept().use { accepted ->
                    val register=mux.javaClass.getDeclaredMethod("register",Socket::class.java).apply{isAccessible=true}
                    assertEquals(true,register.invoke(mux,accepted))
                    mux.stop()
                    // Model accept() already completed, but handler dispatch happened after stop().
                    val method=mux.javaClass.getDeclaredMethod("handleClient",Socket::class.java).apply{isAccessible=true}
                    val error=ConcurrentLinkedQueue<Throwable>()
                    val done=CountDownLatch(1)
                    val worker=Thread({try{method.invoke(mux,accepted)}catch(t:Throwable){error.add(t)}finally{done.countDown()}},"qa-late-admit").apply{isDaemon=true;start()}
                    try {
                        assertClosed(client)
                        assertTrue("Late handler blocked after stop",done.await(1,TimeUnit.SECONDS))
                        assertTrue("Late handler threw",error.isEmpty())
                        assertEquals(0,relay.opens.get())
                        val flows=mux.javaClass.getDeclaredField("flows").apply{isAccessible=true}.get(mux) as Map<*,*>
                        assertTrue("Stopped mux published orphan flow",flows.isEmpty())
                    } finally {accepted.close();worker.join(1000)}
                }
            }
        }
    }

    @Test(timeout=5000) fun flowClosedBeforeDispatchCannotStartWorkersOrMaintenance() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            connect(server.localPort).use { client -> server.accept().use { accepted ->
                val mux=StripeMux(0,emptyList(),"127.0.0.1",38500,{})
                val flow=StripeFlow(991,accepted,mux)
                fun field(name:String)=flow.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(flow)
                try {
                    flow.close() // stop won after flow admission but before its dispatch.
                    flow.run()
                    assertClosed(client)
                    for(name in listOf("writerThread","pumpThread")) {
                        val worker=field(name) as? Thread
                        assertTrue("Closed flow started $name",worker==null || !worker.isAlive)
                    }
                    val future=field("maintenance") as? java.util.concurrent.ScheduledFuture<*>
                    assertTrue("Closed flow retained periodic maintenance",future==null || future.isCancelled || future.isDone)
                } finally {
                    flow.close();mux.stop()
                    // Negative controls must not leak the very worker/timer being detected.
                    (field("maintenance") as? java.util.concurrent.ScheduledFuture<*>)?.cancel(false)
                    for(name in listOf("writerThread","pumpThread")) (field(name) as? Thread)?.let{it.interrupt();it.join(500)}
                }
            } }
        }
    }

    @Test(timeout=5000) fun closeDoesNotHoldLifecycleMonitorWhileWaitingForAckLock() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            connect(server.localPort).use { client -> server.accept().use { accepted ->
                val mux=StripeMux(0,emptyList(),"127.0.0.1",38500,{})
                val flow=StripeFlow(992,accepted,mux)
                val lock=flow.javaClass.getDeclaredField("lock").apply{isAccessible=true}.get(flow) as java.util.concurrent.locks.ReentrantLock
                val closed=flow.javaClass.getDeclaredField("closed").apply{isAccessible=true}.get(flow) as AtomicBoolean
                val entered=CountDownLatch(1)
                var closer:Thread?=null;var monitorProbe:Thread?=null
                lock.lock()
                try {
                    closer=Thread({flow.close()},"qa-flow-close").apply{isDaemon=true;start()}
                    val deadline=System.nanoTime()+1_000_000_000L
                    while(!closed.get() && System.nanoTime()<deadline)Thread.sleep(5)
                    assertTrue("Close never reached lifecycle barrier",closed.get())
                    monitorProbe=Thread({synchronized(flow){entered.countDown()}},"qa-flow-monitor").apply{isDaemon=true;start()}
                    // onAck can invoke abort/close while owning this lock. Keeping
                    // the monitor while waiting for it would create an ABBA cycle.
                    assertTrue("Close holds lifecycle monitor while waiting for ACK lock",entered.await(500,TimeUnit.MILLISECONDS))
                } finally {
                    lock.unlock();closer?.join(1000);monitorProbe?.join(1000)
                    flow.close();mux.stop()
                }
                assertClosed(client)
            } }
        }
    }
}
