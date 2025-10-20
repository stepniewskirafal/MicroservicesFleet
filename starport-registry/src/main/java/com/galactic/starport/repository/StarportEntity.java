package com.galactic.starport.repository;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "starport")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class StarportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "starport_id_seq_gen")
    @SequenceGenerator(name = "starport_id_seq_gen", sequenceName = "starport_id_seq", allocationSize = 10)
    @Column(name = "id")
    private Long id;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "starport", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DockingBayEntity> dockingBays;
}
