package hk.flynt.mcms.api.dto;

import java.util.List;

/** /chat/getMessages 返回：{ list, hasMore }。 */
public record MessagePage(List<ChatMessage> list, boolean hasMore) {
}