package com.inkslate.core.peer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Putting a message on the wire, and taking one off it.
 *
 * The devices talk over a private network - a tailnet, in practice - which already authenticates
 * the machines and encrypts what passes between them. This adds a shared secret on top anyway, for
 * two reasons: it decides *which app* is allowed to push marks into your documents rather than
 * which machine, and it means the same code is still safe on an ordinary network if the tunnel is
 * ever not there. It costs a key derivation once per connection and a few microseconds per
 * message.
 *
 * Frames are length-prefixed rather than newline-delimited: a stroke's JSON can contain anything,
 * and a protocol that can be confused by its own payload is a protocol with a bug in it waiting
 * for the right drawing.
 */
object PeerFrames {

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    /** Frames larger than this are refused outright: nothing legitimate is a hundred megabytes. */
    const val MAX_FRAME = 64 * 1024 * 1024

    private val random = SecureRandom()

    /**
     * Turn a pairing code into a key.
     *
     * Slow on purpose. The code is short enough for someone to read off a screen and type on a
     * tablet, which is exactly the kind of secret that has to cost something to guess.
     */
    fun keyFrom(code: String, salt: String): SecretKey {
        val spec = PBEKeySpec(
            code.trim().toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            ITERATIONS,
            KEY_BITS
        )
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** The salt both ends can work out without having spoken: the two device tags, in order. */
    fun saltFor(oneTag: String, otherTag: String): String =
        listOf(oneTag, otherTag).sorted().joinToString(":")

    fun write(out: DataOutputStream, key: SecretKey, message: PeerMessage) {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val body = cipher.doFinal(PeerMessage.encode(message).toByteArray(Charsets.UTF_8))
        synchronized(out) {
            out.writeInt(iv.size + body.size)
            out.write(iv)
            out.write(body)
            out.flush()
        }
    }

    /**
     * Read one frame, or null at the end of the stream.
     *
     * A frame that will not decrypt is a peer that does not have the same pairing code - or is not
     * this app at all - so it throws rather than being skipped over. Carrying on after that would
     * mean trusting the next frame from a stranger.
     */
    fun read(input: DataInputStream, key: SecretKey): PeerMessage? {
        val length = try {
            input.readInt()
        } catch (_: java.io.EOFException) {
            return null
        }
        require(length in (IV_BYTES + 1)..MAX_FRAME) { "Refusing a $length byte frame" }
        val iv = ByteArray(IV_BYTES).also { input.readFully(it) }
        val body = ByteArray(length - IV_BYTES).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val text = String(cipher.doFinal(body), Charsets.UTF_8)
        return PeerMessage.decode(text)
    }

    /** A code to read off one screen and type into another. Digits only, and no confusable pairs. */
    fun newPairingCode(): String = (1..6).joinToString("") { random.nextInt(10).toString() }
}
