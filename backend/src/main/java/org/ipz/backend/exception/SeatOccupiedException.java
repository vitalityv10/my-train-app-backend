package org.ipz.backend.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class SeatOccupiedException extends RuntimeException {

    private final List<String> conflictingSeats;

    public SeatOccupiedException(String message, List<String> conflictingSeats) {
        super(message);
        this.conflictingSeats = conflictingSeats;
    }
}
