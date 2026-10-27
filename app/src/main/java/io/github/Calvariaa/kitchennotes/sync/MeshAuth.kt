package io.github.Calvariaa.kitchennotes.sync

import io.github.Calvariaa.kitchennotes.data.UserIdentity

/**
 * 生成对端请求要带的签名头。
 *
 * 客户端与服务端共用同一份签名格式，所以放在这里而不是各自实现 ——
 * 两边一旦漂移（少一个换行、时间单位错一个零），表现是清一色 403，
 * 而 403 看不出是哪一步不匹配，排查要从头读两个文件。
 */
object MeshAuth {

    /**
     * `Authorization: Kn1 <nodeId> <timeMs> <nonce> <sig>`
     *
     * 用自定义 scheme 而不是 Basic/Bearer：git 客户端会把未知 scheme 原样转发，
     * Basic 在某些实现里会被强制 base64 重编码，Bearer 容易被中间件替换。
     */
    fun header(identity: UserIdentity, scope: String): Map<String, String> {
        val timeMs = System.currentTimeMillis()
        val nonce = MeshCrypto.randomNonce()
        val sig = MeshCrypto.sign(identity.sshPrivateKeyPem, scope, timeMs, nonce) ?: return emptyMap()
        val nodeId = MeshCrypto.nodeId(identity.sshPublicKey)
        return mapOf("Authorization" to "Kn1 $nodeId $timeMs $nonce $sig")
    }

    /** 给 OkHttp 请求用（JSON 端点） */
    fun apply(builder: okhttp3.Request.Builder, identity: UserIdentity, scope: String) {
        header(identity, scope).forEach { (k, v) -> builder.addHeader(k, v) }
    }
}
