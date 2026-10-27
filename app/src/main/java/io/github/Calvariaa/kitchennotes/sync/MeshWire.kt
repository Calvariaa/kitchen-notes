package io.github.Calvariaa.kitchennotes.sync

import io.github.Calvariaa.kitchennotes.data.MeshGroup
import io.github.Calvariaa.kitchennotes.data.Peer
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 节点之间交换的报文。
 *
 * 全部走 JSON 而不是自定义二进制：这些端点一秒钟最多被调几次，
 * 可读性（抓包、日志、手工 curl 调试）比省几个字节重要得多。
 * 真正的高频数据是 git pack，那条路径不经过这里。
 */
@Serializable
data class NodeInfo(
    /** SSH 公钥指纹，节点稳定主键 */
    val nodeId: String = "",
    val publicKey: String = "",
    val name: String = "",
    val email: String = "",
    /** 家庭密钥派生的公共标签。对不上说明不是同一个家，直接拒绝 */
    val groupTag: String = "",
    val groupName: String = "",
    val port: Int = 0,
    /** 本机已知的其他节点，加入时一次拿到全网名册，不必逐个扫 */
    val peers: List<PeerBrief> = emptyList(),
    val recipeCount: Int = 0,
    val appVersion: String = "",
) {
    @Serializable
    data class PeerBrief(
        val nodeId: String = "",
        val publicKey: String = "",
        val name: String = "",
        val email: String = "",
        val address: String = "",
    )
}

/** 加入：交上自己的身份与加入码，换回加密的家庭密钥和名册 */
@Serializable
data class JoinRequest(
    val nodeId: String = "",
    val publicKey: String = "",
    val name: String = "",
    val email: String = "",
    val port: Int = 0,
    /** 加入码，证明「我知道这个家的口令」 */
    val inviteCode: String = "",
    /** 保留字段：早期草案里由加入方封密钥，现已改由成员封。留着以免旧客户端 400 */
    val sealedSecret: String = "",
)

/** 加入成功的回执 */
@Serializable
data class JoinResponse(
    val groupName: String = "",
    /** 用**加入者公钥**加密的家庭密钥。只有加入者能解开，中间人拿到也没用 */
    val sealedSecret: String = "",
    /**
     * 响应方自己的 nodeId 与公钥。
     *
     * 必须单独返回：`peers` 里是「它认识的其他节点」，**不含它自己**
     * （谁都不会把自己写进自己的名册）。而加入之后所有请求都要用它的公钥验签，
     * 只靠扫描结果补不出公钥 —— 扫描时对方还没入组，`/mesh/hello` 出于隐私
     * 刻意不返回公钥。少了这两个字段，新节点会有一条 publicKey 为空的
     * 僵尸名册项，从此跟它对不上账、永远同步失败。
     */
    val selfNodeId: String = "",
    val selfPublicKey: String = "",
    val selfName: String = "",
    val selfEmail: String = "",
    val selfPort: Int = 0,
    val peers: List<NodeInfo.PeerBrief> = emptyList(),
)

object MeshWire {
    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun decodeNodeInfo(body: String): NodeInfo? =
        runCatching { json.decodeFromString(NodeInfo.serializer(), body) }.getOrNull()

    fun decodeJoinRequest(body: String): JoinRequest? =
        runCatching { json.decodeFromString(JoinRequest.serializer(), body) }.getOrNull()

    fun infoBody(
        identity: UserIdentity,
        nodeId: String,
        group: MeshGroup,
        port: Int,
        peers: List<Peer>,
        recipeCount: Int,
        appVersion: String,
    ): String = buildJsonObject {
        put("nodeId", nodeId)
        put("publicKey", identity.sshPublicKey)
        put("name", identity.name)
        put("email", identity.email)
        put("groupTag", MeshCrypto.groupTag(group.secret))
        put("groupName", group.name)
        put("port", port)
        put("recipeCount", recipeCount)
        put("appVersion", appVersion)
        put("peers", JsonArray(peers.map { p ->
            buildJsonObject {
                put("nodeId", p.nodeId)
                put("publicKey", p.publicKey)
                put("name", p.name)
                put("email", p.email)
                put("address", p.address)
            }
        }))
    }.toString()

    fun joinResponseBody(
        groupName: String,
        sealedSecret: String,
        self: UserIdentity,
        selfNodeId: String,
        port: Int,
        peers: List<Peer>,
    ): String =
        buildJsonObject {
            put("groupName", groupName)
            put("sealedSecret", sealedSecret)
            put("selfNodeId", selfNodeId)
            put("selfPublicKey", self.sshPublicKey)
            put("selfName", self.name)
            put("selfEmail", self.email)
            put("selfPort", port)
            put("peers", JsonArray(peers.map { p ->
                buildJsonObject {
                    put("nodeId", p.nodeId)
                    put("publicKey", p.publicKey)
                    put("name", p.name)
                    put("email", p.email)
                    put("address", p.address)
                }
            }))
        }.toString()
}
