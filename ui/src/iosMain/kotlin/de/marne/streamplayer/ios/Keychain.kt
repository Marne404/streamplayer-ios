package de.marne.streamplayer.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Kleine Werte (Anmeldung) im iOS-Schlüsselbund statt in einer Datei. */
@OptIn(BetaInteropApi::class)
internal object Keychain {
    private const val SERVICE = "de.marne.streamplayer.ipad"

    private fun key(k: CPointer<*>?) = CFBridgingRelease(k)

    private fun base(account: String) = NSMutableDictionary().apply {
        setObject(key(kSecClassGenericPassword)!!, forKey = key(kSecClass) as NSString)
        setObject(SERVICE, forKey = key(kSecAttrService) as NSString)
        setObject(account, forKey = key(kSecAttrAccount) as NSString)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cf(d: NSMutableDictionary): CFDictionaryRef = CFBridgingRetain(d) as CFDictionaryRef

    fun get(account: String): String? = memScoped {
        val q = base(account).apply {
            setObject(true, forKey = key(kSecReturnData) as NSString)
            setObject(key(kSecMatchLimitOne)!!, forKey = key(kSecMatchLimit) as NSString)
        }
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(cf(q), out.ptr)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(out.value) as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    fun set(account: String, value: String?) {
        SecItemDelete(cf(base(account)))
        if (value == null) return
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val q = base(account).apply {
            setObject(data, forKey = key(kSecValueData) as NSString)
            setObject(key(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)!!, forKey = key(kSecAttrAccessible) as NSString)
        }
        SecItemAdd(cf(q), null)
    }

    @Suppress("unused")
    private fun unused(p: CPointer<*>?) = p
}
