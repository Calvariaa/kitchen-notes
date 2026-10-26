package io.github.Calvariaa.kitchennotes.data

import kotlinx.serialization.Serializable

/**
 * 用户身份。启动时若没有身份会引导创建。
 *
 * 邮件地址与显示名会写进 git 提交（author/committer），
 * 同时也自动生成一对 SSH 密钥写进家庭组主机的授权列表，作为「这个人是谁」的凭据。
 */
@Serializable
data class UserIdentity(
    val name: String,
    val email: String,
    val sshPrivateKeyPem: String = "",
    val sshPublicKey: String = "",
    val createdAt: Long = 0L,
)

/** 家庭组。一台设备当主机（host），其余设备加入。 */
@Serializable
data class FamilyGroup(
    /** 家庭组名，例如「我家厨房」 */
    val name: String,
    /** 本机角色 */
    val role: Role = Role.NONE,
    /** 主机地址，形如 `192.168.1.20:7411` */
    val hostAddress: String = "",
    /** 加入时使用的访问令牌（主机生成，全员共享） */
    val token: String = "",
    /** 自己的仓库在本机（自己就是主机）还是远端 */
    val joinedAt: Long = 0L,
) {
    enum class Role { NONE, HOST, MEMBER }
}

/** 一台被发现的主机候选。用于「扫一遍本地网络」的结果列表。 */
data class DiscoveredHost(
    val address: String,
    val groupName: String,
    val hostName: String,
    val memberCount: Int,
    val requireToken: Boolean,
)
