package com.yczz.neteasepoweramp.netease

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NetEase "weapi" request encryption (the scheme the web player itself uses).
 *
 * Plain /api/login endpoints are closed (401 "无权限访问. ENC") - login requires requests
 * encrypted with this exact AES-128-CBC (twice) + raw RSA construction. Algorithm is the
 * publicly documented one used by the music.163.com web client.
 */
object WeapiCrypto {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val RSA_PUBLIC_EXPONENT = "010001"
    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8" +
            "a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c" +
            "3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ec" +
            "e0462db0a22b8e7"
    private const val SECRET_KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val SECRET_KEY_LENGTH = 16

    /** Form body fields for the weapi request. */
    data class Encrypted(val params: String, val encSecKey: String)

    fun encrypt(payloadJson: String): Encrypted {
        val secretKey = randomSecretKey()
        val once = aesCbc(payloadJson, PRESET_KEY)
        val params = aesCbc(once, secretKey)
        return Encrypted(params = params, encSecKey = rsa(secretKey))
    }

    private fun aesCbc(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.US_ASCII)),
        )
        return Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Raw RSA (no padding) over the reversed secret key; hex, zero-padded to 256 chars. */
    private fun rsa(secretKey: String): String {
        val message = BigInteger(secretKey.reversed().toByteArray(Charsets.US_ASCII))
        val encrypted = message.modPow(
            BigInteger(RSA_PUBLIC_EXPONENT, 16),
            BigInteger(RSA_MODULUS, 16),
        )
        return encrypted.toString(16).padStart(256, '0')
    }

    private fun randomSecretKey(): String {
        val random = SecureRandom()
        val out = StringBuilder(SECRET_KEY_LENGTH)
        repeat(SECRET_KEY_LENGTH) {
            out.append(SECRET_KEY_ALPHABET[random.nextInt(SECRET_KEY_ALPHABET.length)])
        }
        return out.toString()
    }
}
