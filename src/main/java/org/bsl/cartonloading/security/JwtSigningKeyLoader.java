package org.bsl.cartonloading.security;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves the JWT signing secret without embedding any shared credentials in source code.
 * Environment/property secret takes precedence. A single-machine development installation
 * can generate and persist a random secret under the current OS user's home directory.
 */
public final class JwtSigningKeyLoader {
    private static final int MIN_SECRET_BYTES = 32;
    private static final int GENERATED_BYTES = 48;
    private static final Set<PosixFilePermission> DIR_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private JwtSigningKeyLoader() {
    }

    public static String resolve(String configuredSecret, Path localFile, boolean mayGenerate) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            return validate(configuredSecret, "JWT_SECRET");
        }

        Path target = localFile.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalStateException("JWT local secret file must have a parent directory");
        }
        try {
            // The secret is outside the source tree (default: ~/.cartonloading/jwt-secret.key).
            Files.createDirectories(parent);
            restrictPosixPermissions(parent, DIR_PERMISSIONS);

            // Lock makes the first-run generation safe if two independent Java processes start together.
            Path lockPath = parent.resolve(".jwt-secret.lock");
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                restrictPosixPermissions(lockPath, FILE_PERMISSIONS);

                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException("JWT secret path is not a regular file: " + target);
                    }
                    restrictPosixPermissions(target, FILE_PERMISSIONS);
                    return validate(Files.readString(target, StandardCharsets.UTF_8).strip(),
                            "local JWT secret file " + target);
                }

                if (!mayGenerate) {
                    throw new IllegalStateException("JWT_SECRET is not configured and the local JWT secret "
                            + "file does not exist. Configure JWT_SECRET for production/shared deployments.");
                }
                byte[] bytes = new byte[GENERATED_BYTES];
                new SecureRandom().nextBytes(bytes);
                String generated = Base64.getEncoder().encodeToString(bytes);
                Files.writeString(target, generated + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrictPosixPermissions(target, FILE_PERMISSIONS);
                return generated;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read/create persistent JWT signing secret at " + target
                    + ". Check the application's OS user permissions.", e);
        }
    }

    private static String validate(String secret, String source) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(source + " must contain at least 32 UTF-8 bytes");
        }
        return secret;
    }

    private static void restrictPosixPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // NTFS/Windows uses the current user's inherited ACLs.
        }
    }
}
