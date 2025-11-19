package com.galactic.starport.controller;

import com.galactic.starport.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/starports")
@Tag(name = "API: galactic starport")
@Slf4j
public class ReservationController {
    private final ReservationService service;
    private final ReservationWebMapper mapper;

    @Operation(summary = "Create a new reservation")
    @PostMapping(
            path = "/{code}/reservations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationResponse> create(
            @PathVariable String code, @Valid @RequestBody ReservationCreateRequest req) {
        log.info("Received reservation create request for starport {}: {}", code, req);
        var cmd = mapper.toCommand(code, req);
        service.reserveBay(cmd);
        return ResponseEntity.ok(ReservationResponse.builder().build());
        /*service.reserveBay(cmd)
        .map(domain -> ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(code, domain)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());*/
    }
}
