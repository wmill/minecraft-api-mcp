package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import ca.waltermiller.mcpapi.preview.BlockSink;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.block.RailBlock;
import net.minecraft.block.enums.RailShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RailRenderInspectionService {
    private final TaskExecutor taskExecutor;

    RailRenderInspectionService(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    List<Map<String, Object>> inspect(List<BuildTask> orderedTasks, BlockSink sink) {
        List<BuildTask> railTasks = orderedTasks.stream()
            .filter(task -> isRailTask(task.getTaskType()))
            .toList();
        if (railTasks.isEmpty()) {
            return List.of();
        }
        Map<BlockPos, List<RailPointOwner>> clearanceOwners = buildClearanceOwners(railTasks);

        List<Map<String, Object>> issues = new ArrayList<>();
        for (BuildTask task : railTasks) {
            checkSlopeStraightness(task, issues);
        }
        for (int i = 1; i < railTasks.size(); i++) {
            checkTaskJoin(railTasks.get(i - 1), railTasks.get(i), issues);
        }
        checkOtherTasksBlockingHeadroom(railTasks, orderedTasks, issues);

        if (sink == null) {
            return issues;
        }
        for (BuildTask task : railTasks) {
            TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(task, sink);
            if (!result.success()) {
                issues.add(issue(
                    "error",
                    "rail_dry_run_failed",
                    "Rail dry-run failed: " + result.errorMessage(),
                    task
                ));
            }
        }

        for (BuildTask task : railTasks) {
            inspectRenderedRailTask(task, sink, issues, clearanceOwners);
        }
        return issues;
    }

    private void inspectRenderedRailTask(BuildTask task,
                                         BlockSink sink,
                                         List<Map<String, Object>> issues,
                                         Map<BlockPos, List<RailPointOwner>> clearanceOwners) {
        List<TaskExecutor.RailPoint> points = parsePath(task);
        if (points.size() < 2) {
            return;
        }

        for (int i = 0; i < points.size(); i++) {
            TaskExecutor.RailPoint point = points.get(i);
            BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
            BlockState state = sink.getBlockState(pos);
            if (!isRailState(state)) {
                issues.add(issue(
                    "error",
                    "rail_missing_after_render",
                    "Rendered rail path is missing a rail block at " + formatPos(pos),
                    task
                ));
                continue;
            }

            RailShape expectedShape = TaskExecutor.determineRenderedRailShape(sink, pos);
            RailShape actualShape = getRailShape(state);
            if (expectedShape != null && actualShape != expectedShape) {
                issues.add(issue(
                    "error",
                    "rail_shape_mismatch",
                    "Rendered rail at " + formatPos(pos) + " has shape " + actualShape + " but expected " + expectedShape,
                    task
                ));
            }

            if (task.getTaskType() == TaskType.RAIL_TUNNEL_SEGMENT) {
                checkTunnelClearance(task, sink, pos, points, i, issues, clearanceOwners);
            }
        }
    }

    private void checkTunnelClearance(BuildTask task,
                                      BlockSink sink,
                                      BlockPos pos,
                                      List<TaskExecutor.RailPoint> path,
                                      int index,
                                      List<Map<String, Object>> issues,
                                      Map<BlockPos, List<RailPointOwner>> clearanceOwners) {
        if (isBlockedHeadroomCell(task, pos, pos.up(), sink, clearanceOwners)
            || isBlockedHeadroomCell(task, pos, pos.up(2), sink, clearanceOwners)) {
            issues.add(issue(
                "error",
                "tunnel_headroom_blocked",
                "Tunnel headroom is blocked at " + formatPos(pos),
                task
            ));
        }

        Direction portalDirection = getPortalDirection(path, index);
        if (portalDirection != null) {
            checkOpenFace(task, sink, pos, portalDirection, issues);
        }
    }

    private void checkOpenFace(BuildTask task,
                               BlockSink sink,
                               BlockPos pos,
                               Direction direction,
                               List<Map<String, Object>> issues) {
        BlockPos offset = switch (direction) {
            case NORTH -> new BlockPos(0, 0, -1);
            case SOUTH -> new BlockPos(0, 0, 1);
            case EAST -> new BlockPos(1, 0, 0);
            case WEST -> new BlockPos(-1, 0, 0);
            default -> null;
        };
        if (offset == null) {
            return;
        }

        for (int dy = 1; dy <= 2; dy++) {
            BlockPos facePos = pos.add(offset.getX(), dy, offset.getZ());
            if (isBlockingTunnelCell(sink.getBlockState(facePos))) {
                issues.add(issue(
                    "error",
                    "tunnel_open_face_blocked",
                    "Tunnel opening is blocked at " + formatPos(facePos) + " near " + formatPos(pos),
                    task
                ));
                return;
            }
        }
    }

    static Direction getPortalDirection(List<TaskExecutor.RailPoint> path, int index) {
        if (path.size() < 2) {
            return null;
        }

        if (index == 0) {
            TaskExecutor.RailPoint current = path.get(0);
            TaskExecutor.RailPoint next = path.get(1);
            if (next.y() != current.y()) {
                return null;
            }
            return directionBetween(
                new BlockPos(current.x(), current.y(), current.z()),
                new BlockPos(next.x(), next.y(), next.z())
            ).getOpposite();
        }

        if (index == path.size() - 1) {
            TaskExecutor.RailPoint previous = path.get(index - 1);
            TaskExecutor.RailPoint current = path.get(index);
            if (previous.y() != current.y()) {
                return null;
            }
            return directionBetween(
                new BlockPos(previous.x(), previous.y(), previous.z()),
                new BlockPos(current.x(), current.y(), current.z())
            );
        }

        return null;
    }

    private void checkSlopeStraightness(BuildTask task, List<Map<String, Object>> issues) {
        List<TaskExecutor.RailPoint> path = parsePath(task);
        for (int i = 1; i < path.size(); i++) {
            TaskExecutor.RailPoint a = path.get(i - 1);
            TaskExecutor.RailPoint b = path.get(i);
            int dx = b.x() - a.x();
            int dy = b.y() - a.y();
            int dz = b.z() - a.z();
            if (dy == 0) {
                continue;
            }
            boolean straightHorizontal = (Math.abs(dx) == 1 && dz == 0)
                || (dx == 0 && Math.abs(dz) == 1);
            if (!straightHorizontal || Math.abs(dy) != 1) {
                issues.add(issue(
                    "error",
                    "rail_sloped_step_not_straight",
                    "Sloped rail step from " + formatPoint(a) + " to " + formatPoint(b)
                        + " must change Y by exactly 1 along a single horizontal axis",
                    task
                ));
                continue;
            }
            if (isCornerTile(path, i - 1)) {
                issues.add(issue(
                    "error",
                    "rail_sloped_step_at_corner",
                    "Sloped rail step from " + formatPoint(a) + " to " + formatPoint(b)
                        + " starts at corner tile " + formatPoint(a)
                        + "; sloped rails must lie on straight sections",
                    task
                ));
            }
            if (isCornerTile(path, i)) {
                issues.add(issue(
                    "error",
                    "rail_sloped_step_at_corner",
                    "Sloped rail step from " + formatPoint(a) + " to " + formatPoint(b)
                        + " ends at corner tile " + formatPoint(b)
                        + "; sloped rails must lie on straight sections",
                    task
                ));
            }
        }
    }

    private static boolean isCornerTile(List<TaskExecutor.RailPoint> path, int index) {
        if (index <= 0 || index >= path.size() - 1) {
            return false;
        }
        Direction.Axis incoming = horizontalAxis(path.get(index - 1), path.get(index));
        Direction.Axis outgoing = horizontalAxis(path.get(index), path.get(index + 1));
        return incoming != null && outgoing != null && incoming != outgoing;
    }

    private static Direction.Axis horizontalAxis(TaskExecutor.RailPoint from, TaskExecutor.RailPoint to) {
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        if (dx != 0 && dz == 0) {
            return Direction.Axis.X;
        }
        if (dx == 0 && dz != 0) {
            return Direction.Axis.Z;
        }
        return null;
    }

    private void checkOtherTasksBlockingHeadroom(List<BuildTask> railTasks,
                                                 List<BuildTask> allTasks,
                                                 List<Map<String, Object>> issues) {
        List<BuildTask> nonRailTasks = allTasks.stream()
            .filter(task -> !isRailTask(task.getTaskType()))
            .toList();
        if (nonRailTasks.isEmpty()) {
            return;
        }

        for (BuildTask railTask : railTasks) {
            for (TaskExecutor.RailPoint point : parsePath(railTask)) {
                BoundingBox up1 = headroomCell(point, 1);
                BoundingBox up2 = headroomCell(point, 2);
                for (BuildTask other : nonRailTasks) {
                    BoundingBox otherBox = BoundingBox.fromTaskData(other.getTaskType(), other.getTaskData());
                    if (otherBox == null) {
                        continue;
                    }
                    if (!otherBox.intersects(up1) && !otherBox.intersects(up2)) {
                        continue;
                    }
                    Map<String, Object> issue = issue(
                        "error",
                        "rail_headroom_blocked_by_task",
                        "Task " + other.getTaskOrder() + " (" + other.getTaskType()
                            + ") occupies the headroom above rail at " + formatPoint(point),
                        railTask
                    );
                    issue.put("related_task_id", other.getId() != null ? other.getId().toString() : null);
                    issue.put("related_task_order", other.getTaskOrder());
                    issues.add(issue);
                }
            }
        }
    }

    private static BoundingBox headroomCell(TaskExecutor.RailPoint point, int dy) {
        int y = point.y() + dy;
        return new BoundingBox(point.x(), y, point.z(), point.x(), y, point.z());
    }

    private void checkTaskJoin(BuildTask previousTask, BuildTask currentTask, List<Map<String, Object>> issues) {
        List<TaskExecutor.RailPoint> previousPath = parsePath(previousTask);
        List<TaskExecutor.RailPoint> currentPath = parsePath(currentTask);
        if (previousPath.size() < 2 || currentPath.size() < 2) {
            return;
        }

        if (pathsJoinCleanly(previousPath, currentPath)) {
            return;
        }

        Map<String, Object> issue = issue(
            "error",
            "rail_segment_disconnected",
            "Rail segment does not connect cleanly to the previous segment",
            currentTask
        );
        issue.put("related_task_id", previousTask.getId().toString());
        issue.put("related_task_order", previousTask.getTaskOrder());
        issues.add(issue);
    }

    private boolean pathsJoinCleanly(List<TaskExecutor.RailPoint> previousPath, List<TaskExecutor.RailPoint> currentPath) {
        TaskExecutor.RailPoint previousLast = previousPath.get(previousPath.size() - 1);
        TaskExecutor.RailPoint currentFirst = currentPath.get(0);
        if (samePoint(previousLast, currentFirst)) {
            return true;
        }

        if (areAdjacent(previousLast, currentFirst)) {
            return true;
        }

        return previousPath.size() >= 2
            && currentPath.size() >= 2
            && samePoint(previousPath.get(previousPath.size() - 2), currentPath.get(0))
            && samePoint(previousPath.get(previousPath.size() - 1), currentPath.get(1));
    }

    private List<TaskExecutor.RailPoint> parsePath(BuildTask task) {
        List<TaskExecutor.RailPoint> points = new ArrayList<>();
        if (task.getTaskData() == null || !task.getTaskData().has("path")) {
            return points;
        }

        for (var point : task.getTaskData().get("path")) {
            points.add(new TaskExecutor.RailPoint(
                point.get("x").asInt(),
                point.get("y").asInt(),
                point.get("z").asInt()
            ));
        }
        return points;
    }

    private static boolean isRailTask(TaskType taskType) {
        return taskType == TaskType.RAIL_SURFACE_SEGMENT
            || taskType == TaskType.RAIL_BRIDGE_SEGMENT
            || taskType == TaskType.RAIL_TUNNEL_SEGMENT;
    }

    private static boolean isRailState(BlockState state) {
        return state.isOf(Blocks.RAIL) || state.isOf(Blocks.POWERED_RAIL);
    }

    private static boolean isBlockingTunnelCell(BlockState state) {
        return !state.isAir() && !isRailState(state);
    }

    private boolean isBlockedHeadroomCell(BuildTask task,
                                          BlockPos currentPos,
                                          BlockPos headroomPos,
                                          BlockSink sink,
                                          Map<BlockPos, List<RailPointOwner>> clearanceOwners) {
        if (!isBlockingTunnelCell(sink.getBlockState(headroomPos))) {
            return false;
        }

        List<RailPointOwner> owners = clearanceOwners.getOrDefault(headroomPos, List.of());
        for (RailPointOwner owner : owners) {
            if (!owner.taskId().equals(task.getId()) || !owner.matches(currentPos)) {
                return false;
            }
        }
        return true;
    }

    private Map<BlockPos, List<RailPointOwner>> buildClearanceOwners(List<BuildTask> railTasks) {
        Map<BlockPos, List<RailPointOwner>> owners = new HashMap<>();
        for (BuildTask task : railTasks) {
            for (TaskExecutor.RailPoint point : parsePath(task)) {
                RailPointOwner owner = new RailPointOwner(task.getId(), point.x(), point.y(), point.z());
                owners.computeIfAbsent(new BlockPos(point.x(), point.y() + 1, point.z()), ignored -> new ArrayList<>()).add(owner);
                owners.computeIfAbsent(new BlockPos(point.x(), point.y() + 2, point.z()), ignored -> new ArrayList<>()).add(owner);
            }
        }
        return owners;
    }

    private static Direction directionBetween(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static RailShape getRailShape(BlockState state) {
        if (state.contains(RailBlock.SHAPE)) {
            return state.get(RailBlock.SHAPE);
        }
        if (state.contains(PoweredRailBlock.SHAPE)) {
            return state.get(PoweredRailBlock.SHAPE);
        }
        return null;
    }

    private static boolean samePoint(TaskExecutor.RailPoint left, TaskExecutor.RailPoint right) {
        return left.x() == right.x() && left.y() == right.y() && left.z() == right.z();
    }

    private static boolean areAdjacent(TaskExecutor.RailPoint left, TaskExecutor.RailPoint right) {
        int dx = Math.abs(left.x() - right.x());
        int dy = Math.abs(left.y() - right.y());
        int dz = Math.abs(left.z() - right.z());
        return dx + dz == 1 && dy <= 1;
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String formatPoint(TaskExecutor.RailPoint point) {
        return "(" + point.x() + ", " + point.y() + ", " + point.z() + ")";
    }

    private static Map<String, Object> issue(String severity, String check, String message, BuildTask task) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("task_id", task.getId().toString());
        issue.put("task_order", task.getTaskOrder());
        issue.put("check", check);
        issue.put("message", message);
        return issue;
    }

    private record RailPointOwner(UUID taskId, int x, int y, int z) {
        boolean matches(BlockPos pos) {
            return x == pos.getX() && y == pos.getY() && z == pos.getZ();
        }
    }
}
