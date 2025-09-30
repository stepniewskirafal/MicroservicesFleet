package com.galactic.starport.architecture;

public abstract class ArchUnitSpecification {

    // Pakiety
    protected static final String PKG_API = "com.galactic.starport.api..";
    protected static final String PKG_APPLICATION = "com.galactic.starport.application..";
    protected static final String PKG_DOMAIN = "com.galactic.starport.domain..";
    protected static final String PKG_INFRASTRUCTURE = "com.galactic.starport.infrastructure..";

    // Framework packages (zakazane w domenie)
    protected static final String PKG_SPRING = "org.springframework..";
    protected static final String PKG_SPRING_WEB = "org.springframework.web..";
    protected static final String PKG_SPRING_DATA = "org.springframework.data..";
    protected static final String PKG_SPRING_HTTP = "org.springframework.http..";
    protected static final String PKG_JAKARTA_PERSISTENCE = "jakarta.persistence..";
    protected static final String PKG_JAVAX_PERSISTENCE = "javax.persistence..";

    // Adnotacje
    protected static final String ANNOTATION_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    protected static final String ANNOTATION_SERVICE = "org.springframework.stereotype.Service";
    protected static final String ANNOTATION_REPOSITORY = "org.springframework.stereotype.Repository";
    protected static final String ANNOTATION_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
}
