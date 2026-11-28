package com.pubbge.liharness.infrastructure.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 原子写文件：先写到同目录的临时文件，再一次性改名替换。
 *
 * <p>为什么：直接覆盖写，写到一半断电或崩溃，文件就只剩半截，下次启动读出来是坏的。
 * 同一个目录里的改名在主流文件系统上是原子的：读的人要么看到旧文件，要么看到新文件。
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * @param ownerOnly true 时文件权限是 600（只有自己能读写），放 Key 的文件用。不支持 POSIX 权限的系统忽略
     */
    public static void write(Path file, String text, boolean ownerOnly) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        // 临时文件一创建出来就是 600：先写内容再改权限的话，中间有一小段时间别人能读到
        Path tmp = ownerOnly && posix
            ? Files.createTempFile(dir, "." + file.getFileName(), ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            : Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
