# Implementation Plan: Build Task Management System

## Overview

This implementation plan breaks down the build task management system into discrete coding steps that build incrementally. The approach focuses on database setup, endpoint refactoring, core service implementation, and comprehensive testing.

## Tasks

- [x] 1. Set up PostgreSQL database integration and schema
  - Add PostgreSQL JDBC driver and connection pooling dependencies to build.gradle
  - Create database configuration and connection management
  - Implement database schema initialization with tables and indexes
  - Add PostgreSQL service to docker-compose.yml
  - _Requirements: 5.1, 5.2, 5.3_

- [ ]* 1.1 Write unit tests for database connection and schema initialization
  - Test database connection establishment and error handling
  - Test schema creation and index setup
  - _Requirements: 5.2, 5.3_

- [x] 2. Create core data models and repositories
  - [x] 2.1 Implement Build and BuildTask entity classes
    - Create Build class with UUID, metadata, and status tracking
    - Create BuildTask class with task data, coordinates, and execution status
    - Create BoundingBox utility class for coordinate operations
    - _Requirements: 1.4, 2.4, 4.3_

  - [ ]* 2.2 Write property test for Build entity
    - **Property 3: Build Metadata Completeness**
    - **Validates: Requirements 1.4**

  - [x] 2.3 Implement BuildRepository with CRUD operations
    - Create repository interface and PostgreSQL implementation
    - Implement build creation, retrieval, and status updates
    - Add location-based query methods with spatial indexing
    - _Requirements: 1.1, 1.2, 1.3, 4.1, 4.4_

  - [ ]* 2.4 Write property test for BuildRepository
    - **Property 2: Build Persistence Round Trip**
    - **Validates: Requirements 1.2, 1.3**

  - [x] 2.5 Implement TaskRepository with queue operations
    - Create repository for task CRUD and queue management
    - Implement task ordering and batch operations
    - Add coordinate tracking and spatial queries
    - _Requirements: 2.1, 2.3, 2.5, 4.3_

  - [ ]* 2.6 Write property test for TaskRepository
    - **Property 4: Task Queue Ordering**
    - **Validates: Requirements 2.1, 2.3**

- [x] 3. Checkpoint - Ensure database layer tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. Refactor existing endpoints to support programmatic calls
  - [ ] 4.1 Extract BlocksEndpointCore from BlocksEndpoint
    - Create BlocksEndpointCore class with core block operations
    - Refactor existing HTTP handlers to delegate to core methods
    - Ensure all block operations work without Javalin Context
    - _Requirements: 7.1, 7.3_

  - [ ]* 4.2 Write compatibility tests for BlocksEndpoint refactoring
    - Test that HTTP endpoints produce identical results before/after refactoring
    - Test that core methods work without HTTP context
    - _Requirements: 7.1, 7.3_

  - [ ] 4.3 Extract PrefabEndpointCore from PrefabEndpoint
    - Create PrefabEndpointCore class with core prefab operations
    - Refactor existing HTTP handlers to delegate to core methods
    - Ensure all prefab operations work without Javalin Context
    - _Requirements: 7.2, 7.4_

  - [ ]* 4.4 Write compatibility tests for PrefabEndpoint refactoring
    - Test that HTTP endpoints produce identical results before/after refactoring
    - Test that core methods work without HTTP context
    - _Requirements: 7.2, 7.4_

  - [ ]* 4.5 Write property test for endpoint refactoring compatibility
    - **Property 18: Endpoint Refactoring Compatibility**
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5**

- [ ] 5. Implement TaskExecutor for build execution
  - [ ] 5.1 Create TaskExecutor class
    - Implement task execution using refactored endpoint cores
    - Add coordinate calculation for each task type
    - Implement task status tracking and error handling
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 4.3_

  - [ ]* 5.2 Write property test for TaskExecutor
    - **Property 10: Endpoint Integration Equivalence**
    - **Validates: Requirements 3.5**

  - [ ] 5.3 Implement task data validation
    - Create validators for each task type against endpoint schemas
    - Add comprehensive input validation with detailed error messages
    - _Requirements: 2.2_

  - [ ]* 5.4 Write property test for task validation
    - **Property 5: Task Validation Consistency**
    - **Validates: Requirements 2.2**

- [ ] 6. Implement BuildService for core business logic
  - [ ] 6.1 Create BuildService class
    - Implement build creation with unique ID generation
    - Add task queue management operations
    - Implement build execution orchestration
    - _Requirements: 1.1, 1.2, 2.1, 2.5, 3.1, 3.4_

  - [ ]* 6.2 Write property test for BuildService build operations
    - **Property 1: Build ID Uniqueness**
    - **Validates: Requirements 1.1**

  - [ ]* 6.3 Write property test for BuildService task operations
    - **Property 6: Task Queue Persistence**
    - **Validates: Requirements 2.5**

  - [ ] 6.4 Implement LocationQueryService
    - Create service for location-based build queries
    - Implement spatial intersection logic
    - Add chronological ordering for overlapping builds
    - _Requirements: 4.1, 4.2, 4.4_

  - [ ]* 6.5 Write property test for LocationQueryService
    - **Property 11: Location Query Intersection**
    - **Validates: Requirements 4.1**

- [ ] 7. Checkpoint - Ensure core services tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 8. Implement HTTP API endpoints
  - [ ] 8.1 Create BuildTaskEndpoint class
    - Implement POST /api/builds for build creation
    - Implement GET /api/builds/{id} for build retrieval
    - Follow existing endpoint patterns and error handling
    - _Requirements: 6.1, 6.4, 6.5_

  - [ ] 8.2 Add task queue management endpoints
    - Implement POST /api/builds/{id}/tasks for adding tasks
    - Implement GET /api/builds/{id}/tasks for queue retrieval
    - Implement PUT /api/builds/{id}/tasks for queue updates
    - _Requirements: 6.1, 6.4, 6.5_

  - [ ] 8.3 Add build execution and query endpoints
    - Implement POST /api/builds/{id}/execute for build execution
    - Implement POST /api/builds/query-location for location queries
    - Add comprehensive error handling and validation
    - _Requirements: 6.2, 6.3, 6.4, 6.5_

  - [ ]* 8.4 Write integration tests for HTTP endpoints
    - Test all endpoint request/response cycles
    - Test error handling and validation
    - _Requirements: 6.1, 6.2, 6.3_

  - [ ]* 8.5 Write property test for API error handling
    - **Property 16: API Error Response Consistency**
    - **Validates: Requirements 6.4**

- [ ] 9. Integrate BuildTaskEndpoint into APIServer
  - [ ] 9.1 Register BuildTaskEndpoint in APIServer
    - Add BuildTaskEndpoint instantiation to APIServer.start()
    - Ensure proper dependency injection and initialization
    - _Requirements: 6.1, 6.2, 6.3_

  - [ ]* 9.2 Write integration test for complete system
    - Test end-to-end build creation → task addition → execution → query flow
    - Test system integration with existing endpoints
    - _Requirements: All requirements_

- [ ] 10. Add comprehensive property-based tests
  - [ ]* 10.1 Write remaining property tests for task execution
    - **Property 7: Task Execution Ordering**
    - **Validates: Requirements 3.1**

  - [ ]* 10.2 Write property tests for status tracking
    - **Property 8: Task Status Tracking**
    - **Validates: Requirements 3.2, 3.3**

  - [ ]* 10.3 Write property tests for build completion
    - **Property 9: Build Completion Status**
    - **Validates: Requirements 3.4**

  - [ ]* 10.4 Write property tests for location queries
    - **Property 12: Location Query Completeness**
    - **Validates: Requirements 4.2**
    - **Property 14: Overlapping Build Chronological Order**
    - **Validates: Requirements 4.4**

  - [ ]* 10.5 Write property tests for coordinate tracking
    - **Property 13: Coordinate Tracking Completeness**
    - **Validates: Requirements 4.3**

  - [ ]* 10.6 Write property tests for error handling
    - **Property 15: Database Error Handling**
    - **Validates: Requirements 5.4**

- [ ] 11. Final compatibility and regression testing
  - [ ]* 11.1 Write comprehensive compatibility tests
    - **Property 19: Existing API Functionality Preservation**
    - **Validates: Requirements 8.4**

  - [ ]* 11.2 Write property test for API pattern consistency
    - **Property 17: API Pattern Consistency**
    - **Validates: Requirements 6.5**

- [ ] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties
- Unit tests validate specific examples and edge cases
- The implementation builds incrementally: database → models → services → endpoints → integration