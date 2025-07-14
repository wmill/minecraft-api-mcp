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
        response = requests.get(f"{API_BASE}/players")
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

if __name__ == "__main__":
    main()