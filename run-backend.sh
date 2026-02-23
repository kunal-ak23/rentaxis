#!/bin/bash
# Load environment variables from .env.backend
export $(grep -v '^#' .env.backend | xargs)

# Run the Spring Boot application using Gradle
cd backend
./gradlew bootRun
