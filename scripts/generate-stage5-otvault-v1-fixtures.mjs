import { createCipheriv, createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

// Builds the `.otvault` v1 archive independently of the Kotlin codec: fixed
// inputs, fixed nonces, no clock and no randomness, so regenerating produces
// byte-identical resources.
//
// The archive key is a fixed raw AES-256 key rather than one derived from the
// export passphrase: Node ships no Argon2id, and the derivation itself is
// already proven by the Kotlin crypto tests. The header still pins the frozen
// Argon2id parameters (64 MiB, 3 iterations, parallelism 1, 16-byte salt) that
// every reader must accept.

const outputDirectory = join(
  process.cwd(),
  "core/data/src/test/resources/backup-format/otvault-v1",
);

const magic = Buffer.from("OPEN_TASKS_VAULT", "utf8");
const vaultId = "vault-alpha";
const createdAtEpochMillis = 1754000000000;
const coveredGeneration = 53;
const segmentGeneration = 54;
const blobSetId = "blob-set-1";
const maxChunksPerBlobSet = 25;
const key = Buffer.from(
  "000102030405060708090a0b0c0d0e0f" +
    "101112131415161718191a1b1c1d1e1f",
  "hex",
);
const tinkPrefix = Buffer.from("010000002a", "hex");
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

function u32(value) {
  const bytes = Buffer.alloc(4);
  bytes.writeUInt32BE(value);
  return bytes;
}

function prefixedUtf8(value) {
  const bytes = Buffer.from(value, "utf8");
  return Buffer.concat([u32(bytes.length), bytes]);
}

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

const semanticStatuses = [
  "BACKLOG",
  "PLANNED",
  "STARTED",
  "BLOCKED",
  "COMPLETED",
];

const statusRecords = semanticStatuses
  .map((semantic, index) =>
    record("WORKFLOW_STATUS", [`status-inbox-${semantic.toLowerCase()}`], [
      stringField("id", `status-inbox-${semantic.toLowerCase()}`),
      nullableStringField("projectId", null),
      stringField("name", `Inbox ${semantic.toLowerCase()}`),
      stringField("semanticStatus", semantic),
      stringField("rank", `inbox-${index}`),
      nullableLongField("archivedAtEpochMillis", null),
      longField("revisionWallMillis", 1),
      intField("revisionLogical", index),
      stringField("revisionDeviceId", "device-alpha"),
    ])
  )
  .sort((left, right) => (left.identity[0] < right.identity[0] ? -1 : 1));

const taskRecord = record("TASK", ["task-1"], [
  stringField("id", "task-1"),
  stringField("workspaceId", "workspace-1"),
  nullableStringField("projectId", null),
  nullableStringField("parentTaskId", null),
  stringField("statusId", "status-inbox-started"),
  stringField("semanticStatus", "STARTED"),
  stringField("title", "Résumé task 🚀"),
  bytesField("descriptionCiphertext", [0, 255]),
  stringField("priority", "HIGH"),
  nullableLongField("startEpochMillis", 1),
  nullableStringField("startZoneId", "UTC"),
  nullableLongField("dueEpochMillis", 2),
  nullableStringField("dueZoneId", "UTC"),
  nullableStringField("recurrenceFrequency", null),
  nullableIntField("recurrenceInterval", null),
  nullableStringField("recurrenceWeekdays", null),
  nullableIntField("recurrenceCount", null),
  nullableStringField("recurrenceEndDate", null),
  nullableStringField("recurrenceSeriesId", null),
  nullableLongField("recurrenceAnchorEpochMillis", null),
  nullableStringField("recurrenceAnchorZoneId", null),
  nullableIntField("recurrenceOccurrenceIndex", null),
  nullableLongField("estimateSeconds", 60),
  nullableStringField("milestoneId", null),
  nullableLongField("completedAtEpochMillis", null),
  nullableLongField("deletedAtEpochMillis", null),
  longField("revisionWallMillis", 10),
  intField("revisionLogical", 0),
  stringField("revisionDeviceId", "device-alpha"),
]);

const snapshotRecords = [
  record("VAULT", [vaultId], [
    stringField("id", vaultId),
    longField("createdAtEpochMillis", 1),
    intField("schemaVersion", 9),
    intField("cryptoVersion", 1),
    intField("minimumReaderVersion", 1),
  ]),
  record("WORKSPACE", ["workspace-1"], [
    stringField("id", "workspace-1"),
    stringField("vaultId", vaultId),
    stringField("ownerId", "member-1"),
    stringField("name", "Résumé workspace"),
  ]),
  record("MEMBER", ["member-1"], [
    stringField("id", "member-1"),
    stringField("displayName", "Member One"),
  ]),
  ...statusRecords,
  taskRecord,
  record("ATTACHMENT", ["attachment-1"], [
    stringField("id", "attachment-1"),
    stringField("taskId", "task-1"),
    bytesField("displayNameCiphertext", [0, 1]),
    stringField("mimeType", "text/plain"),
    longField("byteCount", 35),
    stringField("contentHash", "sha256:fixture"),
    nullableStringField("blobSetId", blobSetId),
    intField("chunkCount", 2),
    nullableLongField("deletedAtEpochMillis", null),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", 0),
    stringField("revisionDeviceId", "device-alpha"),
  ]),
];

const snapshot = {
  formatVersion: 1,
  minimumReaderVersion: 1,
  vaultId,
  coveredGeneration,
  records: snapshotRecords,
};

const taskMutation = {
  formatVersion: 1,
  minimumReaderVersion: 1,
  mutationKind: "UPSERT",
  record: taskRecord,
  deletedFamily: null,
  deletedIdentity: null,
};

const segment = {
  formatVersion: 1,
  minimumReaderVersion: 1,
  vaultId,
  firstGeneration: segmentGeneration,
  lastGeneration: segmentGeneration,
  entries: [
    {
      operationId: "operation-1",
      generation: segmentGeneration,
      sequence: 0,
      objectId: "task-1",
      objectType: "TASK",
      revisionWallMillis: 10,
      revisionLogical: 0,
      sourceDeviceId: "device-alpha",
      payloadBase64: Buffer.from(JSON.stringify(taskMutation), "utf8")
        .toString("base64")
        .replace(/=+$/, ""),
    },
  ],
  entryCount: 1,
};

const chunkPlaintexts = [
  Buffer.from("otvault-chunk-zero", "utf8"),
  Buffer.from("otvault-chunk-one", "utf8"),
];

const manifest = {
  blobSetId,
  contentSha256: sha256(Buffer.concat(chunkPlaintexts)),
  totalByteCount: chunkPlaintexts.reduce((total, chunk) => total + chunk.length, 0),
  chunks: chunkPlaintexts.map((chunk, index) => ({
    index,
    providerObjectId: `provider-chunk-${index}`,
    ciphertextSha256: (index === 0 ? "b" : "c").repeat(64),
    plaintextByteCount: chunk.length,
  })),
};

const headerPayload = {
  formatVersion: 1,
  vaultId,
  createdAtEpochMillis,
  recoveryEnvelope,
  recordCount: snapshotRecords.length,
  attachmentCount: 1,
};
const headerJson = JSON.stringify(headerPayload);
const headerBytes = Buffer.from(headerJson, "utf8");
const headerBlock = Buffer.concat([
  magic,
  u32(1),
  u32(headerBytes.length),
  headerBytes,
]);

function cloudObject(
  family,
  objectId,
  nonceHex,
  plaintext,
  chunkIndex = null,
  chunkCount = null,
) {
  const associatedData = Buffer.concat([
    "open-tasks:cloud-header-identity:v1",
    family,
    "1",
    "1",
    "1",
    vaultId,
    objectId,
    chunkIndex === null ? "" : String(chunkIndex),
    chunkCount === null ? "" : String(chunkCount),
  ].map(prefixedUtf8));
  const nonce = Buffer.from(nonceHex, "hex");
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(associatedData);
  const ciphertext = Buffer.concat([
    tinkPrefix,
    nonce,
    cipher.update(plaintext),
    cipher.final(),
    cipher.getAuthTag(),
  ]);
  const frameHeader = {
    magic: "OPEN_TASKS",
    family,
    schemaVersion: 1,
    cryptoVersion: 1,
    minimumReaderVersion: 1,
    vaultId,
    objectId,
    ciphertextLength: ciphertext.length,
    ciphertextSha256: sha256(ciphertext),
    chunkIndex,
    chunkCount,
  };
  const frameHeaderBytes = Buffer.from(JSON.stringify(frameHeader), "utf8");
  return {
    objectId,
    family,
    chunkIndex,
    chunkCount,
    nonceHex,
    associatedDataHex: associatedData.toString("hex"),
    plaintextSha256: sha256(plaintext),
    frame: Buffer.concat([u32(frameHeaderBytes.length), frameHeaderBytes, ciphertext]),
  };
}

const objects = [
  cloudObject(
    "SNAPSHOT",
    `otvault:snapshot:${coveredGeneration}`,
    "202122232425262728292a2b",
    Buffer.from(JSON.stringify(snapshot), "utf8"),
  ),
  cloudObject(
    "OPERATION_SEGMENT",
    `otvault:segment:${segmentGeneration}:${segmentGeneration}`,
    "303132333435363738393a3b",
    Buffer.from(JSON.stringify(segment), "utf8"),
  ),
  cloudObject(
    "MANIFEST",
    `otvault:attachment-manifest:${blobSetId}`,
    "404142434445464748494a4b",
    Buffer.from(JSON.stringify(manifest), "utf8"),
  ),
  ...chunkPlaintexts.map((plaintext, index) =>
    cloudObject(
      "ATTACHMENT_CHUNK",
      `otvault:attachment-chunk:${blobSetId}:${index}`,
      index === 0 ? "505152535455565758595a5b" : "606162636465666768696a6b",
      plaintext,
      index,
      maxChunksPerBlobSet,
    )
  ),
];

const inventoryEntries = objects.map((object) => ({
  objectId: object.objectId,
  family: object.family,
  sha256: sha256(object.frame),
  byteCount: object.frame.length,
}));
const inventoryPayload = {
  formatVersion: 1,
  vaultId,
  headerSha256: sha256(headerBlock),
  entryCount: inventoryEntries.length,
  entries: inventoryEntries,
};
const inventoryJson = JSON.stringify(inventoryPayload);
const inventory = cloudObject(
  "MANIFEST",
  "otvault:inventory",
  "707172737475767778797a7b",
  Buffer.from(inventoryJson, "utf8"),
);

function archiveOf(frames) {
  return Buffer.concat([
    headerBlock,
    ...frames.flatMap((frame) => [u32(frame.length), frame]),
  ]);
}

const frames = [...objects.map((object) => object.frame), inventory.frame];
const archive = archiveOf(frames);

// Re-authenticating a flipped ciphertext byte is impossible without the key, so
// the tampered frame repairs its own declared digest: only AES-GCM can catch it.
function tamperedFrame(frame) {
  const frameHeaderLength = frame.readUInt32BE(0);
  const ciphertext = Buffer.from(frame.subarray(4 + frameHeaderLength));
  ciphertext[ciphertext.length - 1] ^= 0x01;
  const frameHeader = JSON.parse(
    frame.subarray(4, 4 + frameHeaderLength).toString("utf8"),
  );
  frameHeader.ciphertextSha256 = sha256(ciphertext);
  const frameHeaderBytes = Buffer.from(JSON.stringify(frameHeader), "utf8");
  return Buffer.concat([u32(frameHeaderBytes.length), frameHeaderBytes, ciphertext]);
}

const corruptArchive = archiveOf([
  tamperedFrame(objects[0].frame),
  ...frames.slice(1),
]);
const truncatedArchive = archive.subarray(0, archive.length - 8);
const oversizedHeaderArchive = Buffer.concat([
  magic,
  u32(1),
  u32(16 * 1024 + 1),
  headerBytes.subarray(0, 64),
]);
const newerHeaderBytes = Buffer.from(
  JSON.stringify({ ...headerPayload, formatVersion: 2 }),
  "utf8",
);
const newerVersionArchive = Buffer.concat([
  magic,
  u32(2),
  u32(newerHeaderBytes.length),
  newerHeaderBytes,
  archive.subarray(headerBlock.length),
]);
const wrongMagicArchive = Buffer.concat([
  Buffer.from("OPEN_TASKS_OTHER", "utf8"),
  archive.subarray(magic.length),
]);

const fixture = {
  vaultId,
  createdAtEpochMillis,
  keyHex: key.toString("hex"),
  recordCount: headerPayload.recordCount,
  attachmentCount: headerPayload.attachmentCount,
  recoveryEnvelope,
  headerJson,
  headerSha256: inventoryPayload.headerSha256,
  snapshotJson: JSON.stringify(snapshot),
  segmentJson: JSON.stringify(segment),
  inventoryJson,
  manifest,
  chunkPlaintextHex: chunkPlaintexts.map((chunk) => chunk.toString("hex")),
  objects: [...objects, inventory].map((object) => ({
    objectId: object.objectId,
    family: object.family,
    chunkIndex: object.chunkIndex,
    chunkCount: object.chunkCount,
    nonceHex: object.nonceHex,
    associatedDataHex: object.associatedDataHex,
    plaintextSha256: object.plaintextSha256,
    frameSha256: sha256(object.frame),
    byteCount: object.frame.length,
  })),
  inventoryEntries,
  archiveByteCount: archive.length,
  archiveSha256: sha256(archive),
};

mkdirSync(outputDirectory, { recursive: true });
writeFileSync(join(outputDirectory, "archive.bin"), archive);
writeFileSync(join(outputDirectory, "corrupt-frame.bin"), corruptArchive);
writeFileSync(join(outputDirectory, "truncated.bin"), truncatedArchive);
writeFileSync(join(outputDirectory, "oversized-header.bin"), oversizedHeaderArchive);
writeFileSync(join(outputDirectory, "newer-version.bin"), newerVersionArchive);
writeFileSync(join(outputDirectory, "wrong-magic.bin"), wrongMagicArchive);
writeFileSync(
  join(outputDirectory, "archive.json"),
  `${JSON.stringify(fixture, null, 2)}\n`,
);
