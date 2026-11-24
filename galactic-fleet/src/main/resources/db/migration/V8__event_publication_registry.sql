-- Spring Modulith Event Publication Registry (ADR-0043).
--
-- An event published inside a transaction is stored here in that same transaction and delivered to
-- its @ApplicationModuleListener after the commit, on another thread. A row with completion_date
-- IS NULL is a publication whose listener has not finished (yet, or because it failed).
--
-- Hibernate runs with ddl-auto=none, so the tables the library's JPA entities map to are created
-- here. Names and columns follow org.springframework.modulith.events.jpa.JpaEventPublication (1.4.x).

CREATE TABLE event_publication (
    id               UUID                     NOT NULL,
    listener_id      TEXT                     NOT NULL,
    event_type       TEXT                     NOT NULL,
    serialized_event TEXT                     NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- Incomplete publications are looked up by completion_date IS NULL, oldest first.
CREATE INDEX ix_event_publication_incomplete
    ON event_publication (publication_date)
    WHERE completion_date IS NULL;

-- Used only with spring.modulith.events.completion-mode=archive; the entity is mapped either way.
CREATE TABLE event_publication_archive (
    id               UUID                     NOT NULL,
    listener_id      TEXT                     NOT NULL,
    event_type       TEXT                     NOT NULL,
    serialized_event TEXT                     NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
