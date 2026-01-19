#!/usr/bin/env python3
"""
Test script for Minecraft Fabric mod API endpoints.
Tests player listing and entity spawning functionality.
"""

import requests
import json
import sys

API_BASE = "http://localhost:7070"

def get_players():
    """Get list of players from the server."""
    try:
        response = requests.get(f"{API_BASE}/api/world/players")
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        print(f"Error getting players: {e}")
        return None

def spawn_entity_near_player(player, entity_type="minecraft:zombie"):
    """Spawn an entity near the specified player."""
    # Spawn 3 blocks away from player
    spawn_pos = {
        "type": entity_type,
        "x": player["position"]["x"] + 3,
        "y": player["position"]["y"],
        "z": player["position"]["z"] + 3
    }
    
    try:
        response = requests.post(
            f"{API_BASE}/api/world/entities/spawn",
            json=spawn_pos,
            headers={"Content-Type": "application/json"}
        )
        print("Status Code:", response.status_code)
        print("Headers:", response.headers)
        print("Text Body:", response.text)
        print("JSON Body:", response.json())
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        print(f"Error spawning entity: {e}")
        if hasattr(e, 'response') and e.response is not None:
            try:
                error_data = e.response.json()
                print(f"Error details: {error_data}")
            except:
                print(f"Response text: {e.response.text}")
        return None

def test_place_ladder():
    """Test ladder placement endpoint with various configuration scenarios."""
    print("\n4. Testing ladder placement endpoint...")
    
    # Test 1: Basic ladder placement with auto-detection
    print("\n   4.1. Testing basic ladder placement with auto-detection...")
    ladder_request = {
        "x": 100,
        "y": 64,
        "z": 100,
        "height": 5,
        "block_type": "minecraft:ladder"
        # No facing specified - should auto-detect
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        print(f"   Status: {response.status_code}")
        result = response.json()
        print(f"   Response: {json.dumps(result, indent=4)}")
        
        # Verify response format and data accuracy
        if result.get("success"):
            assert "blocks_placed" in result, "Response missing blocks_placed field"
            assert "facing" in result, "Response missing facing field"
            assert "start_position" in result, "Response missing start_position field"
            assert "end_position" in result, "Response missing end_position field"
            assert "world" in result, "Response missing world field"
            assert result["blocks_placed"] == 5, f"Expected 5 blocks, got {result['blocks_placed']}"
            print(f"   ✅ Successfully placed {result['blocks_placed']} ladder blocks facing {result['facing']}")
        else:
            print(f"   ❌ Failed to place ladder: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing ladder placement: {e}")
    except AssertionError as e:
        print(f"   ❌ Response format validation failed: {e}")
    
    # Test 2: Ladder with specified facing direction
    print("\n   4.2. Testing specified facing direction...")
    ladder_request = {
        "x": 105,
        "y": 64,
        "z": 100,
        "height": 3,
        "block_type": "minecraft:ladder",
        "facing": "north"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        print(f"   Status: {response.status_code}")
        result = response.json()
        print(f"   Response: {json.dumps(result, indent=4)}")
        
        if result.get("success"):
            assert result["facing"] == "north", f"Expected facing 'north', got '{result['facing']}'"
            print(f"   ✅ Successfully placed {result['blocks_placed']} ladder blocks facing {result['facing']}")
        else:
            print(f"   ❌ Failed to place ladder: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing ladder placement: {e}")
    except AssertionError as e:
        print(f"   ❌ Response validation failed: {e}")
    
    # Test 3: Different ladder heights
    print("\n   4.3. Testing different ladder heights...")
    for height in [1, 10, 20]:
        ladder_request = {
            "x": 110 + height,
            "y": 64,
            "z": 100,
            "height": height,
            "block_type": "minecraft:ladder"
        }
        
        try:
            response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
            result = response.json()
            
            if result.get("success"):
                assert result["blocks_placed"] == height, f"Expected {height} blocks, got {result['blocks_placed']}"
                print(f"   ✅ Height {height}: Placed {result['blocks_placed']} blocks")
            else:
                print(f"   ❌ Height {height}: Failed - {result.get('error', 'Unknown error')}")
        except requests.exceptions.RequestException as e:
            print(f"   ❌ Height {height}: Error - {e}")
        except AssertionError as e:
            print(f"   ❌ Height {height}: Validation failed - {e}")

def test_ladder_error_conditions():
    """Test error condition handling for invalid inputs."""
    print("\n5. Testing ladder error conditions...")
    
    # Test 1: Invalid coordinates (negative Y)
    print("\n   5.1. Testing invalid coordinates...")
    ladder_request = {
        "x": 0,
        "y": -10,  # Invalid Y coordinate
        "z": 0,
        "height": 5,
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        print(f"   Status: {response.status_code}")
        result = response.json()
        
        if response.status_code == 400 or not result.get("success"):
            print(f"   ✅ Correctly rejected invalid coordinates: {result.get('error', 'Unknown error')}")
        else:
            print(f"   ❌ Should have failed but succeeded: {result}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing invalid coordinates: {e}")
    
    # Test 2: Invalid block type
    print("\n   5.2. Testing invalid block type...")
    ladder_request = {
        "x": 120,
        "y": 64,
        "z": 100,
        "height": 3,
        "block_type": "minecraft:invalid_block"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        print(f"   Status: {response.status_code}")
        result = response.json()
        
        if response.status_code == 400 or not result.get("success"):
            print(f"   ✅ Correctly rejected invalid block type: {result.get('error', 'Unknown error')}")
        else:
            print(f"   ❌ Should have failed but succeeded: {result}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing invalid block type: {e}")
    
    # Test 3: Invalid facing direction
    print("\n   5.3. Testing invalid facing direction...")
    ladder_request = {
        "x": 125,
        "y": 64,
        "z": 100,
        "height": 2,
        "block_type": "minecraft:ladder",
        "facing": "invalid_direction"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        print(f"   Status: {response.status_code}")
        result = response.json()
        
        if response.status_code == 400 or not result.get("success"):
            print(f"   ✅ Correctly rejected invalid facing: {result.get('error', 'Unknown error')}")
        else:
            print(f"   ❌ Should have failed but succeeded: {result}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing invalid facing: {e}")
    
    # Test 4: Invalid height (zero or negative)
    print("\n   5.4. Testing invalid height values...")
    for invalid_height in [0, -5]:
        ladder_request = {
            "x": 130,
            "y": 64,
            "z": 100,
            "height": invalid_height,
            "block_type": "minecraft:ladder"
        }
        
        try:
            response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
            result = response.json()
            
            if response.status_code == 400 or not result.get("success"):
                print(f"   ✅ Height {invalid_height}: Correctly rejected - {result.get('error', 'Unknown error')}")
            else:
                print(f"   ❌ Height {invalid_height}: Should have failed but succeeded: {result}")
        except requests.exceptions.RequestException as e:
            print(f"   ❌ Height {invalid_height}: Error - {e}")
    
    # Test 5: Invalid world name
    print("\n   5.5. Testing invalid world name...")
    ladder_request = {
        "world": "nonexistent_world",
        "x": 135,
        "y": 64,
        "z": 100,
        "height": 3,
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        print(f"   Status: {response.status_code}")
        result = response.json()
        
        if response.status_code == 400 or not result.get("success"):
            print(f"   ✅ Correctly rejected invalid world: {result.get('error', 'Unknown error')}")
        else:
            print(f"   ❌ Should have failed but succeeded: {result}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing invalid world: {e}")

def test_ladder_response_format():
    """Test response format consistency and completeness."""
    print("\n6. Testing ladder response format...")
    
    # Test successful response format
    print("\n   6.1. Testing successful response format...")
    ladder_request = {
        "x": 140,
        "y": 64,
        "z": 100,
        "height": 4,
        "block_type": "minecraft:ladder",
        "facing": "south"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        result = response.json()
        
        if result.get("success"):
            # Verify all required fields are present with correct types
            required_fields = {
                "success": bool,
                "world": str,
                "blocks_placed": int,
                "facing": str,
                "start_position": dict,
                "end_position": dict
            }
            
            for field, expected_type in required_fields.items():
                assert field in result, f"Missing required field: {field}"
                assert isinstance(result[field], expected_type), f"Field {field} has wrong type: expected {expected_type}, got {type(result[field])}"
            
            # Verify position dictionaries have x, y, z coordinates
            for pos_field in ["start_position", "end_position"]:
                pos = result[pos_field]
                for coord in ["x", "y", "z"]:
                    assert coord in pos, f"Missing coordinate {coord} in {pos_field}"
                    assert isinstance(pos[coord], int), f"Coordinate {coord} in {pos_field} should be int, got {type(pos[coord])}"
            
            print(f"   ✅ Response format validation passed")
            print(f"   ✅ All required fields present with correct types")
        else:
            print(f"   ❌ Request failed: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing response format: {e}")
    except AssertionError as e:
        print(f"   ❌ Response format validation failed: {e}")

def test_ladder_specific_scenarios():
    """Test ladder-specific scenarios including world defaults and block types."""
    print("\n7. Testing ladder-specific scenarios...")
    
    # Test 1: Default world behavior (overworld fallback)
    print("\n   7.1. Testing default world behavior (overworld fallback)...")
    ladder_request = {
        # No world specified - should default to overworld
        "x": 150,
        "y": 64,
        "z": 100,
        "height": 3,
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        result = response.json()
        
        if result.get("success"):
            # Should default to overworld
            expected_world = "minecraft:overworld"
            assert result["world"] == expected_world, f"Expected world '{expected_world}', got '{result['world']}'"
            print(f"   ✅ Correctly defaulted to world: {result['world']}")
        else:
            print(f"   ❌ Failed to place ladder with default world: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing default world: {e}")
    except AssertionError as e:
        print(f"   ❌ World default validation failed: {e}")
    
    # Test 2: Explicit overworld specification
    print("\n   7.2. Testing explicit overworld specification...")
    ladder_request = {
        "world": "minecraft:overworld",
        "x": 152,
        "y": 64,
        "z": 100,
        "height": 2,
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        result = response.json()
        
        if result.get("success"):
            assert result["world"] == "minecraft:overworld", f"Expected overworld, got '{result['world']}'"
            print(f"   ✅ Explicit overworld specification worked: {result['world']}")
        else:
            print(f"   ❌ Failed with explicit overworld: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing explicit overworld: {e}")
    except AssertionError as e:
        print(f"   ❌ Explicit overworld validation failed: {e}")
    
    # Test 3: Different ladder block types (minecraft:ladder variants)
    print("\n   7.3. Testing different ladder block types...")
    # Note: In vanilla Minecraft, there's typically only minecraft:ladder
    # But we test to ensure the system handles block type validation properly
    ladder_types = [
        "minecraft:ladder",
        # Test with potential modded ladder types that might exist
        "minecraft:bamboo_ladder",  # This might not exist, testing validation
        "minecraft:iron_ladder"     # This might not exist, testing validation
    ]
    
    for i, block_type in enumerate(ladder_types):
        ladder_request = {
            "x": 155 + i * 5,
            "y": 64,
            "z": 100,
            "height": 2,
            "block_type": block_type
        }
        
        try:
            response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
            result = response.json()
            
            if result.get("success"):
                print(f"   ✅ {block_type}: Successfully placed {result['blocks_placed']} blocks")
            else:
                # For non-existent block types, failure is expected
                if block_type == "minecraft:ladder":
                    print(f"   ❌ {block_type}: Unexpected failure - {result.get('error', 'Unknown error')}")
                else:
                    print(f"   ✅ {block_type}: Correctly rejected invalid block type - {result.get('error', 'Unknown error')}")
        except requests.exceptions.RequestException as e:
            print(f"   ❌ {block_type}: Error - {e}")
    
    # Test 4: Maximum height edge case (world height limits)
    print("\n   7.4. Testing maximum height edge case...")
    ladder_request = {
        "x": 170,
        "y": 64,
        "z": 100,
        "height": 256,  # Very tall ladder - should hit world height limit
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        result = response.json()
        
        if result.get("success"):
            # Should handle height limits gracefully
            print(f"   ✅ Maximum height: Placed {result['blocks_placed']} blocks")
            if result['blocks_placed'] < 256:
                print(f"   ✅ Height was appropriately truncated due to world limits")
            
            # Verify end position reflects actual placement
            expected_end_y = result['start_position']['y'] + result['blocks_placed'] - 1
            actual_end_y = result['end_position']['y']
            assert actual_end_y == expected_end_y, f"End position mismatch: expected Y={expected_end_y}, got Y={actual_end_y}"
            print(f"   ✅ End position correctly calculated: Y={actual_end_y}")
        else:
            print(f"   ❌ Maximum height test failed: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing maximum height: {e}")
    except AssertionError as e:
        print(f"   ❌ Maximum height validation failed: {e}")
    
    # Test 5: Attachment failure scenario (high in air with no solid blocks)
    print("\n   7.5. Testing attachment failure scenario...")
    ladder_request = {
        "x": 200,
        "y": 150,  # High in the air, likely no solid blocks nearby
        "z": 200,
        "height": 4,
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        result = response.json()
        
        if result.get("success"):
            print(f"   ✅ Attachment fallback: Placed {result['blocks_placed']} blocks facing {result['facing']}")
            print(f"   ✅ System handled lack of attachment with fallback logic")
        else:
            # Attachment failure is also acceptable behavior depending on implementation
            print(f"   ✅ Attachment validation correctly prevented placement: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing attachment failure: {e}")
    
    # Test 6: Near-bedrock placement (low Y coordinates)
    print("\n   7.6. Testing near-bedrock placement...")
    ladder_request = {
        "x": 175,
        "y": 5,  # Near bedrock level
        "z": 100,
        "height": 10,
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        result = response.json()
        
        if result.get("success"):
            print(f"   ✅ Near-bedrock: Placed {result['blocks_placed']} blocks from Y={result['start_position']['y']}")
        else:
            print(f"   ❌ Near-bedrock placement failed: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing near-bedrock placement: {e}")
    
    # Test 7: All facing directions
    print("\n   7.7. Testing all valid facing directions...")
    facing_directions = ["north", "south", "east", "west"]
    
    for i, facing in enumerate(facing_directions):
        ladder_request = {
            "x": 180 + i * 3,
            "y": 64,
            "z": 100,
            "height": 3,
            "block_type": "minecraft:ladder",
            "facing": facing
        }
        
        try:
            response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
            result = response.json()
            
            if result.get("success"):
                assert result["facing"] == facing, f"Expected facing '{facing}', got '{result['facing']}'"
                print(f"   ✅ Facing {facing}: Successfully placed {result['blocks_placed']} blocks")
            else:
                print(f"   ❌ Facing {facing}: Failed - {result.get('error', 'Unknown error')}")
        except requests.exceptions.RequestException as e:
            print(f"   ❌ Facing {facing}: Error - {e}")
        except AssertionError as e:
            print(f"   ❌ Facing {facing}: Validation failed - {e}")
    
    # Test 8: Single block ladder (minimum height)
    print("\n   7.8. Testing single block ladder (minimum height)...")
    ladder_request = {
        "x": 195,
        "y": 64,
        "z": 100,
        "height": 1,
        "block_type": "minecraft:ladder"
    }
    
    try:
        response = requests.post(f"{API_BASE}/api/world/prefabs/ladder", json=ladder_request)
        result = response.json()
        
        if result.get("success"):
            assert result["blocks_placed"] == 1, f"Expected 1 block, got {result['blocks_placed']}"
            # Start and end positions should be the same for single block
            start_pos = result["start_position"]
            end_pos = result["end_position"]
            assert start_pos == end_pos, f"Start and end positions should be equal for single block: start={start_pos}, end={end_pos}"
            print(f"   ✅ Single block ladder: Successfully placed at ({start_pos['x']}, {start_pos['y']}, {start_pos['z']})")
        else:
            print(f"   ❌ Single block ladder failed: {result.get('error', 'Unknown error')}")
    except requests.exceptions.RequestException as e:
        print(f"   ❌ Error testing single block ladder: {e}")
    except AssertionError as e:
        print(f"   ❌ Single block ladder validation failed: {e}")

def main():
    print("Testing Minecraft API endpoints...")
    
    # Get players
    print("\n1. Getting player list...")
    players = get_players()
    
    if not players:
        print("No players found or API error. Make sure the server is running.")
        sys.exit(1)
    
    print(f"Found {len(players)} player(s):")
    for player in players:
        pos = player["position"]
        print(f"  - {player['name']} at ({pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f})")
    
    # Spawn entity near first player
    if players:
        first_player = players[0]
        print(f"\n2. Spawning sheep near {first_player['name']}...")
        
        result = spawn_entity_near_player(first_player, "minecraft:sheep")

        
        if result:
            print(result)
            if result.get("success"):
                pos = result["position"]
                print(f"✅ Successfully spawned {result['type']} at ({pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f})")
                print(f"   Entity UUID: {result['uuid']}")
            else:
                print(f"❌ Failed to spawn entity: {result}")
        else:
            print("❌ Failed to spawn entity")
    
    # Test different entity types
    print(f"\n3. Testing different entity types...")
    test_entities = ["minecraft:sheep", "minecraft:cow", "minecraft:chicken"]
    
    for entity_type in test_entities:
        print(f"Spawning {entity_type}...")
        result = spawn_entity_near_player(first_player, entity_type)
        if result and result.get("success"):
            print(f"  ✅ {entity_type} spawned successfully")
        else:
            print(f"  ❌ Failed to spawn {entity_type}")
    
    # Test ladder placement with comprehensive scenarios
    test_place_ladder()
    test_ladder_error_conditions()
    test_ladder_response_format()
    test_ladder_specific_scenarios()

if __name__ == "__main__":
    main()