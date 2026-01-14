# Implementation Plan: MCP Server Modularization

## Overview

This plan transforms the monolithic `minecraft_mcp.py` (2700+ lines) into a well-organized Python package with clear separation of concerns. The refactoring maintains complete backward compatibility while improving maintainability and extensibility.

## Tasks

- [x] 1. Create package structure and configuration
  - Create `mcp/minecraft_mcp/` directory with `__init__.py`
  - Create subdirectories: `tools/`, `client/`, `handlers/`, `utils/`
  - Add `__init__.py` files to all subdirectories
  - _Requirements: 7.1, 7.2, 7.3_

- [x] 2. Extract configuration module
  - [x] 2.1 Create `minecraft_mcp/config.py`
    - Move environment variable loading (dotenv_values, BASE_URL)
    - Move DEBUG mode setup and debugpy configuration
    - Export ServerConfig dataclass with base_url, debug, script_dir
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [x] 3. Extract utility functions
  - [x] 3.1 Create `minecraft_mcp/utils/helpers.py`
    - Move `yaw_to_cardinal` function
    - Move `safe_url` function
    - _Requirements: 8.1, 8.2, 8.3, 8.4_
  
  - [x] 3.2 Create `minecraft_mcp/utils/formatting.py`
    - Create `format_success_response` helper
    - Create `format_error_response` helper
    - Create coordinate formatting helpers
    - Create list formatting helpers
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 4. Extract API client
  - [x] 4.1 Create `minecraft_mcp/client/minecraft_api.py`
    - Create MinecraftAPIClient class with base_url
    - Implement `get_players()` method
    - Implement `get_entities()` method
    - Implement `spawn_entity()` method
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  
  - [x] 4.2 Add block-related API methods
    - Implement `get_blocks()` method
    - Implement `set_blocks()` method
    - Implement `get_blocks_chunk()` method
    - Implement `fill_box()` method
    - Implement `get_heightmap()` method
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  
  - [x] 4.3 Add message API methods
    - Implement `broadcast_message()` method
    - Implement `send_message_to_player()` method
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  
  - [x] 4.4 Add prefab API methods
    - Implement `place_nbt_structure()` method
    - Implement `place_door_line()` method
    - Implement `place_stairs()` method
    - Implement `place_window_pane_wall()` method
    - Implement `place_torch()` method
    - Implement `place_sign()` method
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  
  - [x] 4.5 Add build management API methods
    - Implement `create_build()` method
    - Implement `add_build_task()` method
    - Implement `execute_build()` method
    - Implement `query_builds_by_location()` method
    - Implement `get_build_status()` method
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  
  - [x] 4.6 Add player and system API methods
    - Implement `teleport_player()` method
    - Implement `test_connection()` method
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 5. Define tool schemas
  - [x] 5.1 Create `minecraft_mcp/tools/schemas.py`
    - Define world tools schemas (get_players, get_entities, spawn_entity)
    - Define block tools schemas (get_blocks, set_blocks, get_blocks_chunk, fill_box, get_heightmap)
    - Define message tools schemas (broadcast_message, send_message_to_player)
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  
  - [x] 5.2 Add prefab and build tool schemas
    - Define prefab tools schemas (place_nbt_structure, place_door_line, place_stairs, place_window_pane_wall, place_torch, place_sign)
    - Define build tools schemas (create_build, add_build_task_*, execute_build, query_builds_by_location, get_build_status)
    - Define system tools schemas (teleport_player, test_server_connection)
    - Export TOOL_SCHEMAS list
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 6. Implement tool handlers by domain
  - [ ] 6.1 Create `minecraft_mcp/handlers/world.py`
    - Implement `handle_get_players` using API client
    - Implement `handle_get_entities` using API client
    - Implement `handle_spawn_entity` using API client
    - Use formatting utilities for responses
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.2_
  
  - [ ] 6.2 Create `minecraft_mcp/handlers/blocks.py`
    - Implement `handle_get_blocks` using API client
    - Implement `handle_set_blocks` using API client
    - Implement `handle_get_blocks_chunk` using API client
    - Implement `handle_fill_box` using API client
    - Implement `handle_get_heightmap` using API client
    - Use formatting utilities for responses
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.2_
  
  - [ ] 6.3 Create `minecraft_mcp/handlers/messages.py`
    - Implement `handle_broadcast_message` using API client
    - Implement `handle_send_message_to_player` using API client
    - Use formatting utilities for responses
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.2_
  
  - [ ] 6.4 Create `minecraft_mcp/handlers/prefabs.py`
    - Implement `handle_place_nbt_structure` using API client
    - Implement `handle_place_door_line` using API client
    - Implement `handle_place_stairs` using API client
    - Implement `handle_place_window_pane_wall` using API client
    - Implement `handle_place_torch` using API client
    - Implement `handle_place_sign` using API client
    - Use formatting utilities for responses
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.2_
  
  - [ ] 6.5 Create `minecraft_mcp/handlers/builds.py`
    - Implement `handle_create_build` using API client
    - Implement `handle_add_build_task_block_set` using API client
    - Implement `handle_add_build_task_block_fill` using API client
    - Implement `handle_add_build_task_prefab_door` using API client
    - Implement `handle_add_build_task_prefab_stairs` using API client
    - Implement `handle_add_build_task_prefab_window` using API client
    - Implement `handle_add_build_task_prefab_torch` using API client
    - Implement `handle_add_build_task_prefab_sign` using API client
    - Implement `handle_execute_build` using API client
    - Implement `handle_query_builds_by_location` using API client
    - Implement `handle_get_build_status` using API client
    - Use formatting utilities for responses
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.2_
  
  - [ ] 6.6 Create `minecraft_mcp/handlers/system.py`
    - Implement `handle_teleport_player` using API client
    - Implement `handle_test_server_connection` using API client
    - Use formatting utilities for responses
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.2_

- [ ] 7. Create tool registry
  - [ ] 7.1 Create `minecraft_mcp/tools/registry.py`
    - Import all handler modules
    - Create TOOL_HANDLERS dictionary mapping tool names to handler functions
    - Implement `get_handler(tool_name)` function
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [ ] 8. Create main server class
  - [ ] 8.1 Create `minecraft_mcp/server.py`
    - Create MinecraftMCPServer class
    - Initialize with api_base, create API client
    - Implement `setup_handlers()` to register list_tools and call_tool
    - Implement list_tools handler using TOOL_SCHEMAS
    - Implement call_tool handler using tool registry
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 10.2_
  
  - [ ] 8.2 Add transport methods to server
    - Implement `run_stdio()` method
    - Implement `create_sse_app()` method
    - Implement `run()` method (defaults to stdio)
    - Preserve logging to stderr
    - _Requirements: 5.3, 9.1, 9.2, 9.3, 9.4_

- [ ] 9. Update package exports
  - [ ] 9.1 Update `minecraft_mcp/__init__.py`
    - Export MinecraftMCPServer class
    - Export config module
    - Export API client class
    - _Requirements: 7.3_

- [ ] 10. Update entry point for backward compatibility
  - [ ] 10.1 Update `minecraft_mcp.py` entry point
    - Import MinecraftMCPServer from package
    - Import config from package
    - Keep command-line argument parsing
    - Keep main() function with asyncio.run
    - Preserve all print statements to stderr
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 7.4, 9.1_

- [ ] 11. Checkpoint - Verify backward compatibility
  - Test that `python minecraft_mcp.py` works with stdio transport
  - Test that all tool names are preserved
  - Test that response formats match original
  - Ensure all tests pass, ask the user if questions arise

- [ ] 12. Update package metadata
  - [ ] 12.1 Update `pyproject.toml`
    - Update package structure references
    - Ensure entry point still works
    - _Requirements: 7.4_

- [ ] 13. Final checkpoint - Complete verification
  - Run integration tests with actual Minecraft API
  - Verify SSE transport still works
  - Verify DEBUG mode and debugpy work
  - Ensure all tests pass, ask the user if questions arise

## Notes

- All tasks focus on code refactoring and organization
- Backward compatibility is maintained throughout
- Each handler uses the API client for HTTP calls
- Response formatting is centralized in utilities
- Tool schemas are separated from implementation
- The entry point `minecraft_mcp.py` remains unchanged for users
- All logging to stderr is preserved for Claude Desktop compatibility
