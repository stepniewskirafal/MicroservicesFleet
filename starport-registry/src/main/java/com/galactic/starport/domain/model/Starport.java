package com.galactic.starport.domain.model;

import jakarta.persistence.*;
import java.util.List;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
        name = "starport",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_starport_name", columnNames = "name"),
            @UniqueConstraint(name = "uk_starport_code", columnNames = "code")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Starport {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 64)
    private String code;

    @OneToMany(mappedBy = "starport", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DockingBay> bays;
}
