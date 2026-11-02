package org.ipz.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.controller.mapper.OccupancyControllerMapper;
import org.ipz.backend.controller.mapper.TrainControllerMapper;
import org.ipz.backend.exception.dto.ErrorResponseDto;
import org.ipz.backend.exception.dto.ValidationErrorResponseDto;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.TrainOccupancy;
import org.ipz.backend.model.dto.TrainFilter;
import org.ipz.backend.model.dto.TrainFilterRequestDto;
import org.ipz.backend.model.dto.TrainOccupancyResponseDto;
import org.ipz.backend.model.dto.TrainResponseDto;
import org.ipz.backend.service.OccupancyService;
import org.ipz.backend.service.TrainService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Trains Catalog & Seat Occupancy",
        description = "Train catalog browsing, filtering, seeding, and real-time seat occupancy")
@ApiResponses(value = {
        @ApiResponse(responseCode = "400", description = "Invalid validation in query parameters",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
})
@RestController
@RequestMapping("/api/trains")
@RequiredArgsConstructor
@Slf4j
public class TrainController {

    private final TrainService trainService;
    private final OccupancyService occupancyService;
    private final TrainControllerMapper trainMapper;
    private final OccupancyControllerMapper occupancyMapper;

    @Operation(summary = "Get a list of trains",
            description = "Fetches all trains or filters them by search term, city, date, max price, Wi-Fi availability, and pagination")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Invalid validation in query parameters",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Successfully fetched trains list")
    })
    @SecurityRequirements()
    @GetMapping
    public ResponseEntity<List<TrainResponseDto>> getTrains(
            @Valid @ParameterObject @ModelAttribute TrainFilterRequestDto requestDto) {
        log.info("Received request to fetch trains with filters: {}", requestDto);

        TrainFilter filter = trainMapper.toDomainFilter(requestDto);
        List<Train> trains = trainService.findTrains(filter);
        List<TrainResponseDto> response = trainMapper.toDtoList(trains);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get distinct cities",
            description = "Returns distinct departure and arrival cities for filtering (cached in Redis)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully fetched distinct cities")
    })
    @SecurityRequirements()
    @GetMapping("/cities")
    public ResponseEntity<List<String>> getCities() {
        log.info("Received request to fetch distinct train cities");

        List<String> cities = trainService.findAllCities();

        return ResponseEntity.ok(cities);
    }

    @Operation(summary = "Get train by ID",
            description = "Returns a single train by its unique ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "404", description = "Train not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Successfully fetched train details")
    })
    @SecurityRequirements()
    @GetMapping("/{id}")
    public ResponseEntity<TrainResponseDto> getTrainById(@PathVariable Integer id) {
        log.info("Received request to fetch train with id: {}", id);

        Train train = trainService.findById(id);
        TrainResponseDto response = trainMapper.toDto(train);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get occupied seats for a train and date",
            description = "Returns all occupied and temporarily locked seats for a specific train and departure date")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "404", description = "Train not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Successfully fetched seat occupancy")
    })
    @SecurityRequirements()
    @GetMapping("/{trainId}/occupancy")
    public ResponseEntity<TrainOccupancyResponseDto> getTrainOccupancy(
            @PathVariable Integer trainId,
            @Parameter(description = "Departure date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("Received request for seat occupancy: trainId={}, date={}", trainId, date);

        TrainOccupancy occupancy = occupancyService.getTrainOccupancy(trainId, date);
        TrainOccupancyResponseDto response = occupancyMapper.toDto(occupancy);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Subscribe to real-time seat occupancy updates (SSE)",
            description = "Streams real-time occupied seat updates via Server-Sent Events for a specific train and date")
    @SecurityRequirements()
    @GetMapping(value = "/{trainId}/occupancy/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTrainOccupancy(
            @PathVariable Integer trainId,
            @Parameter(description = "Departure date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("Opening SSE occupancy stream for trainId={}, date={}", trainId, date);

        return occupancyService.subscribeOccupancyStream(trainId, date);
    }

    @Operation(summary = "Seed initial trains data",
            description = "Seeds PostgreSQL with initial trains data if the trains table is empty and invalidates Redis caches")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Trains seeded successfully")
    })
    @SecurityRequirements()
    @PostMapping("/seed")
    public ResponseEntity<List<TrainResponseDto>> seedTrains() {
        log.info("Received request to seed initial trains data");

        List<Train> trains = trainService.seedInitialTrains();
        List<TrainResponseDto> response = trainMapper.toDtoList(trains);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
