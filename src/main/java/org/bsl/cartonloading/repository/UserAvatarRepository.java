package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.UserAvatar;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Avatar ID is the authenticated account's User ID (never a supplied target ID). */
public interface UserAvatarRepository extends MongoRepository<UserAvatar, String> { }
