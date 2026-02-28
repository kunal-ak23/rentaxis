package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.Language;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "renters")
@Getter
@Setter
public class Renter extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_language")
    private Language primaryLanguage = Language.EN;

    @Column(name = "user_id")
    private UUID userId;
}
