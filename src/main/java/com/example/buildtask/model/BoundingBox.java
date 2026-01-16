package com.example.buildtask.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Utility class for coordinate operations and bounding box calculations.
 * Used for spatial queries and coordinate tracking.
 * Requirements: 4.3
 */
public class BoundingBox {
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    public BoundingBox() {
    }

    public BoundingBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    /**
     * Checks if this bounding box intersects with another bounding box.
     */
    public boolean intersects(BoundingBox other) {
        if (other == null) return false;
        
        return !(this.maxX < other.minX || this.minX > other.maxX ||
                 this.maxY < other.minY || this.minY > other.maxY ||
                 this.maxZ < other.minZ || this.minZ > other.maxZ);
    }

    /**
     * Creates a bounding box from block set request data.
     * Assumes the task data contains coordinate information.
     */
    public static BoundingBox fromBlockSetRequest(JsonNode taskData) {
        if (taskData == null || !taskData.has("blocks")) {
            return null;
        }

        JsonNode blocks = taskData.get("blocks");
        if (!blocks.isArray() || blocks.size() == 0) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (JsonNode block : blocks) {
            if (block.has("x") && block.has("y") && block.has("z")) {
                int x = block.get("x").asInt();
                int y = block.get("y").asInt();
                int z = block.get("z").asInt();

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }

        if (minX == Integer.MAX_VALUE) {
            return null;
        }

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Creates a bounding box from fill box request data.
     */
    public static BoundingBox fromFillBoxRequest(JsonNode taskData) {
        if (taskData == null) {
            return null;
        }

        if (taskData.has("x1") && taskData.has("y1") && taskData.has("z1") &&
            taskData.has("x2") && taskData.has("y2") && taskData.has("z2")) {
            
            int startX = taskData.get("x1").asInt();
            int startY = taskData.get("y1").asInt();
            int startZ = taskData.get("z1").asInt();
            int endX = taskData.get("x2").asInt();
            int endY = taskData.get("y2").asInt();
            int endZ = taskData.get("z2").asInt();

            return new BoundingBox(startX, startY, startZ, endX, endY, endZ);
        }

        return null;
    }

    /**
     * Creates a bounding box from prefab request data.
     * Most prefabs are single-block or small structures.
     */
    public static BoundingBox fromPrefabRequest(JsonNode taskData) {
        if (taskData == null) {
            return null;
        }

        if (taskData.has("x") && taskData.has("y") && taskData.has("z")) {
            int x = taskData.get("x").asInt();
            int y = taskData.get("y").asInt();
            int z = taskData.get("z").asInt();

            // Most prefabs are single block or small structures
            // For simplicity, assume 1-block size unless specified
            int size = taskData.has("size") ? taskData.get("size").asInt() : 1;
            
            return new BoundingBox(x, y, z, x + size - 1, y + size - 1, z + size - 1);
        }

        return null;
    }

    /**
     * Creates a bounding box from task data based on task type.
     */
    public static BoundingBox fromTaskData(TaskType taskType, JsonNode taskData) {
        if (taskData == null) {
            return null;
        }

        switch (taskType) {
            case BLOCK_SET:
                return fromBlockSetRequest(taskData);
            case BLOCK_FILL:
                return fromFillBoxRequest(taskData);
            case PREFAB_DOOR:
            case PREFAB_STAIRS:
            case PREFAB_WINDOW:
            case PREFAB_TORCH:
            case PREFAB_SIGN:
                return fromPrefabRequest(taskData);
            default:
                return null;
        }
    }

    // Getters and setters
    public int getMinX() {
        return minX;
    }

    public void setMinX(int minX) {
        this.minX = minX;
    }

    public int getMinY() {
        return minY;
    }

    public void setMinY(int minY) {
        this.minY = minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public void setMinZ(int minZ) {
        this.minZ = minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public void setMaxX(int maxX) {
        this.maxX = maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public void setMaxY(int maxY) {
        this.maxY = maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public void setMaxZ(int maxZ) {
        this.maxZ = maxZ;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BoundingBox that = (BoundingBox) obj;
        return minX == that.minX && minY == that.minY && minZ == that.minZ &&
               maxX == that.maxX && maxY == that.maxY && maxZ == that.maxZ;
    }

    @Override
    public int hashCode() {
        int result = minX;
        result = 31 * result + minY;
        result = 31 * result + minZ;
        result = 31 * result + maxX;
        result = 31 * result + maxY;
        result = 31 * result + maxZ;
        return result;
    }

    @Override
    public String toString() {
        return "BoundingBox{" +
                "minX=" + minX +
                ", minY=" + minY +
                ", minZ=" + minZ +
                ", maxX=" + maxX +
                ", maxY=" + maxY +
                ", maxZ=" + maxZ +
                '}';
    }
}