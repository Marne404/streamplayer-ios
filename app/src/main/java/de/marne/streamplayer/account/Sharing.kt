package de.marne.streamplayer.account

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.LibraryStore
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

/**
 * Bibliotheken mit Freunden teilen – nur zum Ansehen. Aufbau im Konto (Regeln: firebase/firestore.rules):
 *
 *   shared/{libraryId}                  { owner, ownerName, name, color, updatedAt }
 *   shared/{libraryId}/titles/{titleId} { json, updatedAt, deleted }   – spiegelt CloudSync
 *   shared/{libraryId}/members/{uid}    { code, name, joinedAt }       – wer beigetreten ist
 *   invites/{code}                      { shareId, owner, createdAt }  – Einladung, Code = Dokument-ID
 *
 * Beitreten heißt: mit dem Code das eigene Mitglieds-Dokument anlegen. Die Regeln prüfen, dass
 * der Code zu genau dieser Bibliothek gehört – wer den Code nicht kennt, kommt nicht hinein.
 */
class Sharing(private val cloud: Cloud, private val library: LibraryStore) {

    data class Member(val uid: String, val name: String, val joinedAt: Long)

    class ShareException(message: String) : Exception(message)

    private val db: FirebaseFirestore get() = cloud.db ?: throw ShareException(tr("Konto ist in dieser Version nicht eingerichtet.", "Accounts aren't set up in this version."))
    private fun me(): Cloud.User = cloud.user.value ?: throw ShareException(tr("Zum Teilen erst bei einem Konto anmelden.", "Sign in to an account to share."))
    private fun myName(u: Cloud.User) = u.name?.takeIf { it.isNotBlank() } ?: u.email?.substringBefore('@') ?: tr("Ein Freund", "A friend")

    /**
     * Teilen einschalten (oder neue Einladung): legt die geteilte Bibliothek an bzw. aktualisiert
     * Name und Farbe und gibt einen gültigen Einladungscode zurück. Die Titel spiegelt dann CloudSync.
     */
    suspend fun invite(l: Library): String {
        val u = me()
        require(!l.joined && l.id != Library.MAIN)
        db.collection("shared").document(l.id).set(mapOf(
            "owner" to u.uid, "ownerName" to myName(u), "name" to l.label, "color" to l.color,
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
        val code = l.invite ?: newCode().also { c ->
            db.collection("invites").document(c).set(mapOf("shareId" to l.id, "owner" to u.uid, "createdAt" to FieldValue.serverTimestamp())).await()
        }
        library.setLibrary(l.copy(shared = true, invite = code))
        return code
    }

    /** Name oder Farbe geändert: auch bei den Freunden. */
    suspend fun updateMeta(l: Library) {
        if (!l.shared) return
        val u = me()
        db.collection("shared").document(l.id).set(mapOf(
            "owner" to u.uid, "ownerName" to myName(u), "name" to l.label, "color" to l.color,
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
    }

    suspend fun members(l: Library): List<Member> =
        db.collection("shared").document(l.id).collection("members").get().await().documents.map {
            Member(it.id, it.getString("name") ?: tr("Ein Freund", "A friend"), it.getTimestamp("joinedAt")?.toDate()?.time ?: 0)
        }.sortedBy { it.joinedAt }

    suspend fun removeMember(l: Library, uid: String) {
        db.collection("shared").document(l.id).collection("members").document(uid).delete().await()
    }

    /**
     * Teilen beenden: Einladung, Mitglieder, gespiegelte Titel und die geteilte Bibliothek löschen –
     * in dieser Reihenfolge, denn die Regeln für die Unterlagen fragen das Hauptdokument.
     * Bei Freunden verschwindet die Bibliothek beim nächsten Abgleich.
     */
    suspend fun stop(l: Library) {
        me()
        val root = db.collection("shared").document(l.id)
        l.invite?.let { runCatching { db.collection("invites").document(it).delete().await() } }
        for (sub in listOf("members", "titles")) {
            val docs = root.collection(sub).get().await().documents
            docs.chunked(400).forEach { part -> db.batch().apply { part.forEach { delete(it.reference) } }.commit().await() }
        }
        root.delete().await()
        library.setLibrary(l.copy(shared = false, invite = null))
    }

    /** Einladung einlösen: Rückgabe ist die neue (nur lesbare) Bibliothek. */
    suspend fun join(code: String): Library {
        val u = me()
        val invite = runCatching { db.collection("invites").document(code).get().await() }.getOrNull()
        val sid = invite?.getString("shareId") ?: throw ShareException(tr("Diese Einladung gibt es nicht (mehr).", "This invite doesn't exist (anymore)."))
        if (invite.getString("owner") == u.uid) throw ShareException(tr("Das ist deine eigene Bibliothek.", "That's your own library."))
        db.collection("shared").document(sid).collection("members").document(u.uid)
            .set(mapOf("code" to code, "name" to myName(u), "joinedAt" to FieldValue.serverTimestamp())).await()
        val meta = db.collection("shared").document(sid).get().await()
        val l = Library(
            id = sid,
            name = meta.getString("name") ?: tr("Geteilt", "Shared"),
            color = (meta.getLong("color") ?: 0).toInt(),
            joined = true,
            owner = meta.getString("ownerName"),
        )
        library.setLibrary(l)
        return l
    }

    /** Freundes-Bibliothek verlassen. Auch ohne Netz: lokal ist sie sofort weg. */
    suspend fun leave(l: Library) {
        library.removeJoined(l.id)
        val u = cloud.user.value ?: return
        runCatching { db.collection("shared").document(l.id).collection("members").document(u.uid).delete().await() }
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        /** 22 Zeichen ohne Verwechsler (0/O, 1/l/I): nicht zu erraten, aber abtippbar. */
        fun newCode(): String {
            val r = SecureRandom()
            return (1..22).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }

        private val codeRe = Regex("""(?:streamplayer://join/)?([A-Za-z2-9]{22})""")

        /** Code aus einem eingefügten Text (ganze Nachricht, Link oder nur der Code). */
        fun codeIn(text: String): String? =
            Regex("""streamplayer://join/([A-Za-z0-9]{22})""").find(text)?.groupValues?.get(1)
                ?: codeRe.findAll(text).map { it.groupValues[1] }.firstOrNull { c -> c.all { it in ALPHABET } }

        fun inviteText(l: Library, code: String) = tr(
            "Ich teile meine Streamplayer-Bibliothek „${l.label}“ mit dir. Öffnen: streamplayer://join/$code\n" +
                "Oder in Streamplayer: Bibliotheken → Einladung einlösen, Code: $code",
            "I'm sharing my Streamplayer library “${l.label}” with you. Open: streamplayer://join/$code\n" +
                "Or in Streamplayer: Libraries → Redeem invite, code: $code",
        )
    }
}
