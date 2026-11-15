package com.rikkaminis.app.sandbox

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Clear a rootfs while retaining L2S targets named by host-side bind mounts. */
internal fun clearRootfsForReinstall(rootfsDir: File) {
    val root = rootfsDir.absoluteFile.toPath().normalize()
    val backing = root.resolve(L2S_BACKING_DIR_NAME)
    if (Files.isSymbolicLink(root)) throw IOException("Rootfs path is a symlink: $rootfsDir")
    if (!Files.exists(root, NOFOLLOW_LINKS)) {
        if (!rootfsDir.mkdirs() && !rootfsDir.isDirectory) {
            throw IOException("Could not create rootfs directory: $rootfsDir")
        }
        return
    }
    if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
        throw IOException("Rootfs path is not a directory: $rootfsDir")
    }

    val preserveBacking = Files.exists(backing, NOFOLLOW_LINKS)
    if (preserveBacking &&
        (Files.isSymbolicLink(backing) || !Files.isDirectory(backing, NOFOLLOW_LINKS) || backing.parent != root)
    ) {
        throw IOException("Refusing to reset an unsafe L2S backing path: $backing")
    }

    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (preserveBacking && dir == backing) FileVisitResult.SKIP_SUBTREE
            else FileVisitResult.CONTINUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            if (preserveBacking && dir == backing) return FileVisitResult.CONTINUE
            if (dir != root) Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

internal const val L2S_BACKING_DIR_NAME = ".l2s-backing"
