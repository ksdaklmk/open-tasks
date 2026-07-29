import {
  createCipheriv,
  createHash,
} from "node:crypto";
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

const portableKey = Buffer.from(
  "000102030405060708090a0b0c0d0e0f" +
    "101112131415161718191a1b1c1d1e1f",
  "hex",
);
const tinkPrefix = Buffer.from("010000002a", "hex");
const portableProducedAt = 1754000000000;
const recoveryEnvelope = {
  formatVersion: 1,
  kdfAlgorithm: "ARGON2ID",
  memoryKiB: 65536,
  iterations: 3,
  parallelism: 1,
  saltBase64: "AAECAwQFBgcICQoLDA0ODw",
  nonceBase64: "EBESExQVFhcYGRob",
  wrappedKeysetBase64: "HB0eHyAhIiM",
};

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function prefixedUtf8(value) {
  const bytes = Buffer.from(value, "utf8");
  const prefix = Buffer.alloc(4);
  prefix.writeUInt32BE(bytes.length);
  return Buffer.concat([prefix, bytes]);
}

function cloudAssociatedData(family, objectId) {
  return Buffer.concat([
    "open-tasks:cloud-header-identity:v1",
    family,
    "1",
    "1",
    "1",
    "vault-alpha",
    objectId,
    "",
    "",
  ].map(prefixedUtf8));
}

function authenticatedFrame(family, objectId, nonce, plaintext) {
  const cipher = createCipheriv("aes-256-gcm", portableKey, nonce);
  cipher.setAAD(cloudAssociatedData(family, objectId));
  const encrypted = Buffer.concat([
    cipher.update(plaintext),
    cipher.final(),
  ]);
  const ciphertext = Buffer.concat([
    tinkPrefix,
    nonce,
    encrypted,
    cipher.getAuthTag(),
  ]);
  const header = {
    magic: "OPEN_TASKS",
    family,
    schemaVersion: 1,
    cryptoVersion: 1,
    minimumReaderVersion: 1,
    vaultId: "vault-alpha",
    objectId,
    ciphertextLength: ciphertext.length,
    ciphertextSha256: sha256(ciphertext),
    chunkIndex: null,
    chunkCount: null,
  };
  const headerBytes = Buffer.from(JSON.stringify(header), "utf8");
  const headerLength = Buffer.alloc(4);
  headerLength.writeUInt32BE(headerBytes.length);
  return Buffer.concat([headerLength, headerBytes, ciphertext]);
}

function portableRecordCounts() {
  const counts = new Map(familyOrder.map((family) => [family, 0]));
  for (const item of snapshot.records) {
    counts.set(item.family, counts.get(item.family) + 1);
  }
  return familyOrder.map((family) => ({
    family,
    count: counts.get(family),
  }));
}

function writePortableFixture() {
  const snapshotJson = JSON.stringify(snapshot);
  const snapshotFrame = authenticatedFrame(
    "SNAPSHOT",
    `snapshot:${snapshot.coveredGeneration}`,
    Buffer.from("202122232425262728292a2b", "hex"),
    Buffer.from(snapshotJson, "utf8"),
  );
  const recoveryEnvelopeJson = JSON.stringify(recoveryEnvelope);
  const manifest = {
    packageVersion: 1,
    minimumReaderVersion: 1,
    vaultId: "vault-alpha",
    generation: snapshot.coveredGeneration,
    producedAtEpochMillis: portableProducedAt,
    recoveryEnvelopeSha256: sha256(Buffer.from(recoveryEnvelopeJson, "utf8")),
    snapshotObjectId: `snapshot:${snapshot.coveredGeneration}`,
    snapshotFrameLength: snapshotFrame.length,
    snapshotFrameSha256: sha256(snapshotFrame),
    recordCounts: portableRecordCounts(),
  };
  const manifestJson = JSON.stringify(manifest);
  const manifestFrame = authenticatedFrame(
    "MANIFEST",
    `portable-manifest:${snapshot.coveredGeneration}`,
    Buffer.from("101112131415161718191a1b", "hex"),
    Buffer.from(manifestJson, "utf8"),
  );
  let bootstrap = {
    magic: "OPEN_TASKS_PORTABLE",
    packageVersion: 1,
    minimumReaderVersion: 1,
    vaultId: "vault-alpha",
    generation: snapshot.coveredGeneration,
    producedAtEpochMillis: portableProducedAt,
    recoveryEnvelope,
    manifestFrameLength: manifestFrame.length,
    manifestFrameSha256: sha256(manifestFrame),
    snapshotFrameLength: snapshotFrame.length,
    snapshotFrameSha256: sha256(snapshotFrame),
    totalPackageLength: 0,
  };
  for (let pass = 0; pass < 8; pass += 1) {
    const length = Buffer.byteLength(JSON.stringify(bootstrap), "utf8");
    bootstrap = {
      ...bootstrap,
      totalPackageLength: 4 + length + manifestFrame.length +
        snapshotFrame.length,
    };
  }
  const bootstrapJson = JSON.stringify(bootstrap);
  const bootstrapBytes = Buffer.from(bootstrapJson, "utf8");
  const bootstrapLength = Buffer.alloc(4);
  bootstrapLength.writeUInt32BE(bootstrapBytes.length);
  const packageBytes = Buffer.concat([
    bootstrapLength,
    bootstrapBytes,
    manifestFrame,
    snapshotFrame,
  ]);
  if (packageBytes.length !== bootstrap.totalPackageLength) {
    throw new Error("Portable fixture length did not stabilise");
  }
  const fixture = {
    keyHex: portableKey.toString("hex"),
    manifestNonceHex: "101112131415161718191a1b",
    snapshotNonceHex: "202122232425262728292a2b",
    bootstrapJson,
    manifestJson,
    snapshotJson,
    recoveryEnvelopeSha256: manifest.recoveryEnvelopeSha256,
    manifestFrameSha256: bootstrap.manifestFrameSha256,
    snapshotFrameSha256: bootstrap.snapshotFrameSha256,
    packageSha256: sha256(packageBytes),
    manifestFrameHex: manifestFrame.toString("hex"),
    snapshotFrameHex: snapshotFrame.toString("hex"),
    packageHex: packageBytes.toString("hex"),
  };
  writeFileSync(
    join(outputDirectory, "portable-package.json"),
    `${JSON.stringify(fixture, null, 2)}\n`,
  );
}

mkdirSync(outputDirectory, { recursive: true });
writeFixture("snapshot", snapshot);
writeFixture("operation-segment", segment);
writePortableFixture();
