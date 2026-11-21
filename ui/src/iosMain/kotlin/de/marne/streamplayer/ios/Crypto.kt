package de.marne.streamplayer.ios

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal object Crypto {
    fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
        return out
    }

    fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        data.usePinned { d -> out.usePinned { o -> CC_SHA256(if (data.isEmpty()) null else d.addressOf(0), data.size.convert(), o.addressOf(0).reinterpretUByte()) } }
        return out
    }

    fun base64Url(data: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(data)

    private val lenient = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /**
     * PIN prüfen wie PinHash.matches auf Android: „pbkdf2|Runden|Salz|Hash“, PBKDF2-HMAC-SHA256,
     * 256 Bit, Base64 ohne Auffüllung.
     */
    fun pinMatches(stored: String, pin: String): Boolean {
        val p = stored.split('|')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val rounds = p[1].toIntOrNull() ?: return false
        val salt = runCatching { lenient.decode(p[2]) }.getOrNull() ?: return false
        val want = runCatching { lenient.decode(p[3]) }.getOrNull() ?: return false
        val pw = pin.encodeToByteArray()
        val got = ByteArray(want.size)
        val rc = pw.usePinned { pwp ->
            salt.usePinned { sp ->
                got.usePinned { gp ->
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2, pwp.addressOf(0), pw.size.convert(),
                        sp.addressOf(0).reinterpretUByte(), salt.size.convert(),
                        kCCPRFHmacAlgSHA256, rounds.convert(),
                        gp.addressOf(0).reinterpretUByte(), want.size.convert(),
                    )
                }
            }
        }
        if (rc != 0) return false
        var diff = 0
        for (i in want.indices) diff = diff or (want[i].toInt() xor got[i].toInt())
        return diff == 0
    }
}

private fun CPointer<ByteVar>.reinterpretUByte(): CPointer<UByteVar> = reinterpret()
