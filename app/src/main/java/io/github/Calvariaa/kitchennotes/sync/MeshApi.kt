package io.github.Calvariaa.kitchennotes.sync

import android.util.Log
import io.github.Calvariaa.kitchennotes.data.MeshGroup
import io.github.Calvariaa.kitchennotes.data.Peer
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 局域网扫描到的一台候选节点 */
data class DiscoveredNode(
    val address: String,
    val nodeId: String,
    val name: String,
    val groupName: String,
    val hasGroup: Boolean,
)

/**
 * 节点之间的 JSON 交互：探针、加入、拉名册。
 *
 * 超时设得短（连接 3s）：局域网内的失败通常是「没这台机器」或「App 没跑」，
 * 都会立刻拒绝。只有 IP 存在但不响应才拖到超时，而扫描 254 个地址时这种占多数，
 * 所以宁可早放弃。
 */
class MeshApi(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 公开探针：这个 IP 上是不是一台 kitchen-notes 节点。
     *
     * 不需要签名 —— 扫描时还不知道对方公钥。返回 null 表示不是（连接拒绝、404、格式不对都算）。
     */
    suspend fun hello(address: String, port: Int): DiscoveredNode? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${peerBaseUrl(address, port)}/mesh/hello").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if (obj["app"]?.jsonPrimitive?.content != "kitchen-notes") return@withContext null
                DiscoveredNode(
                    address = normalizeAddress(address, obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: port),
                    nodeId = obj["nodeId"]?.jsonPrimitive?.content.orEmpty(),
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    groupName = obj["groupName"]?.jsonPrimitive?.content.orEmpty(),
                    hasGroup = obj["hasGroup"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true,
                )
            }
        }.getOrElse {
            Log.v(TAG, "hello $address failed: ${it.message}")
            null
        }
    }

    /** 加入的结果：家庭组 + 对端名册 */
    class JoinOutcome(val group: MeshGroup, val peers: List<Peer>)

    /**
     * 请求加入一个已有家庭组。
     *
     * 报文里只有三样东西：我的公钥（身份）、加入码（成员资格）、我的地址。
     * 对端验完加入码，用它自己的 secret 加密后**用我的公钥封好**回给我 ——
     * 只有我能解开，中间人即使抓到响应也拿不到 secret。
     *
     * 为什么不在请求里塞 secret：加入方本来就还没有 secret，也变不出来。
     * 反过来由「已有成员」发密钥，才是自然的信任方向。
     */
    suspend fun join(
        target: DiscoveredNode,
        inviteCode: String,
        identity: UserIdentity,
        port: Int,
    ): JoinOutcome = withContext(Dispatchers.IO) {
        val payload = MeshWire.json.encodeToString(
            JoinRequest.serializer(),
            JoinRequest(
                nodeId = MeshCrypto.nodeId(identity.sshPublicKey),
                publicKey = identity.sshPublicKey,
                name = identity.name,
                email = identity.email,
                port = port,
                inviteCode = inviteCode,
                sealedSecret = "",
            ),
        )
        val req = Request.Builder()
            .url("${peerBaseUrl(target.address, port)}/mesh/join")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching { http.newCall(req).execute() }.getOrElse {
            throw SyncError(SyncError.Kind.UNREACHABLE, it.message)
        }.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val code = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                }.getOrNull()
                throw when (resp.code) {
                    403 -> SyncError(SyncError.Kind.TOKEN_REJECTED, code)
                    409 -> SyncError(SyncError.Kind.NO_GROUP, code)
                    else -> SyncError(SyncError.Kind.JOIN_REJECTED, code ?: "HTTP ${resp.code}")
                }
            }
            val body = runCatching {
                MeshWire.json.decodeFromString(JoinResponse.serializer(), text)
            }.getOrNull() ?: throw SyncError(SyncError.Kind.BAD_RESPONSE)

            // 用本机私钥解出 secret。解不开说明对端封给了别人 —— 拒绝而不是静默接受
            val secret = MeshCrypto.decryptFromPeer(identity.sshPrivateKeyPem, body.sealedSecret)
                ?: throw SyncError(SyncError.Kind.BAD_RESPONSE, "sealedSecret")

            val discovered = body.peers.mapNotNull { brief ->
                if (brief.nodeId.isBlank() || brief.publicKey.isBlank()) return@mapNotNull null
                Peer(
                    nodeId = brief.nodeId,
                    name = brief.name,
                    email = brief.email,
                    publicKey = brief.publicKey,
                    address = brief.address,
                    lastSeenAt = System.currentTimeMillis(),
                )
            }
            // 响应方自己不在它的 peers 里（没人会把自己写进自己的名册），
            // 必须单独补进来 —— 少了这条，之后和它对账时没有公钥可用
            val responder = if (body.selfNodeId.isNotBlank() && body.selfPublicKey.isNotBlank()) {
                Peer(
                    nodeId = body.selfNodeId,
                    name = body.selfName.ifBlank { target.name },
                    email = body.selfEmail,
                    publicKey = body.selfPublicKey,
                    address = normalizeAddress(
                        target.address,
                        body.selfPort.takeIf { it > 0 } ?: port,
                    ),
                    lastSeenAt = System.currentTimeMillis(),
                )
            } else {
                null
            }
            JoinOutcome(
                group = MeshGroup(
                    name = body.groupName.ifBlank { "Kitchen" },
                    secret = secret,
                    joinedAt = System.currentTimeMillis(),
                ),
                peers = if (responder == null) discovered else discovered + responder,
            )
        }
    }

    /**
     * 拉对端名册（需要签名）。
     *
     * 用于定期刷新全家地址：加入时拿到的名册会过期（DHCP 换 IP、新成员加入），
     * 每次同步顺手拉一次，谁换了地址都能自动跟上。
     *
     * **响应的 peers 里不含对端自己**（没人会把自己写进自己的名册），
     * 所以要把 `NodeInfo` 顶层的 self 字段也转成一条 peer 加进去。
     * 否则 A 先加入 B、B 的名册里 A 是空的地址，之后 B 永远连不上 A —— 单向网格。
     */
    suspend fun peers(peer: Peer, identity: UserIdentity, port: Int): List<Peer> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder()
                    .url("${peerBaseUrl(peer.address, port)}/mesh/info")
                    .get()
                MeshAuth.apply(builder, identity, "/mesh/info")
                http.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val info = MeshWire.decodeNodeInfo(resp.body?.string().orEmpty())
                        ?: return@withContext emptyList()
                    val briefs = info.peers.mapNotNull { brief ->
                        if (brief.nodeId.isBlank() || brief.publicKey.isBlank()) return@mapNotNull null
                        Peer(
                            nodeId = brief.nodeId,
                            name = brief.name,
                            email = brief.email,
                            publicKey = brief.publicKey,
                            address = brief.address,
                            lastSeenAt = System.currentTimeMillis(),
                        )
                    }
                    // 对端自己那条：地址用「我刚连它的那个地址」，
                    // 比它自报的任何值都更可能真的可达
                    val self = if (info.nodeId.isNotBlank() && info.publicKey.isNotBlank()) {
                        listOf(
                            Peer(
                                nodeId = info.nodeId,
                                name = info.name.ifBlank { peer.name },
                                email = info.email,
                                publicKey = info.publicKey,
                                address = peer.address,
                                lastSeenAt = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                    briefs + self
                }
            }.getOrDefault(emptyList())
        }

    private fun normalizeAddress(host: String, port: Int): String =
        if (host.contains(":")) host else "$host:$port"

    private companion object {
        const val TAG = "MeshApi"
    }
}
