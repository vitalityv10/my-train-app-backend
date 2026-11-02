package org.ipz.backend.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TrainType {
    DAY("Day"),
    NIGHT("Night");

    @JsonValue
    private final String value;

    @JsonCreator
    public static TrainType fromValue(String value) {
        for (TrainType type : TrainType.values()) {
            if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown train type: " + value);
    }
}
