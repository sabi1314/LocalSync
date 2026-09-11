package dev.localsync.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class BilibiliSessionStore {
    private static final long MAX_CONFIG_BYTES = 32_768L;
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

    private final Path path;

    BilibiliSessionStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    Optional<String> load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("账户配置路径不是普通文件");
        }
        if (Files.size(path) > MAX_CONFIG_BYTES) {
            throw new IOException("账户配置文件过大");
        }
        String encoded = Files.readString(path, StandardCharsets.UTF_8);
        try {
            return Optional.of(BilibiliAccountData.decodeStoredCookie(encoded));
        } catch (RuntimeException error) {
            throw new IOException("账户配置格式无效", error);
        }
    }

    void save(String normalizedCookie) throws IOException {
        String cookie = BilibiliAccountData.normalizeCookieHeader(normalizedCookie);
        if (!BilibiliAccountData.hasAuthenticatedCookie(cookie)) {
            throw new IOException("没有可保存的登录 Cookie");
        }

        Path parent = path.getParent();
        if (parent == null) throw new IOException("账户配置目录无效");
        Files.createDirectories(parent);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("拒绝覆盖符号链接账户配置");
        }

        Path temporary = Files.createTempFile(parent, ".localsync-bilibili-", ".tmp");
        try {
            restrictToOwner(temporary);
            String encoded = BilibiliAccountData.encodeStoredCookie(
                cookie, System.currentTimeMillis() / 1_000L);
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void clear() throws IOException {
        Files.deleteIfExists(path);
    }

    Path path() {
        return path;
    }

    private static void restrictToOwner(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file,
            PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(OWNER_ONLY);
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(file,
            AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) return;
        UserPrincipal owner;
        try {
            owner = file.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException | RuntimeException ignored) {
            owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
        }
        AclEntry ownerAccess = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
        acl.setAcl(List.of(ownerAccess));
    }
}
