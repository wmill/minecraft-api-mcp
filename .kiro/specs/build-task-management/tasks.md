# Implementation Plan: Build Task Management System

## Overview

This implementation plan reflects the current state of the build task management system. Most core components have been implemented including database integration, service layer, HTTP API endpoints, and endpoint refactoring. The remaining work focuses on MCP server integration and comprehensive testing.

## Tasks

- [x] 1. Set up PostgreSQL database integration and schema
  - Add PostgreSQL JDBC driver and connection pooling dependencies to build.gradle
  - Create database configuration and connection management
  - Implement database schema initialization with tables and indexes
  - Add PostgreSQL service to docker-compose.yml
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 2. Create core data models and repositories
  - [x] 2.1 Implement Build and BuildTask entity classes
    - Create Build class with UUID, metadata, and status tracking
    - Create BuildTask class with task data, coordinates, and execution status
    - Create BoundingBox utility class for coordinate operations
    - _Requirements: 1.4, 2.4, 4.3_

  - [x] 2.2 Implement BuildRepository with CRUD operations
    - Create repository interface and PostgreSQL implementation
    - Implement build creation, retrieval, and status updates
    - Add location-based query methods with spatial indexing
    - _Requirements: 1.1, 1.2, 1.3, 4.1, 4.4_

  - [x] 2.3 Implement TaskRepository with queue operations
    - Create repository for task CRUD and queue management
    - Implement task ordering and batch operations
    - Add coordinate tracking and spatial queries
    - _Requirements: 2.1, 2.3, 2.5, 4.3_

- [x] 3. Refactor existing endpoints to support programmatic calls
  - [x] 3.1 Extract BlocksEndpointCore from BlocksEndpoint
    - Create BlocksEndpointCore class with core block operations
    - Refactor existing HTTP handlers to delegate to core methods
    - Ensure all block operations work without Javalin Context
    - _Requirements: 7.1, 7.3_

  - [x] 3.2 Extract PrefabEndpointCore from PrefabEndpoint
    - Create PrefabEndpointCore class with core prefab operations
    - Refactor existing HTTP handlers to delegate to core methods
    - Ensure all prefab operations work without Javalin Context
    - _Requirements: 7.2, 7.4_

- [x] 4. Implement core services and task execution
  - [x] 4.1 Create BuildService class
    - Implement build creation with unique ID generation
    - Add task queue management operations
    - Implement build execution orchestration
    - _Requirements: 1.1, 1.2, 2.1, 2.5, 3.1, 3.4_

  - [x] 4.2 Create TaskExecutor class
    - Implement task execution using refactored endpoint cores
    - Add coordinate calculation for each task type
    - Implement task status tracking and error handling
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 4.3_

  - [x] 4.3 Implement task data validation
    - Create TaskDataValidator for each task type against endpoint schemas
    - Add comprehensive input validation with detailed error messages
    - _Requirements: 2.2_

  - [x] 4.4 Implement LocationQueryService
    - Create service for location-based build queries
    - Implement spatial intersection logic
    - Add chronological ordering for overlapping builds
    - _Requirements: 4.1, 4.2, 4.4_

- [x] 5. Implement HTTP API endpoints
  - [x] 5.1 Create BuildTaskEndpoint class
    - Implement POST /api/builds for build creation
    - Implement GET /api/builds/{id} for build retrieval
    - Follow existing endpoint patterns and error handling
    - _Requirements: 6.1, 6.4, 6.5_

  - [x] 5.2 Add task queue management endpoints
    - Implement POST /api/builds/{id}/tasks for adding tasks
    - Implement GET /api/builds/{id}/tasks for queue retrieval
    - Implement PUT /api/builds/{id}/tasks for queue updates
    - _Requirements: 6.1, 6.4, 6.5_

  - [x] 5.3 Add build execution and query endpoints
    - Implement POST /api/builds/{id}/execute for build execution
    - Implement POST /api/builds/query-location for location queries
    - Add comprehensive error handling and validation
    - _Requirements: 6.2, 6.3, 6.4, 6.5_

- [x] 6. Integrate BuildTaskEndpoint into APIServer
  - [x] 6.1 Register BuildTaskEndpoint in APIServer
    - Add BuildTaskEndpoint instantiation to APIServer.start()
    - Ensure proper dependency injection and initialization
    - _Requirements: 6.1, 6.2, 6.3_

- [ ] 7. Implement MCP server integration
  - [ ] 7.1 Add build task management tools to MCP server
    - Add create_build tool to minecraft_mcp.py
    - Add add_build_task tool for task queue management
    - Add execute_build tool for build execution
    - Add query_builds_by_location tool for location queries
    - Add get_build_status tool for build information retrieval
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [ ]* 7.2 Write integration tests for MCP tools
    - Test MCP tool calls against running HTTP API server
    - Test error handling and validation in MCP layer
    - _Requirements: 8.6, 8.7_

- [ ]* 8. Add missing unit tests
  - [ ]* 8.1 Write unit tests for BuildService
    - Test build creation, task management, and execution orchestration
    - Test error handling and validation
    - _Requirements: 1.1, 1.2, 2.1, 2.5, 3.1, 3.4_

  - [ ]* 8.2 Write unit tests for BuildRepository
    - Test CRUD operations and location queries
    - Test database error handling
    - _Requirements: 1.1, 1.2, 1.3, 4.1, 4.4_

  - [ ]* 8.3 Write unit tests for TaskRepository
    - Test task queue operations and coordinate tracking
    - Test batch operations and transaction handling
    - _Requirements: 2.1, 2.3, 2.5, 4.3_

  - [ ]* 8.4 Write unit tests for LocationQueryService
    - Test spatial intersection logic and chronological ordering
    - Test edge cases and boundary conditions
    - _Requirements: 4.1, 4.2, 4.4_

- [ ]* 9. Add comprehensive property-based tests
  - [ ]* 9.1 Write property tests for build operations
    - **Property 1: Build ID Uniqueness**
    - **Property 2: Build Persistence Round Trip**
    - **Property 3: Build Metadata Completeness**
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.4**

  - [ ]* 9.2 Write property tests for task queue operations
    - **Property 4: Task Queue Ordering**
    - **Property 5: Task Validation Consistency**
    - **Property 6: Task Queue Persistence**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.5**

  - [ ]* 9.3 Write property tests for task execution
    - **Property 7: Task Execution Ordering**
    - **Property 8: Task Status Tracking**
    - **Property 9: Build Completion Status**
    - **Property 10: Endpoint Integration Equivalence**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

  - [ ]* 9.4 Write property tests for location queries
    - **Property 11: Location Query Intersection**
    - **Property 12: Location Query Completeness**
    - **Property 13: Coordinate Tracking Completeness**
    - **Property 14: Overlapping Build Chronological Order**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4**

  - [ ]* 9.5 Write property tests for error handling
    - **Property 15: Database Error Handling**
    - **Property 16: API Error Response Consistency**
    - **Validates: Requirements 5.4, 6.4**

  - [ ]* 9.6 Write property tests for API consistency
    - **Property 17: API Pattern Consistency**
    - **Property 18: Endpoint Refactoring Compatibility**
    - **Validates: Requirements 6.5, 7.1, 7.2, 7.3, 7.4, 7.5**

  - [ ]* 9.7 Write property tests for MCP integration
    - **Property 19: MCP Build Creation Tool**
    - **Property 20: MCP Task Addition Tool**
    - **Property 21: MCP Build Execution Tool**
    - **Property 22: MCP Location Query Tool**
    - **Property 23: MCP Build Status Tool**
    - **Property 24: MCP HTTP API Delegation**
    - **Property 25: MCP Error Message Descriptiveness**
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7**

  - [ ]* 9.8 Write property tests for system compatibility
    - **Property 26: Existing API Functionality Preservation**
    - **Validates: Requirements 9.4**

- [ ]* 10. Final system validation
  - [ ]* 10.1 Write end-to-end integration tests
    - Test complete build creation → task addition → execution → query workflow
    - Test system integration with existing endpoints
    - Test MCP tool integration with HTTP API
    - _Requirements: All requirements_

  - [ ]* 10.2 Write compatibility regression tests
    - Test that existing API functionality remains unchanged
    - Test endpoint refactoring maintains backward compatibility
    - Test performance regression for existing endpoints
    - _Requirements: 7.3, 7.4, 9.4_

- [ ] 11. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Most core functionality has been implemented - remaining work focuses on MCP integration and comprehensive testing
- The system is fully functional through HTTP API endpoints
- Property tests validate universal correctness properties using jqwik framework
- Unit tests validate specific examples and edge cases
- Integration tests ensure end-to-end functionality works correctly