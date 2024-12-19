package io.cequence.wsclient

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object EncryptionUtil {

  // HMAC-SHA256 signing
  def hmacSHA256(
    key: Array[Byte],
    data: String
  ): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    val keySpec = new SecretKeySpec(key, "HmacSHA256")
    mac.init(keySpec)
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
  }

  // SHA256 hashing
  def sha256Hash(data: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(data.getBytes(StandardCharsets.UTF_8))
    hash.map("%02x".format(_)).mkString
  }
}
