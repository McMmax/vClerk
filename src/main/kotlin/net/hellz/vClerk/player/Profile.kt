package net.hellz.vClerk

import com.google.gson.Gson
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.hellz.util.StreamConnection
import net.hellz.utils.LettuceConnection
import org.bson.Document
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class Profile private constructor(val uuid: UUID, val username: String, val ipAddresses: MutableList<String>) {
    val permissions: MutableSet<String> = mutableSetOf()

    companion object {
        val profiles = ConcurrentHashMap<UUID, Profile>()
        private val gson = Gson()
        private const val CACHE_TTL = 600 // Cache time in seconds (10 minutes)

        suspend fun retrieve(player: Player) {
            retrieve(player.uniqueId.toString(), player.username)
        }

        suspend fun retrieve(playerUuid: String, playerName: String? = null) {
            val uuid = UUID.fromString(playerUuid)
            val cacheKey = "profile2:$playerUuid"

            val cachedProfile = LettuceConnection.get(cacheKey)
            if (cachedProfile != null) {
                val profileData = gson.fromJson(cachedProfile, ProfileData::class.java)
                profiles[uuid] = Profile(uuid, profileData.username, profileData.ipAddresses.toMutableList()).apply {
                    permissions.addAll(profileData.permissions)
                }
                println("[clerk] Loaded profile for ${profileData.username} from Redis cache.")
                return
            }

            val filter = Document("_id", playerUuid)
            val doc = withContext(Dispatchers.IO) {
                StreamConnection.readAsync("profiles2", filter)
            }

            if (doc != null) {
                val profile = Profile(uuid, doc.getString("username"), doc.getList("ipAddresses", String::class.java).toMutableList()).apply {
                    permissions.addAll(doc.getList("permissions", String::class.java))
                }
                profiles[uuid] = profile
                LettuceConnection.setex(cacheKey, gson.toJson(ProfileData(profile)), CACHE_TTL.toLong())
                println("[clerk] Cached profile for ${profile.username} in Redis.")
            } else {
                if (playerName != null) create(playerUuid, playerName)
            }
        }

        suspend fun create(playerUuid: String, username: String) {
            val uuid = UUID.fromString(playerUuid)
            val ipAddresses = mutableListOf("127.0.0.1") // Placeholder IP, replace with actual

            val profile = Profile(uuid, username, ipAddresses)
            profiles[uuid] = profile

            val document = Document("_id", playerUuid)
                .append("username", username)
                .append("ipAddresses", ipAddresses)
                .append("permissions", profile.permissions.toList())
            StreamConnection.writeAsync("profiles2", document)

            val cacheKey = "profile2:$playerUuid"
            LettuceConnection.setex(cacheKey, gson.toJson(ProfileData(profile)), CACHE_TTL.toLong())
            println("[clerk] Created and cached profile for $username.")
        }
    }

    suspend fun hasPermission(permission: String): Boolean {
        if (permissions.contains(permission)) {
            return true
        }
        val parts = permission.split(".")
        for (i in parts.indices) {
            val wildcardPermission = parts.subList(0, i).joinToString(".") + ".*"
            if (permissions.contains(wildcardPermission)) {
                return true
            }
        }
        return false
    }

    data class ProfileData(val username: String, val permissions: List<String>, val ipAddresses: List<String>) {
        constructor(profile: Profile) : this(profile.username, profile.permissions.toList(), profile.ipAddresses)
    }
}
