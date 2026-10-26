package io.github.Calvariaa.kitchennotes.sync

import android.util.Base64
import io.github.Calvariaa.kitchennotes.data.FamilyGroup
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64 as JBase64
import java.util.concurrent.TimeUnit

/**
 * 家庭组成员管理。
 *
 * 身份方案（这是用户明确要的「用 ssh 密钥分辨用户」）：
 * - 每台设备首次启动生成一对 RSA-2048 密钥，私钥留在本机（Android 私有目录）。
 * - 公钥上传到主机，主机按 `email` 登记到 `.kitchen-notes/members.json`。
 * - 主机把所有成员公钥渲染成 OpenSSH 的 authorized_keys 文件，交给内置 SSH 服务端校验。
 *   JGit 的 JschConfigSessionFactory 支持指定 authorizedKeys 文件，于是「谁能推」由密钥决定，
 *   与 git 的常规运维方式一致，设备之间不共享密码。
 * - 每次 push/pull 时提交的 author/committer 用 name + email，主机侧就能看出是谁改的。
 *
 * 加入家庭组只需要扫一遍局域网：`LanDiscovery` 找到主机，`MembersApi` 拿配置，
 * 再 `git push`/`fetch` 完成第一次同步。
 */
class MembersApi(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun join(hostAddress: String, token: String, identity: io.github.Calvariaa.kitchennotes.data.UserIdentity): FamilyGroup =
        withContext(Dispatchers.IO) {
            val body = okhttp3.FormBody.Builder()
                .add("email", identity.email)
                .add("name", identity.name)
                .add("publicKey", identity.sshPublicKey)
                .build()
            val req = Request.Builder()
                .url("http://$hostAddress/api/join")
                .addHeader("X-Dish-Token", token)
                .post(body)
                .build()
            val resp = runCatching { http.newCall(req).execute() }
                .getOrElse { throw SyncError(SyncError.Kind.UNREACHABLE, it.message) }
            resp.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    // 主机明确说了令牌不对 → 用可翻译的 TOKEN_REJECTED；
                    // 其他 4xx/5xx 保留服务端原文
                    val serverMsg = parseError(text)
                    throw if (it.code == 403) SyncError(SyncError.Kind.TOKEN_REJECTED, serverMsg)
                    else SyncError(SyncError.Kind.JOIN_REJECTED, serverMsg ?: "HTTP ${it.code}")
                }
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw SyncError(SyncError.Kind.BAD_RESPONSE)
                FamilyGroup(
                    name = obj["groupName"]?.jsonPrimitive?.content
                        ?: HostRepository.FALLBACK_GROUP_NAME,
                    role = FamilyGroup.Role.MEMBER,
                    hostAddress = hostAddress,
                    token = token,
                    joinedAt = System.currentTimeMillis(),
                )
            }
        }

    suspend fun leave(hostAddress: String, token: String, email: String) = withContext(Dispatchers.IO) {
        val body = okhttp3.FormBody.Builder().add("email", email).build()
        runCatching {
            http.newCall(
                Request.Builder()
                    .url("http://$hostAddress/api/leave")
                    .addHeader("X-Dish-Token", token)
                    .post(body)
                    .build(),
            ).execute().use { it.body?.string() }
        }
    }

    /** 主机的 git 是否可达（用于扫描结果校验） */
    suspend fun probeInfo(hostAddress: String): DiscoveredHostLite? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("http://$hostAddress/api/info").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                DiscoveredHostLite(
                    groupName = obj["groupName"]?.jsonPrimitive?.content
                        ?: HostRepository.FALLBACK_GROUP_NAME,
                    hostName = obj["hostName"]?.jsonPrimitive?.content ?: "",
                    memberCount = obj["memberCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    requireToken = obj["requireToken"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                )
            }
        }.getOrNull()
    }

    private fun parseError(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()
}

data class DiscoveredHostLite(
    val groupName: String,
    val hostName: String,
    val memberCount: Int,
    val requireToken: Boolean,
)

/**
 * SSH 密钥的生成与 OpenSSH 格式编码。
 *
 * 用 JCA 生成 RSA-2048，然后手工拼 OpenSSH 的两种格式：
 * - 公钥：`ssh-rsa <base64(blob)> <comment>`
 * - 私钥：PEM 包裹的 `OpenSSH PRIVATE KEY` 块（JSch 认这个格式）
 * Android 上没有 ssh-keygen，所以这两段编码得自己写。算法照 RFC4253 / OpenSSH PROTOCOL.key。
 */
object SshKeyFactory {

    fun generateKeyPair(email: String): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }
        val kp = kpg.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val priv = kp.private as RSAPrivateCrtKey
        val privatePem = encodeOpenSshPrivate(priv, comment = email)
        return privatePem to encodeOpenSshPublic(pub, comment = email)
    }

    private fun encodeOpenSshPublic(pub: RSAPublicKey, comment: String): String {
        val blob = ByteArrayBuilder()
            .writeString("ssh-rsa")
            .writeMpInt(pub.publicExponent.toByteArray())
            .writeMpInt(pub.modulus.toByteArray())
            .build()
        return "ssh-rsa ${JBase64.getEncoder().encodeToString(blob)} $comment"
    }

    private fun encodeOpenSshPrivate(priv: RSAPrivateCrtKey, comment: String): String {
        // OpenSSH private key v1 (unencrypted)
        val magic = "openssh-key-v1".toByteArray()
        val cipher = "none"
        val kdf = "none"

        val publicBlob = ByteArrayBuilder()
            .writeString("ssh-rsa")
            .writeMpInt(priv.publicExponent.toByteArray())
            .writeMpInt(priv.modulus.toByteArray())
            .build()

        val privateBlob = ByteArrayBuilder()
            .writeInt(0x12345678) // checkint 1
            .writeInt(0x12345678) // checkint 2
            .writeString("ssh-rsa")
            .writeMpInt(priv.modulus.toByteArray())
            .writeMpInt(priv.publicExponent.toByteArray())
            .writeMpInt(priv.privateExponent.toByteArray())
            .writeMpInt(priv.crtCoefficient.toByteArray())
            .writeMpInt(priv.primeP.toByteArray())
            .writeMpInt(priv.primeQ.toByteArray())
            .writeString(comment)
            .build()
        // padding 使 total 为 8 的倍数
        val padded = privateBlob.copyOf(
            privateBlob.size + ((8 - privateBlob.size % 8) % 8),
        ).also { arr ->
            var pad = 1
            for (i in privateBlob.size until arr.size) arr[i] = pad++.toByte()
        }

        val body = ByteArrayBuilder()
            .writeBytes(magic)
            .writeByte(0)
            .writeString(cipher)
            .writeString(kdf)
            .writeString("")           // kdf options
            .writeInt(1)               // number of keys
            .writeString(publicBlob)
            .writeString(padded)
            .build()

        val b64 = JBase64.getEncoder().encode(body)
        val wrapped = b64.toString(Charsets.US_ASCII).chunked(70)
            .joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$wrapped\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /** SSH 二进制协议用的便捷写手 */
    private class ByteArrayBuilder {
        private val out = okioBuffer()

        private fun okioBuffer() = java.io.ByteArrayOutputStream()

        fun writeByte(b: Int) = apply { out.write(b) }
        fun writeInt(v: Int) = apply {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun writeBytes(b: ByteArray) = apply { out.write(b) }

        fun writeString(s: String) = writeString(s.toByteArray(Charsets.UTF_8))

        fun writeString(b: ByteArray) = apply {
            writeInt(b.size)
            out.write(b)
        }

        /** mpint：正数如果最高位是 1 要补一个 0x00 */
        fun writeMpInt(v: ByteArray) = apply {
            var bytes = v
            while (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1] >= 0) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            if (bytes.isNotEmpty() && bytes[0] < 0) bytes = byteArrayOf(0) + bytes
            writeString(bytes)
        }

        fun build(): ByteArray = out.toByteArray()
    }
}

/** 给 JGit 用的凭据provider：主机端校验靠 authorized_keys，客户端这边随便填。 */
fun anonymousCredentials() = UsernamePasswordCredentialsProvider("kitchen-notes", "")

internal fun pushSpec(branch: String) = RefSpec("refs/heads/$branch:refs/heads/$branch")
