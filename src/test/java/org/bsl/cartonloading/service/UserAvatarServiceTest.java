package org.bsl.cartonloading.service;

import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.model.UserAvatar;
import org.bsl.cartonloading.repository.UserAvatarRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAvatarServiceTest {
    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    private static MockMultipartFile image(String format, String mime, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, stream));
        return new MockMultipartFile("file", "avatar." + format, mime, stream.toByteArray());
    }

    @Test void pngIsNormalizedToA256PixelJpeg() throws Exception {
        byte[] jpeg = UserAvatarService.normalizeAvatar(image("png", "image/png", 640, 360));
        assertEquals(0xff, jpeg[0] & 0xff);
        assertEquals(0xd8, jpeg[1] & 0xff);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(256, result.getWidth());
        assertEquals(256, result.getHeight());
        assertTrue(jpeg.length < 250_000);
    }

    @Test void jpegIsAccepted() throws Exception {
        byte[] bytes = UserAvatarService.normalizeAvatar(image("jpeg", "image/jpeg", 500, 500));
        assertTrue(bytes.length > 100);
    }

    @Test void rejectingSpoofedOrInvalidImagePreservesSecurity() throws Exception {
        MockMultipartFile spoofed = new MockMultipartFile("file", "malicious.png", "image/png",
                image("jpeg", "image/jpeg", 80, 80).getBytes());
        ResponseStatusException mismatch = assertThrows(ResponseStatusException.class,
                () -> UserAvatarService.normalizeAvatar(spoofed));
        assertEquals(HttpStatus.BAD_REQUEST, mismatch.getStatusCode());
        MockMultipartFile garbage = new MockMultipartFile("file", "empty.png", "image/png", "not-an-image".getBytes());
        assertThrows(ResponseStatusException.class, () -> UserAvatarService.normalizeAvatar(garbage));
        MockMultipartFile svg = new MockMultipartFile("file", "photo.svg", "image/svg+xml", "<svg/>".getBytes());
        assertThrows(ResponseStatusException.class, () -> UserAvatarService.normalizeAvatar(svg));
    }

    @Test void rejectsOversizedImageAndLargeDimensions() throws Exception {
        MockMultipartFile tooManyBytes = new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                new byte[(int) UserAvatarService.MAX_UPLOAD_BYTES + 1]);
        assertThrows(ResponseStatusException.class, () -> UserAvatarService.normalizeAvatar(tooManyBytes));
        MockMultipartFile giantWidth = image("png", "image/png", 4097, 1);
        assertThrows(ResponseStatusException.class, () -> UserAvatarService.normalizeAvatar(giantWidth));
    }

    @Test void regularUserCannotReadOrModifyOtherUsersAvatar() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UserAvatarRepository avatars = mock(UserAvatarRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("regular@example.test", "", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_USER"))));
        UserAvatarService service = new UserAvatarService(users, avatars, audit);
        byte[] photo = UserAvatarService.normalizeAvatar(image("png", "image/png", 100, 100));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.readUserAvatar("someone-else")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.saveUserAvatar("someone-else", photo)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.removeUserAvatar("someone-else")).getStatusCode());
        verifyNoInteractions(avatars);
    }

    @Test void adminCanManageSelectedUserAvatar() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UserAvatarRepository avatars = mock(UserAvatarRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin@example.test", "", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(users.existsById("user-42")).thenReturn(true);
        UserAvatarService service = new UserAvatarService(users, avatars, audit);
        byte[] photo = UserAvatarService.normalizeAvatar(image("png", "image/png", 100, 100));
        service.saveUserAvatar("user-42", photo);
        verify(avatars).save(argThat(row -> "user-42".equals(row.getUserId()) && row.getJpeg().length > 0));
        service.removeUserAvatar("user-42");
        verify(avatars).deleteById("user-42");
        verify(users, atLeastOnce()).existsById("user-42");
    }

    @Test void adminCannotAttachPhotoToMissingUser() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UserAvatarRepository avatars = mock(UserAvatarRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin@example.test", "", java.util.List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(users.existsById("missing")).thenReturn(false);
        UserAvatarService service = new UserAvatarService(users, avatars, audit);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.saveUserAvatar("missing", new byte[]{1,2,3})).getStatusCode());
        verifyNoInteractions(avatars);
    }
}
