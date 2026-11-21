package org.ipz.backend.service;

import org.ipz.backend.model.dto.AiChatRequestDto;
import org.ipz.backend.model.dto.AiChatResponseDto;
import org.ipz.backend.model.dto.AiSmartSearchRequestDto;
import org.ipz.backend.model.dto.AiSmartSearchResponseDto;

public interface AiAssistantService {

    default AiChatResponseDto chat(AiChatRequestDto requestDto, String firebaseUid) {
        return chat(requestDto, firebaseUid, null, null);
    }

    AiChatResponseDto chat(AiChatRequestDto requestDto, String firebaseUid, String userEmail, String userName);

    void clearChatHistory(String sessionId);

    AiSmartSearchResponseDto smartSearch(AiSmartSearchRequestDto requestDto);
}
