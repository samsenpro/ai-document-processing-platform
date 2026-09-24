package com.documind.organization;

import com.documind.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    protected Organization() {
        // JPA
    }

    public Organization(String name) {
        super(UUID.randomUUID());
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
