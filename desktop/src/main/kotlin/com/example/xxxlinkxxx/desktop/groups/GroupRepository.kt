package com.example.xxxlinkxxx.desktop.groups

import com.example.xxxlinkxxx.desktop.crypto.Crypto
import com.example.xxxlinkxxx.desktop.net.FirebaseClient
import com.example.xxxlinkxxx.desktop.net.Repository
import com.example.xxxlinkxxx.desktop.storage.SecurePrefs
import java.util.UUID

/**
 * Group-chat layer on top of Repository. Mirrors the Android v1.14.20-21
 * design (see MainActivity sendGroupMessage / discoverGroup / saveGroup):
 *
 *  - Each group has a Firestore document at /groups/{rawId} carrying
 *    name + members[] + adminId. The 12-char rawId is UUID-derived and
 *    immutable for the life of the group.
 *  - Local "groupId" is "g:" + rawId so the UI can distinguish a group
 *    chat target from a peer localId at a glance.
 *  - Group send fan-out: encrypt the plaintext under each member's
 *    pubkey (sender excluded), write one /messages/{msgId-memberId} doc
 *    per recipient with a shared `groupId` field. Receivers route the
 *    message into the group chat by looking at groupId.
 *  - First time we receive a message whose groupId we don't know yet,
 *    we pull /groups/{rawId} from Firestore and cache it locally —
 *    matches the auto-discovery flow on Android.
 */
class GroupRepository(
    private val base: Repository,
    private val prefs: SecurePrefs,
) {
    private val firebase: FirebaseClient get() = base.firebase
    private val localId: String get() = base.localId

    /** Set of all groupIds (prefixed "g:..." form) the user has joined. */
    fun savedGroupIds(): Set<String> = prefs.getStringSet(KEY_GROUP_IDS, emptySet())

    fun isGroupId(id: String): Boolean = id.startsWith(GROUP_ID_PREFIX)

    fun groupName(id: String): String =
        prefs.getString("$KEY_GROUP_NAME_PREFIX$id")?.takeIf { it.isNotBlank() } ?: id

    fun groupMembers(id: String): List<String> {
        val csv = prefs.getString("$KEY_GROUP_MEMBERS_PREFIX$id") ?: return emptyList()
        return csv.split(",").filter { it.isNotBlank() }
    }

    fun groupAdmin(id: String): String? = prefs.getString("$KEY_GROUP_ADMIN_PREFIX$id")

    /** Build a fresh group: writes /groups/{rawId} on Firestore and caches locally. */
    suspend fun createGroup(name: String, memberIds: List<String>): String {
        val rawId = UUID.randomUUID().toString().take(12).uppercase()
        val groupId = "$GROUP_ID_PREFIX$rawId"
        val members = (memberIds + localId).distinct()
        firebase.firestoreSet(
            "groups/$rawId",
            mapOf(
                "name" to name,
                "members" to members,
                "adminId" to localId,
                "createdAt" to System.currentTimeMillis(),
            ),
            merge = false,
        )
        saveLocal(groupId, name, members, localId)
        return groupId
    }

    /**
     * Pull /groups/{rawId} from Firestore on first contact (a group message
     * arrived from a group we don't know yet) and persist locally.
     */
    suspend fun discoverGroup(groupId: String): Boolean {
        val rawId = groupId.removePrefix(GROUP_ID_PREFIX)
        val doc = firebase.firestoreGet("groups/$rawId") ?: return false
        val name = doc["name"] as? String ?: return false
        @Suppress("UNCHECKED_CAST")
        val members = (doc["members"] as? List<String>) ?: return false
        val adminId = doc["adminId"] as? String ?: return false
        saveLocal(groupId, name, members, adminId)
        return true
    }

    /**
     * Fan-out send. Encrypts the same plaintext under each member's
     * messagePublicKey, writes one /messages/{msgId-memberId} doc per
     * recipient with a shared `groupId` field. Same msgId reused so the
     * sender sees only one local bubble.
     *
     * Returns the shared msgId on success, null if no members + pubkeys
     * could be resolved (group is effectively dead).
     */
    suspend fun sendGroupMessage(groupId: String, text: String): String? {
        val members = groupMembers(groupId).filter { it != localId }
        if (members.isEmpty()) return null
        val msgId = "$localId-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"
        var sentCount = 0
        for (memberId in members) {
            val pubkey = runCatching { base.fetchPeerPublicKey(memberId) }.getOrNull()
            if (pubkey.isNullOrBlank()) continue
            val enc = runCatching { Crypto.encryptMessageFor(text, pubkey) }.getOrNull()
                ?: continue
            val docId = "$msgId-$memberId"
            runCatching {
                firebase.firestoreSet(
                    "messages/$docId",
                    mapOf(
                        "from" to localId,
                        "to" to memberId,
                        "groupId" to groupId,
                        "encryptedKey" to enc.encryptedKey,
                        "iv" to enc.iv,
                        "cipherText" to enc.cipherText,
                        "messageAlgorithm" to enc.messageAlgorithm,
                        "keyAlgorithm" to enc.keyAlgorithm,
                        "createdAt" to System.currentTimeMillis(),
                    ),
                    merge = false,
                )
                sentCount++
            }
        }
        return if (sentCount > 0) msgId else null
    }

    private fun saveLocal(groupId: String, name: String, members: List<String>, adminId: String) {
        prefs.edit()
            .putStringSet(KEY_GROUP_IDS, savedGroupIds() + groupId)
            .putString("$KEY_GROUP_NAME_PREFIX$groupId", name)
            .putString("$KEY_GROUP_MEMBERS_PREFIX$groupId", members.joinToString(","))
            .putString("$KEY_GROUP_ADMIN_PREFIX$groupId", adminId)
            .apply()
    }

    fun leaveLocally(groupId: String) {
        prefs.edit()
            .putStringSet(KEY_GROUP_IDS, savedGroupIds() - groupId)
            .remove("$KEY_GROUP_NAME_PREFIX$groupId")
            .remove("$KEY_GROUP_MEMBERS_PREFIX$groupId")
            .remove("$KEY_GROUP_ADMIN_PREFIX$groupId")
            .apply()
    }

    companion object {
        const val GROUP_ID_PREFIX = "g:"
        private const val KEY_GROUP_IDS = "group_ids"
        private const val KEY_GROUP_NAME_PREFIX = "group_name_"
        private const val KEY_GROUP_MEMBERS_PREFIX = "group_members_"
        private const val KEY_GROUP_ADMIN_PREFIX = "group_admin_"
    }
}
