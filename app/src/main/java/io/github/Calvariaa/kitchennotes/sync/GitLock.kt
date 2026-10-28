package io.github.Calvariaa.kitchennotes.sync

import kotlinx.coroutines.sync.Mutex

/**
 * 全局 git 互斥锁。
 *
 * **为什么必须有这把锁**：同一个工作区 `repo/` 有至少四路写入方，它们原先完全并发：
 *  - UI 协程：`commitAndPush` → `commitAll`（`git.add` + `commit`）
 *  - `MeshSync.syncAll` 协程：`commitAll` → `fetch` → `merge` → `push` → `pushWorkspaceToBare`
 *  - `postReceive` 后台线程：`pullBareIntoWorkspace`（同样是 add/merge/commit）
 *  - 定时同步协程：又一轮 `syncAll`
 *
 * JGit 对同一仓库并发操作会抢 `index.lock`，抛
 * `JGitInternalException: Exception caught during execution of add command`。
 * 而这些调用点外面几乎都包着 `runCatching`（为了不让异常逃进 `viewModelScope`
 * 把进程带崩，坑 15），于是**失败被静默吞掉**：该提交的没提交，该镜像的没镜像。
 * 用户看到的就是「刚建的菜同步一下就没了」。
 *
 * 实测（见工程外 `_mesh_repro/MeshRepro4.java`）：30 个菜单文件、三路并发 commit
 * 外加一路 push，裸仓库里最终**一个都没有**。
 *
 * 用一把全局锁而不是每仓库一把：本进程只有 `repo/` 和 `node.git` 两个仓库，
 * 且几乎所有操作都要同时碰两者（工作区提交完立刻镜像裸仓库），
 * 分锁只会制造「持 A 等 B」的死锁面。串行化的代价是几十毫秒，家用场景无感。
 */
internal object GitLock {
    val mutex = Mutex()
}