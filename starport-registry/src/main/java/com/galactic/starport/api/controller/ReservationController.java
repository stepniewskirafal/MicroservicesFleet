package com.galactic.starport.api.controller;

import com.galactic.starport.api.dto.ReservationCreateRequest;
import com.galactic.starport.api.dto.ReservationResponse;
import com.galactic.starport.api.mapper.ReservationMapper;
import com.galactic.starport.application.command.ReserveBayCommand;
import com.galactic.starport.application.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ReservationController implements ReservationApi {
    private final ReservationService service;
    private final ReservationMapper mapper;

    @PostMapping("/{code}/reservations")
    public ResponseEntity<ReservationResponse> create(
            @PathVariable String code, @Valid @RequestBody ReservationCreateRequest req) {
        ReserveBayCommand command = mapper.toCommand(code, req);

        return service.reserveBay(command)
                .map(mapper::toResponse)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .orElseThrow(() -> new RuntimeException("Failed to create reservation"));
    }
}
