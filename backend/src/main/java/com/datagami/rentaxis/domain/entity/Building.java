package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "buildings")
@Getter
@Setter
public class Building extends BaseTenantEntity {

    // Serialized in responses, never accepted from a request body.
    //
    // These six endpoints bind the JPA entity directly as the request DTO, so
    // every settable property was client-writable. A POST carrying an id made
    // Hibernate treat repository.save() as an update to that row rather than an
    // insert, turning "create" into "silently overwrite something else in my
    // tenant". READ_ONLY closes that without changing any response shape.
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    private Integer floors;
}
