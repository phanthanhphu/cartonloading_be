package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.model.UserAvatar;
import org.bsl.cartonloading.repository.UserAvatarRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;

/** Private user avatars; self-read, but Admin-only writes by targeted user ID. */
@Service
public class UserAvatarService {
    public static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    public static final int MAX_IMAGE_EDGE = 4096;
    public static final int AVATAR_PIXELS = 256;

    private final UserRepository users;
    private final UserAvatarRepository avatars;
    private final AuditLogService audit;

    public UserAvatarService(UserRepository users, UserAvatarRepository avatars, AuditLogService audit) {
        this.users = users;
        this.avatars = avatars;
        this.audit = audit;
    }

    public Optional<byte[]> readOwnAvatar() {
        return avatars.findById(currentUser().getId()).map(UserAvatar::getJpeg);
    }

    /** The controller also requires Admin, but enforce it here to protect service callers. */
    private void requireAdmin() {
        if (!RequestActor.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Admin can manage user photos");
        }
    }

    public Optional<byte[]> readUserAvatar(String userId) {
        requireAdmin();
        requireTargetUser(userId);
        return avatars.findById(userId).map(UserAvatar::getJpeg);
    }

    /** Normalized bytes are passed only after image validation, BEFORE persisting the user. */
    public void saveUserAvatar(String userId, byte[] normalizedJpeg) {
        requireAdmin();
        requireTargetUser(userId);
        if (normalizedJpeg == null || normalizedJpeg.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar data is required");
        }
        avatars.save(new UserAvatar(userId, normalizedJpeg));
        audit.log(RequestActor.current(), AppConstants.AuditAction.UPDATE,
                AppConstants.AuditResource.USER, userId, "Administrator updated user avatar", null, null);
    }

    public void removeUserAvatar(String userId) {
        requireAdmin();
        requireTargetUser(userId);
        avatars.deleteById(userId);
        audit.log(RequestActor.current(), AppConstants.AuditAction.UPDATE,
                AppConstants.AuditResource.USER, userId, "Administrator removed user avatar", null, null);
    }

    private void requireTargetUser(String userId) {
        if (userId == null || userId.isBlank() || !users.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
        }
        return users.findByEmailIgnoreCase(auth.getName()).filter(User::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again"));
    }

    /** Whitelist JPEG/PNG, verify decoded format and dimensions, strip metadata, crop and re-encode. */
    public static byte[] normalizeAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a JPG or PNG photo");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile photo must be 5 MB or smaller");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/jpg".equals(mime))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPG and PNG photos are allowed");
        }
        try {
            byte[] raw = file.getBytes();
            if (raw.length == 0 || raw.length > MAX_UPLOAD_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile photo must be 5 MB or smaller");
            }
            try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
                if (stream == null) throw invalidImage();
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalidImage();
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    boolean valid = (format.equals("jpeg") || format.equals("jpg"))
                            ? (mime.equals("image/jpeg") || mime.equals("image/jpg"))
                            : format.equals("png") && mime.equals("image/png");
                    if (!valid) throw invalidImage();
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image dimensions must be between 1 and 4096 pixels");
                    }
                    BufferedImage original = reader.read(0);
                    if (original == null) throw invalidImage();
                    int side = Math.min(original.getWidth(), original.getHeight());
                    int x = (original.getWidth() - side) / 2;
                    int y = (original.getHeight() - side) / 2;
                    BufferedImage avatar = new BufferedImage(AVATAR_PIXELS, AVATAR_PIXELS, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = avatar.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, AVATAR_PIXELS, AVATAR_PIXELS);
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        graphics.drawImage(original, 0, 0, AVATAR_PIXELS, AVATAR_PIXELS,
                                x, y, x + side, y + side, null);
                    } finally {
                        graphics.dispose();
                    }
                    return writeJpeg(avatar);
                } finally {
                    reader.dispose();
                }
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw invalidImage();
        }
    }

    private static byte[] writeJpeg(BufferedImage avatar) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.84f);
            }
            writer.write(null, new IIOImage(avatar, null, null), params);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static ResponseStatusException invalidImage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "This photo is not a valid JPG or PNG image");
    }
}
