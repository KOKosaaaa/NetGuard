package com.smarttools.netguard.service

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TelegramProbeTest {
    @Test fun authenticatedSocksAndMtprotoNonceAreVerified() {
        val listener = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val server = executor.submit<Boolean> {
                listener.accept().use { socket ->
                    socket.soTimeout=3000
                    val input=DataInputStream(socket.getInputStream()); val out=socket.getOutputStream()
                    assertEquals(5,input.readUnsignedByte()); assertEquals(1,input.readUnsignedByte()); assertEquals(2,input.readUnsignedByte())
                    out.write(byteArrayOf(5,2))
                    assertEquals(1,input.readUnsignedByte())
                    val u=ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    val p=ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    assertEquals("user",String(u)); assertEquals("password",String(p))
                    out.write(byteArrayOf(1,0))
                    assertEquals(5,input.readUnsignedByte()); assertEquals(1,input.readUnsignedByte());input.readByte()
                    assertEquals(3,input.readUnsignedByte())
                    input.readFully(ByteArray(input.readUnsignedByte()));assertEquals(443,input.readUnsignedShort())
                    out.write(byteArrayOf(5,0,0,1,0,0,0,0,0,0))
                    assertEquals(0xef,input.readUnsignedByte()); assertEquals(10,input.readUnsignedByte())
                    val request=ByteArray(40).also { input.readFully(it) }
                    val msg=ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(0L,msg.long); assertEquals(0L,msg.long and 3); assertEquals(20,msg.int);assertEquals(0xbe7e8ef1.toInt(),msg.int)
                    val nonce=ByteArray(16).also { msg.get(it) }
                    val reply=ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(1).putInt(20).putInt(0x05162463).put(nonce).array()
                    out.write(byteArrayOf(10)+reply)
                };true
            }
            SocksServiceProbe(listener.localPort,"user","password").use { assertEquals(Reachability.AVAILABLE,it.check(HealthTarget.TELEGRAM)) }
            assertTrue(server.get(4,TimeUnit.SECONDS))
        } finally { listener.close();executor.shutdownNow() }
    }
}
