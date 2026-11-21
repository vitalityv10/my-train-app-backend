package org.ipz.backend.service;

import org.ipz.backend.model.dto.AiChatRequestDto;
import org.ipz.backend.model.dto.AiChatResponseDto;
import org.ipz.backend.model.dto.AiSmartSearchRequestDto;
import org.ipz.backend.model.dto.AiSmartSearchResponseDto;

public interface AiAssistantService {

    AiChatResponseDto chat(AiChatRequestDto requestDto, String firebaseUid);

    void clearChatHistory(String sessionId);

    AiSmartSearchResponseDto smartSearch(AiSmartSearchRequestDto requestDto);
}
