# ARC replacement preparation

Use `arc:hotload prepare`, then `arc:hotload status`, from an authorized console.
Preparation stops gameplay admission and retires command executors, listeners,
services, channels and tasks while retaining this separate maintenance command.
Receipts distinguish storage, native retirement and the combined runtime stage.
`ready` confirms only ARC-owned cleanup. It does not certify that consumers have
released ARC API classes. `preparing` and `failed` forbid replacement.
Repeated preparation observes the same drain. No resume operation is provided.

Keep ArcCore and PacketEvents running throughout replacement. ARC itself
publishes services and API classes consumed by sibling plugins, including
sidebar, telemetry, item materialization and feature configuration. A stopped
consumer can still link to ARC classes through its classloader.

For a loader already validated for the entire dependency closure:

1. Prepare dependent consumers from leaves toward ARC and await every receipt.
2. Unload those consumers in the same order while ARC and its providers remain alive.
3. Prepare ARC, await `ready`, unload it and replace the exact verified JAR.
4. Load a fresh ARC classloader and verify dependency, persistence and recovery readiness.
5. Fresh-load consumers in dependency order and verify their readiness and saved state.

Never independently replace ARC while consumers are still loaded. Service
unregistration does not invalidate their references. A provider/API change or
an unvalidated dependency closure requires an ordinary coordinated restart.
Use only the separately validated external loader for the sequence. ARC does not embed
a loader. Full server restart remains the standard path when the dependency
ordering or loader compatibility is not established. Configuration reload and
same-instance disable/enable do not establish fresh-classloader compatibility.

The shared endpoint and source tests establish preparation only. Acceptance
still needs two complete replacement cycles in one process, one saved operation,
one late callback, exact artifact/version evidence and checks for old
registrations, threads and visible native owners.
