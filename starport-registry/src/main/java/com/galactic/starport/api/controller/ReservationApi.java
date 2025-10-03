package com.galactic.starport.api.controller;

import com.galactic.starport.api.dto.ReservationCreateRequest;
import com.galactic.starport.api.dto.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "API: galactic starport")
@RequestMapping("/api/v1/starports")
public interface ReservationApi {
    @Operation(summary = "Create a new reservation")
    @PostMapping(
            path = "/{code}/reservations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<ReservationResponse> create(
            @PathVariable String code, @Valid @RequestBody ReservationCreateRequest req);
}
