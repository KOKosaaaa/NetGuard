package com.smarttools.netguard

import com.smarttools.netguard.agent.SshHostTrust
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import net.schmizz.sshj.common.Buffer

class SshHostTrustQaTest {
    @Test fun unknownKeyCannotAuthenticateBeforeExplicitApprovalAndPersistsAcrossInstances() {
        val store = mutableMapOf<String,String>()
        val raw = byteArrayOf(0,0,0,7,115,115,104,45,114,115,97,42)
        val trust = SshHostTrust("Server.Example",22,store::get,{k,v->store[k]=v})
        assertFalse(trust.verify(raw))
        assertTrue(store.isEmpty())
        val fingerprint = "SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw))
        assertEquals(fingerprint, trust.pendingFingerprint)
        trust.approve(fingerprint)
        assertTrue(trust.verify(raw))
        val reopened = SshHostTrust("server.example",22,store::get,{_,_->fail("Already trusted must not rewrite")})
        assertTrue(reopened.verify(raw))
        assertFalse(SshHostTrust("server.example",2222,store::get,{_,_->}).verify(raw))
    }

    @Test fun changedKeyFailsClosedWithoutOfferingSilentReplacement() {
        val store=mutableMapOf<String,String>()
        val initial=SshHostTrust("host",22,store::get,{k,v->store[k]=v})
        initial.verify(byteArrayOf(1,2));initial.approve(initial.pendingFingerprint!!)
        val next=SshHostTrust("host",22,store::get,{_,_->fail("Must not replace trusted key")})
        assertFalse(next.verify(byteArrayOf(3,4)))
        assertNull(next.pendingFingerprint)
        assertTrue(assertThrows(SshHostTrust.Failure::class.java) { next.rethrowFailure() }.changed)
    }

    @Test fun sshjFingerprintUsesSshWireEncodingRatherThanX509Der() {
        val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val wire=Buffer.PlainBuffer().putPublicKey(key).compactData
        val decoded=Buffer.PlainBuffer(wire).readPublicKey()
        assertArrayEquals(key.encoded,decoded.encoded)
        assertFalse(wire.contentEquals(key.encoded))
        val store=mutableMapOf<String,String>()
        val trust=SshHostTrust("host",22,store::get,{k,v->store[k]=v})
        assertFalse(trust.verify(wire));trust.approve(trust.pendingFingerprint!!)
        // JSch/Trilead callbacks supply this exact SSH wire blob.
        assertTrue(trust.verify(wire.copyOf()))
    }
}
