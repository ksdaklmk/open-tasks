import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const outputDirectory = join(
  process.cwd(),
  "core/data/src/test/resources/backup-format/v1",
);

function stringField(name, value) {
  return { name, type: "STRING", value };
}

function nullableStringField(name, value) {
  return value === null
    ? { name, type: "NULL", value: null }
    : stringField(name, value);
}

function longField(name, value) {
  return { name, type: "LONG", value: String(value) };
}

function nullableLongField(name, value) {
  return value === null
    ? { name, type: "NULL", value: null }
    : longField(name, value);
}

function intField(name, value) {
  return { name, type: "INT", value: String(value) };
}

function nullableIntField(name, value) {
  return value === null
    ? { name, type: "NULL", value: null }
    : intField(name, value);
}

function booleanField(name, value) {
  return { name, type: "BOOLEAN", value: String(value) };
}

function bytesField(name, bytes) {
  return {
    name,
    type: "BYTES",
    value: Buffer.from(bytes).toString("base64").replace(/=+$/, ""),
  };
}

function record(family, identity, fields) {
  return { family, identity, fields };
}

function status(id, projectId, name, semanticStatus, rank, logical) {
  return record("WORKFLOW_STATUS", [id], [
    stringField("id", id),
    nullableStringField("projectId", projectId),
    stringField("name", name),
    stringField("semanticStatus", semanticStatus),
    stringField("rank", rank),
    nullableLongField("archivedAtEpochMillis", null),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", logical),
    stringField("revisionDeviceId", "device-alpha"),
  ]);
}

function task(id, parentTaskId, statusId, semanticStatus, milestoneId) {
  const recurring = id === "task-1";
  return record("TASK", [id], [
    stringField("id", id),
    stringField("workspaceId", "workspace-1"),
    nullableStringField("projectId", "project-1"),
    nullableStringField("parentTaskId", parentTaskId),
    stringField("statusId", statusId),
    stringField("semanticStatus", semanticStatus),
    stringField("title", recurring ? "Résumé task 🚀" : "Child"),
    bytesField("descriptionCiphertext", [0, 255]),
    stringField("priority", "HIGH"),
    nullableLongField("startEpochMillis", 1),
    nullableStringField("startZoneId", "UTC"),
    nullableLongField("dueEpochMillis", 2),
    nullableStringField("dueZoneId", "UTC"),
    nullableStringField("recurrenceFrequency", recurring ? "WEEKLY" : null),
    nullableIntField("recurrenceInterval", recurring ? 1 : null),
    nullableStringField(
      "recurrenceWeekdays",
      recurring ? "MONDAY,FRIDAY" : null,
    ),
    nullableIntField("recurrenceCount", recurring ? 2 : null),
    nullableStringField(
      "recurrenceEndDate",
      null,
    ),
    nullableStringField(
      "recurrenceSeriesId",
      recurring ? "series-1" : null,
    ),
    nullableLongField(
      "recurrenceAnchorEpochMillis",
      recurring ? 1 : null,
    ),
    nullableStringField(
      "recurrenceAnchorZoneId",
      recurring ? "UTC" : null,
    ),
    nullableIntField(
      "recurrenceOccurrenceIndex",
      recurring ? 0 : null,
    ),
    nullableLongField("estimateSeconds", 60),
    nullableStringField("milestoneId", milestoneId),
    nullableLongField("completedAtEpochMillis", null),
    nullableLongField("deletedAtEpochMillis", null),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]);
}

const semanticStatuses = [
  "BACKLOG",
  "PLANNED",
  "STARTED",
  "BLOCKED",
  "COMPLETED",
];
const statuses = semanticStatuses.flatMap((semantic, index) => [
  status(
    `status-project-${semantic.toLowerCase()}`,
    "project-1",
    semantic[0] + semantic.slice(1).toLowerCase(),
    semantic,
    `a${index}`,
    index,
  ),
  status(
    `status-inbox-${semantic.toLowerCase()}`,
    null,
    `Inbox ${semantic.toLowerCase()}`,
    semantic,
    `b${index}`,
    index,
  ),
]);

const familyOrder = [
  "VAULT",
  "WORKSPACE",
  "MEMBER",
  "PROJECT",
  "WORKFLOW_STATUS",
  "MILESTONE",
  "TASK",
  "CHECKLIST_ITEM",
  "TASK_DEPENDENCY",
  "TAG",
  "TASK_TAG",
  "REMINDER",
  "ATTACHMENT",
  "ACTIVITY_ENTRY",
  "TIME_ENTRY",
  "TEMPLATE",
  "SAVED_VIEW",
  "TOMBSTONE",
];

const snapshotRecords = [
  record("VAULT", ["vault-alpha"], [
    stringField("id", "vault-alpha"),
    longField("createdAtEpochMillis", 1),
    intField("schemaVersion", 6),
    intField("cryptoVersion", 1),
    intField("minimumReaderVersion", 1),
  ]),
  record("WORKSPACE", ["workspace-1"], [
    stringField("id", "workspace-1"),
    stringField("vaultId", "vault-alpha"),
    stringField("ownerId", "member-1"),
    stringField("name", "Résumé workspace"),
  ]),
  record("MEMBER", ["member-1"], [
    stringField("id", "member-1"),
    stringField("displayName", "Member One"),
  ]),
  record("PROJECT", ["project-1"], [
    stringField("id", "project-1"),
    stringField("workspaceId", "workspace-1"),
    stringField("name", "Project"),
    stringField("summary", "Summary"),
    stringField("health", "ON_TRACK"),
    nullableStringField("dueDate", "2026-07-29"),
    intField("completedTasks", 0),
    intField("totalTasks", 2),
    nullableLongField("archivedAtEpochMillis", null),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]),
  ...statuses,
  record("MILESTONE", ["milestone-1"], [
    stringField("id", "milestone-1"),
    stringField("projectId", "project-1"),
    stringField("name", "Ship"),
    nullableStringField("dueDate", "2026-08-01"),
    nullableLongField("completedAtEpochMillis", null),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]),
  task(
    "task-1",
    null,
    "status-project-started",
    "STARTED",
    "milestone-1",
  ),
  task(
    "task-2",
    "task-1",
    "status-project-planned",
    "PLANNED",
    null,
  ),
  record("CHECKLIST_ITEM", ["check-1"], [
    stringField("id", "check-1"),
    stringField("taskId", "task-1"),
    stringField("text", "Check"),
    booleanField("completed", false),
    stringField("rank", "a0"),
  ]),
  record("TASK_DEPENDENCY", ["task-2", "task-1"], [
    stringField("taskId", "task-2"),
    stringField("dependsOnTaskId", "task-1"),
    longField("revisionWallMillis", 11),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]),
  record("TAG", ["tag-1"], [
    stringField("id", "tag-1"),
    stringField("workspaceId", "workspace-1"),
    stringField("name", "Urgent"),
  ]),
  record("TASK_TAG", ["task-1", "tag-1"], [
    stringField("taskId", "task-1"),
    stringField("tagId", "tag-1"),
    booleanField("present", true),
    longField("revisionWallMillis", 11),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]),
  record("REMINDER", ["reminder:task-1"], [
    stringField("id", "reminder:task-1"),
    stringField("taskId", "task-1"),
    longField("triggerAtEpochMillis", 20),
    stringField("zoneId", "UTC"),
    booleanField("precise", false),
  ]),
  record("ATTACHMENT", ["attachment-1"], [
    stringField("id", "attachment-1"),
    stringField("taskId", "task-1"),
    bytesField("displayNameCiphertext", [0, 1]),
    stringField("mimeType", "text/plain"),
    longField("byteCount", 2),
    stringField("contentHash", "sha256:fixture"),
    booleanField("keepOffline", false),
  ]),
  record("ACTIVITY_ENTRY", ["activity-1"], [
    stringField("id", "activity-1"),
    nullableStringField("taskId", "task-1"),
    nullableStringField("projectId", "project-1"),
    stringField("kind", "UPDATED"),
    bytesField("bodyCiphertext", [2, 3]),
    longField("createdAtEpochMillis", 21),
  ]),
  record("TIME_ENTRY", ["time-1"], [
    stringField("id", "time-1"),
    stringField("taskId", "task-1"),
    stringField("deviceId", "device-alpha"),
    longField("startedAtEpochMillis", 10),
    nullableLongField("stoppedAtEpochMillis", 20),
    bytesField("noteCiphertext", [4, 5]),
  ]),
  record("TEMPLATE", ["template-1"], [
    stringField("id", "template-1"),
    stringField("workspaceId", "workspace-1"),
    stringField("name", "Template"),
    bytesField("encryptedPayload", [6, 7]),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]),
  record("SAVED_VIEW", ["view-1"], [
    stringField("id", "view-1"),
    stringField("workspaceId", "workspace-1"),
    stringField("name", "View"),
    bytesField("encryptedQuery", [8, 9]),
  ]),
  record("TOMBSTONE", ["gone-task", "task"], [
    stringField("objectId", "gone-task"),
    stringField("objectType", "task"),
    longField("deletedAtEpochMillis", 1),
    longField("purgeAfterEpochMillis", 2),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]),
].sort((left, right) => {
  const family = familyOrder.indexOf(left.family) -
    familyOrder.indexOf(right.family);
  if (family !== 0) return family;
  for (let index = 0; index < Math.min(
    left.identity.length,
    right.identity.length,
  ); index += 1) {
    if (left.identity[index] < right.identity[index]) return -1;
    if (left.identity[index] > right.identity[index]) return 1;
  }
  return left.identity.length - right.identity.length;
});

const snapshot = {
  formatVersion: 1,
  minimumReaderVersion: 1,
  vaultId: "vault-alpha",
  coveredGeneration: 53,
  records: snapshotRecords,
};

const tagRecord = record("TAG", ["tag-1"], [
  stringField("id", "tag-1"),
  stringField("workspaceId", "workspace-1"),
  stringField("name", "Urgent"),
]);
const upsertMutation = {
  formatVersion: 1,
  minimumReaderVersion: 1,
  mutationKind: "UPSERT",
  record: tagRecord,
  deletedFamily: null,
  deletedIdentity: null,
};
const deleteMutation = {
  formatVersion: 1,
  minimumReaderVersion: 1,
  mutationKind: "DELETE",
  record: null,
  deletedFamily: "TASK_DEPENDENCY",
  deletedIdentity: ["task-2", "task-1"],
};

function payloadBase64(payload) {
  return Buffer.from(JSON.stringify(payload), "utf8")
    .toString("base64")
    .replace(/=+$/, "");
}

const segment = {
  formatVersion: 1,
  minimumReaderVersion: 1,
  vaultId: "vault-alpha",
  firstGeneration: 41,
  lastGeneration: 53,
  entries: [
    {
      operationId: "operation-1",
      generation: 41,
      sequence: 0,
      objectId: "tag-1",
      objectType: "TAG",
      revisionWallMillis: 9,
      revisionLogical: 2,
      sourceDeviceId: "device-alpha",
      payloadBase64: payloadBase64(upsertMutation),
    },
    {
      operationId: "operation-2",
      generation: 53,
      sequence: 0,
      objectId: "6:task-2|6:task-1",
      objectType: "TASK_DEPENDENCY",
      revisionWallMillis: 10,
      revisionLogical: 0,
      sourceDeviceId: "device-alpha",
      payloadBase64: payloadBase64(deleteMutation),
    },
  ],
  entryCount: 2,
};

function writeFixture(name, payload) {
  const plaintext = Buffer.from(JSON.stringify(payload), "utf8");
  const fixture = {
    plaintextUtf8Hex: plaintext.toString("hex"),
    plaintextSha256: createHash("sha256")
      .update(plaintext)
      .digest("hex"),
  };
  writeFileSync(
    join(outputDirectory, `${name}.json`),
    `${JSON.stringify(fixture, null, 2)}\n`,
  );
}

mkdirSync(outputDirectory, { recursive: true });
writeFixture("snapshot", snapshot);
writeFixture("operation-segment", segment);
