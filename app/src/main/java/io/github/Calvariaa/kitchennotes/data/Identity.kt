package io.github.Calvariaa.kitchennotes.data

import kotlinx.serialization.Serializable

/**
 * 用户身份。启动时若没有身份会引导创建。
 *
 * 邮件地址与显示名会写进 git 提交（author/committer），
 * 同时这对 SSH 密钥是**节点身份证**：`nodeId` 就是它的公钥指纹。
 * 名字和邮箱都能改，指纹不能 —— 所以认人靠指纹，展示靠名字。
 */
@Serializable
data class UserIdentity(
    val name: String,
    val email: String,
    val sshPrivateKeyPem: String = "",
    val sshPublicKey: String = "",
    val createdAt: Long = 0L,
)

/**
 * 家庭组 —— 在 P2P 模型里它只是「一份共享密钥 + 一个名字」。
 *
 * 这里刻意**不存主机地址、不存角色**：
 *  - 没有主机，所以没有 hostAddress；
 *  - 每台设备的角色都完全相同，所以没有 role。
 * 组成员是谁、各自在哪个地址，存在 [Peer] 列表里（见 PeerStore），
 * 那份列表会因为设备上下线而变，不该和「我属于哪个家」这种稳定事实混在一个文件里。
 *
 * `secret` 是全家共享的对称密钥，两个用途：
 *  1. 派生加入码（口头念给家人）；
 *  2. 新节点加入时用它加密握手，证明「我知道这个家的口令」。
 * 它随第一次「创建家庭」生成，之后跟着每次同步在节点间一致传播。
 */
@Serializable
data class MeshGroup(
    /** 家庭组名，例如「我家厨房」 */
    val name: String,
    /** 全员共享的家庭密钥（base64url）。它是「我是这个家的一员」的唯一凭据 */
    val secret: String = "",
    /** 本机何时加入。仅用于排序和展示 */
    val joinedAt: Long = 0L,
) {
    val isConfigured: Boolean get() = secret.isNotBlank()
}

/**
 * 网内一个对等节点。
 *
 * `nodeId` 是 SSH 公钥指纹，是稳定主键 —— 地址会因 DHCP 变化，
 * 甚至同一个人换手机时也只换 nodeId 而保留 email，两种识别都要能对上。
 */
@Serializable
data class Peer(
    /** SSH 公钥指纹，例如 `SHA256:abc...` */
    val nodeId: String = "",
    /** 显示名（设备名或用户填的名字） */
    val name: String = "",
    val email: String = "",
    /** OpenSSH 格式公钥，用于验签。指纹可以重算，公钥必须留着 */
    val publicKey: String = "",
    /** 最近一次见到的地址，形如 `192.168.1.20:7411` */
    val address: String = "",
    val lastSeenAt: Long = 0L,
    /** 从本机视角看，这个节点的数据已经和本地对齐 */
    val synced: Boolean = false,
) {
    /** 只在局域网内可达，超过两小时就不再优先尝试（但仍会在扫描时刷新） */
    val isFresh: Boolean get() = System.currentTimeMillis() - lastSeenAt < 2 * 60 * 60 * 1000L
}
