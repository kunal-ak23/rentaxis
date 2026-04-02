# Key Contacts — Design Document

**Date:** 2026-04-02
**Status:** Approved

## Overview

Add a "Key Contacts" section to the property overview page showing essential service contacts (plumber, electrician, hospital, etc.) visible to all users associated with the property.

## Database

New `property_contacts` table:
- `id` UUID PK
- `property_id` UUID FK → properties(id)
- `tenant_id` UUID (multi-tenant isolation)
- `category` VARCHAR — enum: PLUMBER, ELECTRICIAN, HANDYMAN, SECURITY, HOSPITAL_CLINIC, PHARMACY, BUILDING_MAINTENANCE, CIVIL_DEFENSE, OTHER
- `custom_label` VARCHAR — only when category = OTHER
- `name` VARCHAR — contact person/business name
- `phone` VARCHAR
- `email` VARCHAR (nullable)
- `address` VARCHAR (nullable)
- `notes` VARCHAR (nullable)
- `sort_order` INT
- `created_at`, `updated_at` TIMESTAMP

Migration: `17-property-contacts.yaml`

## API

- `GET /api/v1/properties/{propertyId}/contacts` — all authenticated roles
- `POST /api/v1/properties/{propertyId}/contacts` — TENANT_ADMIN, PROPERTY_MANAGER
- `PUT /api/v1/properties/{propertyId}/contacts/{id}` — TENANT_ADMIN, PROPERTY_MANAGER
- `DELETE /api/v1/properties/{propertyId}/contacts/{id}` — TENANT_ADMIN, PROPERTY_MANAGER

## Frontend

- Key Contacts card below Property Manager on Overview tab
- 3-column grid of contact cards with category icon, name, phone, email, address, notes
- Add/Edit via modal form with category dropdown
- Edit/Delete visible only to TENANT_ADMIN + PROPERTY_MANAGER
- All roles can view

## Categories

PLUMBER, ELECTRICIAN, HANDYMAN, SECURITY, HOSPITAL_CLINIC, PHARMACY, BUILDING_MAINTENANCE, CIVIL_DEFENSE, OTHER (with custom_label)
