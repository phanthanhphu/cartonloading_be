package org.bsl.cartonloading.model;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One small normalized JPEG per account; never embed binary data in the users collection. */
@Document(collection = AppConstants.Collection.USER_AVATARS)
public class UserAvatar {
    @Id
    private String userId;
    private byte[] jpeg;
    private LocalDateTime updatedAt;

    public UserAvatar() { }
    public UserAvatar(String userId, byte[] jpeg) {
        this.userId = userId;
        this.jpeg = jpeg;
        this.updatedAt = LocalDateTime.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public byte[] getJpeg() { return jpeg; }
    public void setJpeg(byte[] jpeg) { this.jpeg = jpeg; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
