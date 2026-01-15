"""
Minecraft API Client

HTTP client for communicating with the Minecraft Fabric mod REST API.
Provides typed methods for each API endpoint with error handling.
"""

import httpx
from typing import Any, Dict, List, Optional


class MinecraftAPIClient:
    """HTTP client for the Minecraft Fabric mod REST API."""
    
    def __init__(self, base_url: str):
        """
        Initialize the API client.
        
        Args:
            base_url: Base URL of the Minecraft API (e.g., "http://localhost:7070")
        """
        self.base_url = base_url
    
    async def get_players(self) -> dict:
        """
        Get list of all players currently online.
        
        Returns:
            dict: Response containing list of players with positions and rotations
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/api/world/players")
            response.raise_for_status()
            return response.json()
    
    async def get_entities(self) -> dict:
        """
        Get list of all available entity types that can be spawned.
        
        Returns:
            dict: Response containing list of entity types
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/api/world/entities")
            response.raise_for_status()
            return response.json()
    
    async def spawn_entity(
        self,
        entity_type: str,
        x: float,
        y: float,
        z: float,
        world: Optional[str] = None
    ) -> dict:
        """
        Spawn an entity at specified coordinates.
        
        Args:
            entity_type: Entity type identifier (e.g., 'minecraft:zombie')
            x: X coordinate
            y: Y coordinate
            z: Z coordinate
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing spawn result with entity UUID and position
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "type": entity_type,
            "x": x,
            "y": y,
            "z": z
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/world/entities/spawn",
                json=payload
            )
            response.raise_for_status()
            return response.json()

    async def get_blocks(self) -> dict:
        """
        Get list of all available block types.
        
        Returns:
            dict: Response containing list of block types
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/api/world/blocks/list")
            response.raise_for_status()
            return response.json()
    
    async def set_blocks(
        self,
        start_x: int,
        start_y: int,
        start_z: int,
        blocks: List[List[List[Optional[Dict[str, Any]]]]],
        world: Optional[str] = None
    ) -> dict:
        """
        Set blocks in the world using a 3D array.
        
        Args:
            start_x: Starting X coordinate
            start_y: Starting Y coordinate
            start_z: Starting Z coordinate
            blocks: 3D array of block objects (use null for no change)
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing blocks set count and result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "blocks": blocks
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/world/blocks/set",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def get_blocks_chunk(
        self,
        start_x: int,
        start_y: int,
        start_z: int,
        size_x: int,
        size_y: int,
        size_z: int,
        world: Optional[str] = None
    ) -> dict:
        """
        Get a chunk of blocks from the world.
        
        Args:
            start_x: Starting X coordinate
            start_y: Starting Y coordinate
            start_z: Starting Z coordinate
            size_x: Size in X dimension (max 64)
            size_y: Size in Y dimension (max 64)
            size_z: Size in Z dimension (max 64)
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing 3D array of blocks
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "size_x": size_x,
            "size_y": size_y,
            "size_z": size_z
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/world/blocks/chunk",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def fill_box(
        self,
        x1: int,
        y1: int,
        z1: int,
        x2: int,
        y2: int,
        z2: int,
        block_type: str,
        world: Optional[str] = None
    ) -> dict:
        """
        Fill a cuboid/box with a specific block type between two coordinates.
        
        Args:
            x1: First corner X coordinate
            y1: First corner Y coordinate
            z1: First corner Z coordinate
            x2: Second corner X coordinate
            y2: Second corner Y coordinate
            z2: Second corner Z coordinate
            block_type: Block type identifier (e.g., 'minecraft:stone')
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing fill result with block counts
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "x1": x1,
            "y1": y1,
            "z1": z1,
            "x2": x2,
            "y2": y2,
            "z2": z2,
            "block_type": block_type
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/world/blocks/fill",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def get_heightmap(
        self,
        x1: int,
        z1: int,
        x2: int,
        z2: int,
        heightmap_type: str = "WORLD_SURFACE",
        world: Optional[str] = None
    ) -> dict:
        """
        Get topographical heightmap for a rectangular area.
        
        Args:
            x1: First corner X coordinate
            z1: First corner Z coordinate
            x2: Second corner X coordinate
            z2: Second corner Z coordinate
            heightmap_type: Type of heightmap (WORLD_SURFACE, MOTION_BLOCKING, etc.)
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing heightmap data
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "x1": x1,
            "z1": z1,
            "x2": x2,
            "z2": z2,
            "heightmap_type": heightmap_type
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/world/blocks/heightmap",
                json=payload
            )
            response.raise_for_status()
            return response.json()

    async def broadcast_message(
        self,
        message: str,
        action_bar: bool = False
    ) -> dict:
        """
        Send a message to all players on the server.
        
        Args:
            message: Message text to send
            action_bar: If true, shows in action bar; if false, shows in chat
            
        Returns:
            dict: Response containing broadcast result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "message": message,
            "action_bar": action_bar
        }
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/message/broadcast",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def send_message_to_player(
        self,
        message: str,
        player_uuid: Optional[str] = None,
        player_name: Optional[str] = None,
        action_bar: bool = False
    ) -> dict:
        """
        Send a message to a specific player.
        
        Args:
            message: Message text to send
            player_uuid: Player's UUID (takes priority over name)
            player_name: Player's name (used if UUID not provided)
            action_bar: If true, shows in action bar; if false, shows in chat
            
        Returns:
            dict: Response containing message result
            
        Raises:
            httpx.HTTPError: If the request fails
            ValueError: If neither player_uuid nor player_name is provided
        """
        if not player_uuid and not player_name:
            raise ValueError("Must provide either player_uuid or player_name")
        
        payload = {
            "message": message,
            "action_bar": action_bar
        }
        if player_uuid:
            payload["uuid"] = player_uuid
        if player_name:
            payload["name"] = player_name
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/message/player",
                json=payload
            )
            response.raise_for_status()
            return response.json()

    async def place_nbt_structure(
        self,
        nbt_file_data: str,
        filename: str,
        x: int,
        y: int,
        z: int,
        world: Optional[str] = None,
        rotation: str = "NONE",
        include_entities: bool = True,
        replace_blocks: bool = True
    ) -> dict:
        """
        Place an NBT structure file at specified coordinates.
        
        Args:
            nbt_file_data: Base64-encoded NBT structure file data
            filename: Original filename of the NBT structure
            x: X coordinate to place structure
            y: Y coordinate to place structure
            z: Z coordinate to place structure
            world: World name (optional, defaults to minecraft:overworld)
            rotation: Structure rotation (NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90)
            include_entities: Whether to include entities from the NBT structure
            replace_blocks: Whether to replace existing blocks
            
        Returns:
            dict: Response containing placement result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "nbt_file_data": nbt_file_data,
            "filename": filename,
            "x": x,
            "y": y,
            "z": z,
            "rotation": rotation,
            "include_entities": include_entities,
            "replace_blocks": replace_blocks
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/prefab/nbt",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def place_door_line(
        self,
        start_x: int,
        start_y: int,
        start_z: int,
        facing: str,
        block_type: str,
        width: int = 1,
        hinge: str = "left",
        double_doors: bool = False,
        open: bool = False,
        world: Optional[str] = None
    ) -> dict:
        """
        Place a line of doors with specified width and properties.
        
        Args:
            start_x: Starting X coordinate
            start_y: Starting Y coordinate
            start_z: Starting Z coordinate
            facing: Direction the doors should face (north, south, east, west)
            block_type: Door block type (e.g., 'minecraft:oak_door')
            width: Number of doors to place in a row
            hinge: Door hinge position (left, right)
            double_doors: Whether to alternate hinges for double doors
            open: Whether doors start in open position
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing placement result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "facing": facing,
            "block_type": block_type,
            "width": width,
            "hinge": hinge,
            "double_doors": double_doors,
            "open": open
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/prefab/door",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def place_stairs(
        self,
        start_x: int,
        start_y: int,
        start_z: int,
        end_x: int,
        end_y: int,
        end_z: int,
        block_type: str,
        stair_type: str,
        staircase_direction: str,
        fill_support: bool = False,
        world: Optional[str] = None
    ) -> dict:
        """
        Build a wide staircase between two points.
        
        Args:
            start_x: Starting X coordinate
            start_y: Starting Y coordinate
            start_z: Starting Z coordinate
            end_x: Ending X coordinate
            end_y: Ending Y coordinate
            end_z: Ending Z coordinate
            block_type: Base block type for solid sections
            stair_type: Stair block type (e.g., 'minecraft:oak_stairs')
            staircase_direction: Orientation of the staircase (north, south, east, west)
            fill_support: Whether to fill underneath for support
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing placement result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "end_x": end_x,
            "end_y": end_y,
            "end_z": end_z,
            "block_type": block_type,
            "stair_type": stair_type,
            "staircase_direction": staircase_direction,
            "fill_support": fill_support
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/prefab/stairs",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def place_window_pane_wall(
        self,
        start_x: int,
        start_y: int,
        start_z: int,
        end_x: int,
        end_z: int,
        height: int,
        block_type: str,
        waterlogged: bool = False,
        world: Optional[str] = None
    ) -> dict:
        """
        Create a vertical wall of window panes between two points.
        
        Args:
            start_x: Starting X coordinate
            start_y: Starting Y coordinate
            start_z: Starting Z coordinate
            end_x: Ending X coordinate
            end_z: Ending Z coordinate
            height: Height of the window pane wall in blocks
            block_type: Pane block type (e.g., 'minecraft:glass_pane')
            waterlogged: Whether the panes should be waterlogged
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing placement result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "start_x": start_x,
            "start_y": start_y,
            "start_z": start_z,
            "end_x": end_x,
            "end_z": end_z,
            "height": height,
            "block_type": block_type,
            "waterlogged": waterlogged
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/prefab/window",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def place_torch(
        self,
        x: int,
        y: int,
        z: int,
        block_type: str,
        facing: Optional[str] = None,
        world: Optional[str] = None
    ) -> dict:
        """
        Place a single torch (ground or wall-mounted) at specified coordinates.
        
        Args:
            x: X coordinate
            y: Y coordinate
            z: Z coordinate
            block_type: Torch type (e.g., 'minecraft:torch', 'minecraft:wall_torch')
            facing: For wall torches, direction the torch faces (north, south, east, west)
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing placement result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "x": x,
            "y": y,
            "z": z,
            "block_type": block_type
        }
        if facing:
            payload["facing"] = facing
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/prefab/torch",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def place_sign(
        self,
        x: int,
        y: int,
        z: int,
        block_type: str,
        front_lines: Optional[List[str]] = None,
        back_lines: Optional[List[str]] = None,
        facing: Optional[str] = None,
        rotation: Optional[int] = None,
        glowing: bool = False,
        world: Optional[str] = None
    ) -> dict:
        """
        Place a single sign (wall or standing) with custom text.
        
        Args:
            x: X coordinate
            y: Y coordinate
            z: Z coordinate
            block_type: Sign type (e.g., 'minecraft:oak_wall_sign', 'minecraft:oak_sign')
            front_lines: Array of 0-4 text lines for the front
            back_lines: Array of 0-4 text lines for the back
            facing: For wall signs, direction the sign faces (north, south, east, west)
            rotation: For standing signs, rotation angle 0-15
            glowing: Whether the sign text should glow
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing placement result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "x": x,
            "y": y,
            "z": z,
            "block_type": block_type,
            "glowing": glowing
        }
        if front_lines:
            payload["front_lines"] = front_lines
        if back_lines:
            payload["back_lines"] = back_lines
        if facing:
            payload["facing"] = facing
        if rotation is not None:
            payload["rotation"] = rotation
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/prefab/sign",
                json=payload
            )
            response.raise_for_status()
            return response.json()

    async def create_build(
        self,
        name: str,
        description: Optional[str] = None,
        world: Optional[str] = None
    ) -> dict:
        """
        Create a new build with metadata for organizing building tasks.
        
        Args:
            name: Build name
            description: Build description
            world: World name (optional, defaults to minecraft:overworld)
            
        Returns:
            dict: Response containing build ID and metadata
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "name": name
        }
        if description:
            payload["description"] = description
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/builds",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def add_build_task(
        self,
        build_id: str,
        task_type: str,
        task_data: Dict[str, Any],
        description: str
    ) -> dict:
        """
        Add a building task to a build queue.
        
        Args:
            build_id: Build UUID
            task_type: Type of building task (BLOCK_SET, BLOCK_FILL, PREFAB_*, etc.)
            task_data: Task-specific payload data
            description: Description of the task
            
        Returns:
            dict: Response containing task addition result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "task_type": task_type,
            "task_data": task_data,
            "description": description
        }
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/builds/{build_id}/tasks",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def execute_build(
        self,
        build_id: str
    ) -> dict:
        """
        Execute all queued tasks in a build.
        
        Args:
            build_id: Build UUID
            
        Returns:
            dict: Response containing execution result with task counts
            
        Raises:
            httpx.HTTPError: If the request fails
        """

        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/builds/{build_id}/execute"
            )
            response.raise_for_status()
            return response.json()
    
    async def query_builds_by_location(
        self,
        min_x: int,
        min_y: int,
        min_z: int,
        max_x: int,
        max_y: int,
        max_z: int,
        world: Optional[str] = None,
        include_in_progress: bool = False
    ) -> dict:
        """
        Find builds that intersect with a specified area.
        
        Args:
            min_x: Minimum X coordinate
            min_y: Minimum Y coordinate
            min_z: Minimum Z coordinate
            max_x: Maximum X coordinate
            max_y: Maximum Y coordinate
            max_z: Maximum Z coordinate
            world: World name (optional, defaults to minecraft:overworld)
            include_in_progress: Whether to include builds still in progress
            
        Returns:
            dict: Response containing list of builds in the area
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "min_x": min_x,
            "min_y": min_y,
            "min_z": min_z,
            "max_x": max_x,
            "max_y": max_y,
            "max_z": max_z,
            "include_in_progress": include_in_progress
        }
        if world:
            payload["world"] = world
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/builds/query-location",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def get_build_status(
        self,
        build_id: str
    ) -> dict:
        """
        Get build details, status, and task information.
        
        Args:
            build_id: Build UUID
            
        Returns:
            dict: Response containing build status and task details
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        async with httpx.AsyncClient() as client:
            response = await client.get(
                f"{self.base_url}/api/builds/{build_id}"
            )
            response.raise_for_status()
            return response.json()

    async def teleport_player(
        self,
        player_name: str,
        x: float,
        y: float,
        z: float,
        dimension: Optional[str] = None,
        yaw: float = 0.0,
        pitch: float = 0.0
    ) -> dict:
        """
        Teleport a player to specified coordinates with optional rotation.
        
        Args:
            player_name: Name of the player to teleport
            x: X coordinate
            y: Y coordinate
            z: Z coordinate
            dimension: World dimension (optional, defaults to minecraft:overworld)
            yaw: Horizontal rotation in degrees (0=south, 90=west, 180=north, -90=east)
            pitch: Vertical rotation in degrees (0=horizontal, 90=down, -90=up)
            
        Returns:
            dict: Response containing teleport result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        payload = {
            "player_name": player_name,
            "x": x,
            "y": y,
            "z": z,
            "yaw": yaw,
            "pitch": pitch
        }
        if dimension:
            payload["dimension"] = dimension
        
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/player/teleport",
                json=payload
            )
            response.raise_for_status()
            return response.json()
    
    async def test_connection(self) -> dict:
        """
        Test if the Minecraft server API is running and responding.
        
        Returns:
            dict: Response containing connection test result
            
        Raises:
            httpx.HTTPError: If the request fails
        """
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/api/test")
            response.raise_for_status()
            return response.json()
