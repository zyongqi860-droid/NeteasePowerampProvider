package com.yczz.neteasepoweramp.netease

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * NetEase "eapi" request encryption (AES-128-ECB over a signed payload).
 *
 * The public weapi POST path on music.163.com is stripped by CDN (HTTP 200, empty body).
 * Login / SMS / some account endpoints must go through interface.music.163.com/eapi/...
 * with this construction. Algorithm matches the music.163.com client.
 */
object EapiCrypto {

    private val KEY = "e82ckenh8dichen8".toByteArray(Charsets.US_ASCII)
    private const val DIGEST_SALT_LEFT = "nobody"
    private const val DIGEST_SALT_MID = "use"
    private const val DIGEST_SALT_RIGHT = "md5forencrypt"
    private const val PAYLOAD_SEP = "-36cd479b6b5-"

    /**
     * @param apiPath the /api/... path that the server decrypts (not the /eapi/... URL).
     * @param payloadJson already-serialized JSON object (no extra whitespace).
     * @return uppercase hex ciphertext for the form field `params`.
     */
    fun encrypt(apiPath: String, payloadJson: String): String {
        val digest = md5Hex(DIGEST_SALT_LEFT + apiPath + DIGEST_SALT_MID + payloadJson + DIGEST_SALT_RIGHT)
        val message = apiPath + PAYLOAD_SEP + payloadJson + PAYLOAD_SEP + digest
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))
        return cipher.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
