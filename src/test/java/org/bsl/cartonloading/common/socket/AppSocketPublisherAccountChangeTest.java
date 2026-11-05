package org.bsl.cartonloading.common.socket;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AppSocketPublisherAccountChangeTest {
    @Test
    void sendsOnlyToAffectedPrincipalWithMatchingUserIdAndVersion() {
        SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
        AppSocketPublisher publisher = new AppSocketPublisher(messages, mock(MongoTemplate.class));

        publisher.accountChanged("userb@example.test", "user-b-id", 9L, false,
                List.of("Tài khoản đã bị vô hiệu hóa (Disabled)"));

        @SuppressWarnings("unchecked")
        var noticeCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(messages).convertAndSendToUser(eq("userb@example.test"),
                eq("/queue/account-change"), noticeCaptor.capture());
        Map<?, ?> notice = noticeCaptor.getValue();
        assertEquals("ACCOUNT", notice.get("module"));
        assertEquals("ACCOUNT_CHANGED", notice.get("action"));
        assertEquals("user-b-id", notice.get("userId"));
        assertEquals(9L, notice.get("version"));
        assertEquals(false, notice.get("enabled"));
        verifyNoMoreInteractions(messages); // no Admin or other User broadcast
    }

    @Test
    void noDestinationForInvalidUser() {
        SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
        AppSocketPublisher publisher = new AppSocketPublisher(messages, mock(MongoTemplate.class));
        publisher.accountChanged("", "user-id", 2L, true, List.of());
        publisher.accountChanged("person@example.test", "", 2L, true, List.of());
        verifyNoInteractions(messages);
    }
}
