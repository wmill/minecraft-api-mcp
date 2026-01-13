package com.example.buildtask.model;

/**
 * Represents the different types of build tasks that can be executed.
 * Each type corresponds to specific endpoint functionality.
 */
public enum TaskType {
    BLOCK_SET,      // Uses BlocksEndpoint.setBlocks
    BLOCK_FILL,     // Uses BlocksEndpoint.fillBox
    PREFAB_DOOR,    // Uses PrefabEndpoint.door
    PREFAB_STAIRS,  // Uses PrefabEndpoint.stairs
    PREFAB_WINDOW,  // Uses PrefabEndpoint.windowPane
    PREFAB_TORCH,   // Uses PrefabEndpoint.torch
    PREFAB_SIGN     // Uses PrefabEndpoint.sign
}