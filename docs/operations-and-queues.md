# Edit operations and task queues

World edits, prefabs, NBT placement, terrain smoothing, entities, fire, teleport,
messages, markers, reservations, and undo/redo register an operation before
dispatch. Responses include `X-Operation-Id`. Read-only queries and dry runs keep
their existing waiting behavior.

Edits which finish within the endpoint's wait window return their usual HTTP
status and JSON body. Otherwise the response is HTTP 202 with
`Location: /api/operations/{operation_id}` and a body such as:

```json
{"operation_id":"...","status":"queued","status_url":"/api/operations/..."}
```

Poll `GET /api/operations/{operation_id}`. Status is `queued`, `running`,
`succeeded`, `failed`, or `cancelled`. Terminal responses also include
`result_status` (the original HTTP status) and `result` (the original JSON body,
with reservation tokens redacted). A 202 response does not claim success.
Completion includes existing build recording and snapshot persistence. A failure
can still represent a partial world edit; preserve snapshot recovery markers.

Optional `Idempotency-Key` headers identify a logical mutation. An identical retry
reuses its operation. Reusing a key with a different method, path, canonical JSON
or multipart file/form payload, or area-lock context returns 409. Request object
field order and multipart boundary changes do not change identity. Keys must be
nonblank and at most 256 characters. Polling never exposes keys or request bodies,
and redacts reservation tokens unless the poller sends the operation's
`Idempotency-Key` header. A delayed reservation acquisition therefore obtains its
token by polling with its key (or by an identical keyed retry) within the
operation's lifetime; anyone else polling sees the redacted result.

Terminal records and their keys expire after one hour. The registry holds at most
10,000 records, never evicts active operations, and rejects new submissions with
503 when full. These guarantees last only within the current server process and
retention window. An unknown/expired operation returns 404: its outcome is unknown,
and that response must not trigger automatic resubmission.

MCP creates a key for each logical mutation, keeps it across a transport retry,
and polls (with that key) once per second for up to 120 seconds. After that it
returns a distinct `pending` result with its operation ID and instructions to use
`get_operation`, and remembers the key in process so `get_operation` can still
retrieve a reservation token.
Schematic and Starlark placement follow the same contract.

Shutdown closes admission and cancels callbacks that have not started. Cancelled
callbacks cannot mutate the world. Running edits retain their actual outcome.
Cleanup runs off the Minecraft server thread, lets background persistence settle,
waits at most ten seconds for owned workers, logs unresolved work, and then closes
PostgreSQL.

## Queue ordering and execution

Insertion positions and task orders are zero-based. An insertion beyond the queue
length appends. Legacy orders remain unchanged on reads and execution; the next
queue edit normalizes the queue to `0..N-1`. Edits preserve task IDs, payloads,
descriptions, coordinates, statuses, errors, and execution timestamps.

Append, insert, delete, reorder, task updates, translation, rail-plan append, and
execution/replay claims share a JDBC transaction and lock the build row before
checking its status. In-progress, completed, and reverted builds reject queue
edits. Reorder requires every existing task ID exactly once; it cannot introduce,
omit, duplicate, or rewrite tasks.

Execute/replay claims the queue and sets `IN_PROGRESS` before HTTP 202 is returned.
Duplicate claims return 409. Replay resets task status in that same transaction.
Execution state is process-local, so on startup (before any endpoint is served)
builds still `IN_PROGRESS` and tasks still `EXECUTING` are marked `FAILED`; they
were interrupted by a crash or a shutdown that outlived its deadline. Inspect the
region, then execute again (completed tasks are skipped) or replay.
World execution holds no database transaction. Each task is persisted as
`EXECUTING` before scheduling, and its final status is persisted only after actual
completion. HTTP deadlines do not fail executing tasks.

Fill volume is capped at 100,000 blocks, heightmaps at 10,000 points, and chunks at
64 blocks per dimension. Dimensions must be positive and end coordinates must
fit signed 32-bit coordinates. Subtraction and volume checks cannot wrap; fills
and heightmaps accept reversed corners. Fill previews use the same cap. Invalid
bounds fail before world access, dispatch, or allocation.
