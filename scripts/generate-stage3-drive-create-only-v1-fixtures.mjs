// Independent Stage 3 create-only ownership and publication fixtures.
//
// The Kotlin codecs are never consulted here. Every byte is constructed from
// the frozen format description alone:
//
//   4-byte unsigned big-endian public JSON length
//   canonical public JSON bytes (ownership header or publication bootstrap)
//   authenticated cloud frame bytes (Stage 1 length-prefixed header + Tink
//   AES-256-GCM ciphertext over the canonical claim or manifest JSON)
//
// Keys and nonces are fixed constants so regeneration is byte-identical:
//
//   contentKey  = 000102...1f            (32-byte AES-256 vault content key)
//   nonce_<n>   = one fixed 12-byte IV per encrypted frame
//
// The authenticated frame's associated data is the Stage 1 cloud header
// identity encoding: each of
//   "open-tasks:cloud-header-identity:v1", family, schemaVersion,
//   cryptoVersion, minimumReaderVersion, vaultId (the lineage), objectId (the
//   claim or publication), chunkIndex, chunkCount
// encoded as a 4-byte big-endian UTF-8 length followed by its UTF-8 bytes.
//
// The publication bootstrap cannot digest the frame that digests it, so a
// manifest binds its bootstrap through the bootstrap *binding pre-image*: the
// canonical bootstrap JSON with encryptedFrameLength 0 and a 64-zero digest.
//
// Every produced file is parsed, re-canonicalised, length-checked,
// digest-checked, decrypted, and cross-checked against the ownership and
// publication chain before anything is written.

import { createCipheriv, createDecipheriv, createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const outputDirectory = join(
  process.cwd(),
  "core/data/src/test/resources/backup-format/drive-create-only-v1",
);

const MAX_PUBLIC_JSON_BYTES = 16 * 1024;
const MAX_PLAINTEXT_BYTES = 1024 * 1024 - 33;
const MAX_RECOVERY_ENVELOPE_BYTES = 16 * 1024;
const TINK_PREFIX = Buffer.from("010000002a", "hex");
const ZERO_SHA256 = "0".repeat(64);

const contentKey = Buffer.from(
  "000102030405060708090a0b0c0d0e0f" +
    "101112131415161718191a1b1c1d1e1f",
  "hex",
);

const nonces = {
  baselinePublication: Buffer.from("300102030405060708090a0b", "hex"),
  rootClaim: Buffer.from("310102030405060708090a0b", "hex"),
  successorPublication: Buffer.from("320102030405060708090a0b", "hex"),
  epochTwoBaseline: Buffer.from("330102030405060708090a0b", "hex"),
  successorClaim: Buffer.from("340102030405060708090a0b", "hex"),
  tombstone: Buffer.from("350102030405060708090a0b", "hex"),
};

const lineageId = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
const sourceVaultId = "7a948eda-84f7-4d91-b95b-2cd46f8fbcf7";
const deviceOne = "4e715bed-51d4-4a6e-9628-ffa13f5c89d4";
const deviceTwo = "5f826cfe-62e5-4b7f-a739-0ab24f6d9ae5";
const rootClaimId = "1b4e28ba-2fa1-4d3b-a3f5-ccbe0e2f56a1";
const successorClaimId = "2c5f39cb-3fb2-4e4c-b406-ddcf1f3a67b2";
const tombstoneClaimId = "3d604adc-40c3-4f5d-c517-eee02f4b78c3";
const tombstoneId = "0d1c2b3a-4958-4677-8695-a4b3c2d1e0f9";
const baselinePublicationId = "8ba59feb-95f8-4ea2-da6c-3de57f0acdf8";
const successorPublicationId = "9cb6a0fc-a609-4fb3-eb7d-4ef6801bdef9";
const epochTwoBaselineId = "adc7b10d-b71a-4ac4-fc8e-5f07912cef0a";
const rootOperationId = "b0c1d2e3-f405-4617-8829-a0b1c2d3e4f5";
const successorOperationId = "c1d2e3f4-0516-4728-9930-b1c2d3e4f506";
const tombstoneOperationId = "d2e3f405-1627-4839-aa41-c2d3e4f50617";
const baselineOperationId = "e3f40516-2738-494a-bb52-d3e4f5061728";
const epochTwoOperationId = "f4051627-3849-4a5b-8c63-e4f506172839";

const rootProviderFileId = "drive-root-claim-0000000000000001";
const successorProviderFileId = "drive-successor-claim-0000000000002";
const baselineProviderFileId = "drive-publication-0000000000000010";
const successorPublicationProviderFileId = "drive-publication-0000000000000011";
const epochTwoBaselineProviderFileId = "drive-publication-0000000000000012";
const epochTwoSuccessorSlotProviderFileId = "drive-reserved-slot-00000000000005";

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

function utf8(value) {
  return Buffer.from(value, "utf8");
}

function prefixedUtf8(value) {
  const bytes = utf8(value);
  const prefix = Buffer.alloc(4);
  prefix.writeUInt32BE(bytes.length);
  return Buffer.concat([prefix, bytes]);
}

function cloudAssociatedData(vaultId, objectId) {
  return Buffer.concat([
    "open-tasks:cloud-header-identity:v1",
    "MANIFEST",
    "1",
    "1",
    "1",
    vaultId,
    objectId,
    "",
    "",
  ].map(prefixedUtf8));
}

function authenticatedFrame(vaultId, objectId, nonce, plaintext) {
  if (plaintext.length > MAX_PLAINTEXT_BYTES) {
    throw new Error("Plaintext exceeds the manifest plaintext bound");
  }
  const associatedData = cloudAssociatedData(vaultId, objectId);
  const cipher = createCipheriv("aes-256-gcm", contentKey, nonce);
  cipher.setAAD(associatedData);
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const ciphertext = Buffer.concat([
    TINK_PREFIX,
    nonce,
    encrypted,
    cipher.getAuthTag(),
  ]);
  const header = {
    magic: "OPEN_TASKS",
    family: "MANIFEST",
    schemaVersion: 1,
    cryptoVersion: 1,
    minimumReaderVersion: 1,
    vaultId,
    objectId,
    ciphertextLength: ciphertext.length,
    ciphertextSha256: sha256(ciphertext),
    chunkIndex: null,
    chunkCount: null,
  };
  const headerBytes = utf8(JSON.stringify(header));
  if (headerBytes.length > MAX_PUBLIC_JSON_BYTES) {
    throw new Error("Cloud frame header exceeds its bound");
  }
  const headerLength = Buffer.alloc(4);
  headerLength.writeUInt32BE(headerBytes.length);
  return Buffer.concat([headerLength, headerBytes, ciphertext]);
}

function decryptFrame(vaultId, objectId, frame) {
  const headerLength = frame.readUInt32BE(0);
  const header = JSON.parse(
    frame.subarray(4, 4 + headerLength).toString("utf8"),
  );
  if (header.vaultId !== vaultId || header.objectId !== objectId) {
    throw new Error("Cloud frame identity mismatch");
  }
  const ciphertext = frame.subarray(4 + headerLength);
  if (ciphertext.length !== header.ciphertextLength) {
    throw new Error("Cloud frame ciphertext length mismatch");
  }
  if (sha256(ciphertext) !== header.ciphertextSha256) {
    throw new Error("Cloud frame ciphertext checksum mismatch");
  }
  const nonce = ciphertext.subarray(
    TINK_PREFIX.length,
    TINK_PREFIX.length + 12,
  );
  const body = ciphertext.subarray(
    TINK_PREFIX.length + 12,
    ciphertext.length - 16,
  );
  const tag = ciphertext.subarray(ciphertext.length - 16);
  const decipher = createDecipheriv("aes-256-gcm", contentKey, nonce);
  decipher.setAAD(cloudAssociatedData(vaultId, objectId));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(body), decipher.final()]);
}

function publicFile(publicJson, frame) {
  const publicBytes = utf8(publicJson);
  if (publicBytes.length > MAX_PUBLIC_JSON_BYTES) {
    throw new Error("Public JSON exceeds its 16 KiB bound");
  }
  const prefix = Buffer.alloc(4);
  prefix.writeUInt32BE(publicBytes.length);
  return Buffer.concat([prefix, publicBytes, frame]);
}

function ownershipHeader(claim, frame) {
  const role = claim.state === "TERMINATED"
    ? "OWNERSHIP_TOMBSTONE"
    : claim.predecessorProviderFileId === null
      ? "OWNERSHIP_ROOT"
      : "OWNERSHIP_CLAIM";
  return {
    magic: "OPEN_TASKS_OWNERSHIP",
    formatVersion: 1,
    minimumReaderVersion: 1,
    lineageId: claim.lineageId,
    claimId: claim.claimId,
    writerEpoch: claim.writerEpoch,
    state: claim.state,
    role,
    providerFileId: claim.providerFileId,
    nextSuccessorProviderFileId: claim.nextSuccessorProviderFileId,
    encryptedFrameLength: frame.length,
    encryptedFrameSha256: sha256(frame),
  };
}

function ownershipFile(claim, nonce) {
  const claimJson = JSON.stringify(claim);
  const frame = authenticatedFrame(
    claim.lineageId,
    claim.claimId,
    nonce,
    utf8(claimJson),
  );
  const headerJson = JSON.stringify(ownershipHeader(claim, frame));
  const fileBytes = publicFile(headerJson, frame);
  return {
    keyHex: contentKey.toString("hex"),
    nonceHex: nonce.toString("hex"),
    headerJson,
    claimJson,
    frameSha256: sha256(frame),
    fileSha256: sha256(fileBytes),
    frameHex: frame.toString("hex"),
    fileHex: fileBytes.toString("hex"),
  };
}

function bindingBootstrap(manifest) {
  return {
    magic: "OPEN_TASKS_PUBLICATION",
    formatVersion: 1,
    minimumReaderVersion: 1,
    lineageId: manifest.lineageId,
    writerEpoch: manifest.writerEpoch,
    plannedClaimProviderFileId: manifest.plannedClaimProviderFileId,
    recoveryEnvelope,
    recoveryCredentialGeneration: manifest.recoveryCredentialGeneration,
    encryptedFrameLength: 0,
    encryptedFrameSha256: ZERO_SHA256,
  };
}

function bootstrapSha256(manifest) {
  return sha256(utf8(JSON.stringify(bindingBootstrap(manifest))));
}

function publicationFile(draft, nonce) {
  const manifest = { ...draft, bootstrapSha256: bootstrapSha256(draft) };
  const manifestJson = JSON.stringify(manifest);
  const frame = authenticatedFrame(
    manifest.lineageId,
    manifest.publicationId,
    nonce,
    utf8(manifestJson),
  );
  const bootstrap = {
    ...bindingBootstrap(manifest),
    encryptedFrameLength: frame.length,
    encryptedFrameSha256: sha256(frame),
  };
  const bootstrapJson = JSON.stringify(bootstrap);
  const fileBytes = publicFile(bootstrapJson, frame);
  return {
    keyHex: contentKey.toString("hex"),
    nonceHex: nonce.toString("hex"),
    bootstrapJson,
    manifestJson,
    recoveryEnvelopeJson: JSON.stringify(recoveryEnvelope),
    bootstrapSha256: manifest.bootstrapSha256,
    frameSha256: sha256(frame),
    fileSha256: sha256(fileBytes),
    frameHex: frame.toString("hex"),
    fileHex: fileBytes.toString("hex"),
  };
}

function inventoryItem(logicalObjectId, role, firstGeneration, lastGeneration) {
  const providerFileId = `drive-object-${logicalObjectId}`;
  return {
    logicalObjectId,
    providerFileId,
    role,
    firstGeneration,
    lastGeneration,
    frameLength: role === "SNAPSHOT" ? 262_144 : 8_192,
    frameSha256: sha256(utf8(`open-tasks:fixture-object:${logicalObjectId}`)),
  };
}

function manifestDraft(fields) {
  return {
    formatVersion: 1,
    minimumReaderVersion: 1,
    bootstrapSha256: ZERO_SHA256,
    lineageId,
    sourceVaultId,
    writerEpoch: fields.writerEpoch,
    activeDeviceId: fields.activeDeviceId,
    publicationProviderFileId: fields.publicationProviderFileId,
    publicationId: fields.publicationId,
    publicationSequence: fields.publicationSequence,
    predecessorPublicationProviderFileId:
      fields.predecessorPublicationProviderFileId ?? null,
    predecessorPublicationId: fields.predecessorPublicationId ?? null,
    predecessorPublicationSha256: fields.predecessorPublicationSha256 ?? null,
    baseline: fields.publicationSequence === 0,
    plannedClaimProviderFileId: fields.plannedClaimProviderFileId ?? null,
    plannedClaimId: fields.plannedClaimId ?? null,
    predecessorClaimProviderFileId: fields.predecessorClaimProviderFileId ?? null,
    predecessorClaimId: fields.predecessorClaimId ?? null,
    predecessorClaimSha256: fields.predecessorClaimSha256 ?? null,
    ownershipClaimProviderFileId: fields.ownershipClaimProviderFileId ?? null,
    ownershipClaimId: fields.ownershipClaimId ?? null,
    ownershipClaimSha256: fields.ownershipClaimSha256 ?? null,
    localGeneration: fields.localGeneration,
    publicationOperationId: fields.publicationOperationId,
    currentBaseObjectId: fields.currentBaseObjectId,
    fallbackBaseObjectId: fields.fallbackBaseObjectId,
    inventory: fields.inventory,
    recoveryCredentialGeneration: fields.recoveryCredentialGeneration,
  };
}

function claim(fields) {
  return {
    formatVersion: 1,
    minimumReaderVersion: 1,
    lineageId,
    writerEpoch: fields.writerEpoch,
    state: fields.state,
    predecessorProviderFileId: fields.predecessorProviderFileId ?? null,
    predecessorClaimId: fields.predecessorClaimId ?? null,
    predecessorClaimSha256: fields.predecessorClaimSha256 ?? null,
    providerFileId: fields.providerFileId,
    claimId: fields.claimId,
    predecessorReservedSuccessorProviderFileId:
      fields.predecessorReservedSuccessorProviderFileId ?? null,
    sourceVaultId: fields.sourceVaultId ?? null,
    activeDeviceId: fields.activeDeviceId ?? null,
    nextSuccessorProviderFileId: fields.nextSuccessorProviderFileId ?? null,
    baselinePublicationProviderFileId:
      fields.baselinePublicationProviderFileId ?? null,
    baselinePublicationId: fields.baselinePublicationId ?? null,
    baselinePublicationSha256: fields.baselinePublicationSha256 ?? null,
    recoveryCredentialGeneration: fields.recoveryCredentialGeneration ?? null,
    creationOperationId: fields.creationOperationId,
    tombstoneId: fields.tombstoneId ?? null,
  };
}

// Epoch one: the baseline publication is created before the ownership root it
// plans, so the root can digest it without a cycle.
const baselineInventory = [
  inventoryItem("base-current-epoch1", "SNAPSHOT", 40, 40),
  inventoryItem("base-fallback-epoch1", "SNAPSHOT", 40, 40),
];
const baselinePublication = publicationFile(
  manifestDraft({
    writerEpoch: 1,
    activeDeviceId: deviceOne,
    publicationProviderFileId: baselineProviderFileId,
    publicationId: baselinePublicationId,
    publicationSequence: 0,
    plannedClaimProviderFileId: rootProviderFileId,
    plannedClaimId: rootClaimId,
    localGeneration: 40,
    publicationOperationId: baselineOperationId,
    currentBaseObjectId: "base-current-epoch1",
    fallbackBaseObjectId: "base-fallback-epoch1",
    inventory: baselineInventory,
    recoveryCredentialGeneration: 1,
  }),
  nonces.baselinePublication,
);

const rootClaim = ownershipFile(
  claim({
    writerEpoch: 1,
    state: "ACTIVE",
    providerFileId: rootProviderFileId,
    claimId: rootClaimId,
    sourceVaultId,
    activeDeviceId: deviceOne,
    nextSuccessorProviderFileId: successorProviderFileId,
    baselinePublicationProviderFileId: baselineProviderFileId,
    baselinePublicationId: baselinePublicationId,
    baselinePublicationSha256: baselinePublication.fileSha256,
    recoveryCredentialGeneration: 1,
    creationOperationId: rootOperationId,
  }),
  nonces.rootClaim,
);

const successorPublication = publicationFile(
  manifestDraft({
    writerEpoch: 1,
    activeDeviceId: deviceOne,
    publicationProviderFileId: successorPublicationProviderFileId,
    publicationId: successorPublicationId,
    publicationSequence: 1,
    predecessorPublicationProviderFileId: baselineProviderFileId,
    predecessorPublicationId: baselinePublicationId,
    predecessorPublicationSha256: baselinePublication.fileSha256,
    ownershipClaimProviderFileId: rootProviderFileId,
    ownershipClaimId: rootClaimId,
    ownershipClaimSha256: rootClaim.fileSha256,
    localGeneration: 42,
    publicationOperationId: successorOperationId,
    currentBaseObjectId: "base-current-epoch1",
    fallbackBaseObjectId: "base-fallback-epoch1",
    inventory: [
      ...baselineInventory,
      inventoryItem("segment-41", "SEGMENT", 41, 41),
      inventoryItem("segment-42", "SEGMENT", 42, 42),
    ],
    recoveryCredentialGeneration: 1,
  }),
  nonces.successorPublication,
);

// Epoch two: a takeover baseline the successor claim digests. It is a complete
// valid publication but is not part of the five emitted fixtures, because the
// emitted publication pair belongs to epoch one.
const epochTwoBaseline = publicationFile(
  manifestDraft({
    writerEpoch: 2,
    activeDeviceId: deviceTwo,
    publicationProviderFileId: epochTwoBaselineProviderFileId,
    publicationId: epochTwoBaselineId,
    publicationSequence: 0,
    plannedClaimProviderFileId: successorProviderFileId,
    plannedClaimId: successorClaimId,
    predecessorClaimProviderFileId: rootProviderFileId,
    predecessorClaimId: rootClaimId,
    predecessorClaimSha256: rootClaim.fileSha256,
    localGeneration: 42,
    publicationOperationId: epochTwoOperationId,
    currentBaseObjectId: "base-current-epoch2",
    fallbackBaseObjectId: "base-fallback-epoch2",
    inventory: [
      inventoryItem("base-current-epoch2", "SNAPSHOT", 42, 42),
      inventoryItem("base-fallback-epoch2", "SNAPSHOT", 42, 42),
    ],
    recoveryCredentialGeneration: 2,
  }),
  nonces.epochTwoBaseline,
);

const successorClaim = ownershipFile(
  claim({
    writerEpoch: 2,
    state: "ACTIVE",
    predecessorProviderFileId: rootProviderFileId,
    predecessorClaimId: rootClaimId,
    predecessorClaimSha256: rootClaim.fileSha256,
    providerFileId: successorProviderFileId,
    claimId: successorClaimId,
    predecessorReservedSuccessorProviderFileId: successorProviderFileId,
    sourceVaultId,
    activeDeviceId: deviceTwo,
    nextSuccessorProviderFileId: epochTwoSuccessorSlotProviderFileId,
    baselinePublicationProviderFileId: epochTwoBaselineProviderFileId,
    baselinePublicationId: epochTwoBaselineId,
    baselinePublicationSha256: epochTwoBaseline.fileSha256,
    recoveryCredentialGeneration: 2,
    creationOperationId: successorOperationId,
  }),
  nonces.successorClaim,
);

const terminatedClaim = ownershipFile(
  claim({
    writerEpoch: 3,
    state: "TERMINATED",
    predecessorProviderFileId: successorProviderFileId,
    predecessorClaimId: successorClaimId,
    predecessorClaimSha256: successorClaim.fileSha256,
    providerFileId: epochTwoSuccessorSlotProviderFileId,
    claimId: tombstoneClaimId,
    predecessorReservedSuccessorProviderFileId:
      epochTwoSuccessorSlotProviderFileId,
    creationOperationId: tombstoneOperationId,
    tombstoneId,
  }),
  nonces.tombstone,
);

function require(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function verifyOwnership(fixture) {
  const fileBytes = Buffer.from(fixture.fileHex, "hex");
  require(sha256(fileBytes) === fixture.fileSha256, "Claim digest mismatch");
  const headerLength = fileBytes.readUInt32BE(0);
  const headerJson = fileBytes.subarray(4, 4 + headerLength).toString("utf8");
  require(headerJson === fixture.headerJson, "Claim header slice mismatch");
  const header = JSON.parse(headerJson);
  require(
    JSON.stringify(header) === headerJson,
    "Claim header is not canonical",
  );
  const frame = fileBytes.subarray(4 + headerLength);
  require(frame.length === header.encryptedFrameLength, "Claim frame length");
  require(sha256(frame) === header.encryptedFrameSha256, "Claim frame digest");
  require(
    4 + headerLength + header.encryptedFrameLength === fileBytes.length,
    "Claim file length",
  );
  const plaintext = decryptFrame(header.lineageId, header.claimId, frame);
  require(
    plaintext.toString("utf8") === fixture.claimJson,
    "Claim plaintext mismatch",
  );
  const claimValue = JSON.parse(fixture.claimJson);
  require(
    JSON.stringify(claimValue) === fixture.claimJson,
    "Claim is not canonical",
  );
  require(
    JSON.stringify(ownershipHeader(claimValue, frame)) === headerJson,
    "Claim header does not restate the authenticated claim",
  );
  return { header, claim: claimValue, fileSha256: fixture.fileSha256 };
}

function verifyPublication(fixture) {
  const fileBytes = Buffer.from(fixture.fileHex, "hex");
  require(
    sha256(fileBytes) === fixture.fileSha256,
    "Publication digest mismatch",
  );
  const bootstrapLength = fileBytes.readUInt32BE(0);
  const bootstrapJson = fileBytes
    .subarray(4, 4 + bootstrapLength)
    .toString("utf8");
  require(
    bootstrapJson === fixture.bootstrapJson,
    "Publication bootstrap slice mismatch",
  );
  const bootstrap = JSON.parse(bootstrapJson);
  require(
    JSON.stringify(bootstrap) === bootstrapJson,
    "Publication bootstrap is not canonical",
  );
  require(
    utf8(JSON.stringify(bootstrap.recoveryEnvelope)).length <=
      MAX_RECOVERY_ENVELOPE_BYTES,
    "Recovery envelope exceeds its bound",
  );
  const frame = fileBytes.subarray(4 + bootstrapLength);
  require(
    frame.length === bootstrap.encryptedFrameLength,
    "Publication frame length",
  );
  require(
    sha256(frame) === bootstrap.encryptedFrameSha256,
    "Publication frame digest",
  );
  require(
    4 + bootstrapLength + bootstrap.encryptedFrameLength === fileBytes.length,
    "Publication file length",
  );
  const manifest = JSON.parse(fixture.manifestJson);
  require(
    JSON.stringify(manifest) === fixture.manifestJson,
    "Publication manifest is not canonical",
  );
  const plaintext = decryptFrame(
    bootstrap.lineageId,
    manifest.publicationId,
    frame,
  );
  require(
    plaintext.toString("utf8") === fixture.manifestJson,
    "Publication plaintext mismatch",
  );
  require(
    manifest.bootstrapSha256 === bootstrapSha256(manifest),
    "Publication manifest does not digest its bootstrap",
  );
  require(
    bootstrap.lineageId === manifest.lineageId &&
      bootstrap.writerEpoch === manifest.writerEpoch &&
      bootstrap.plannedClaimProviderFileId ===
        manifest.plannedClaimProviderFileId &&
      bootstrap.recoveryCredentialGeneration ===
        manifest.recoveryCredentialGeneration,
    "Publication bootstrap disagrees with its manifest",
  );
  return { bootstrap, manifest, fileSha256: fixture.fileSha256 };
}

const verifiedRoot = verifyOwnership(rootClaim);
const verifiedSuccessor = verifyOwnership(successorClaim);
const verifiedTerminated = verifyOwnership(terminatedClaim);
const verifiedBaseline = verifyPublication(baselinePublication);
const verifiedCurrent = verifyPublication(successorPublication);
verifyPublication(epochTwoBaseline);

require(
  verifiedRoot.header.role === "OWNERSHIP_ROOT" &&
    verifiedRoot.claim.writerEpoch === 1 &&
    verifiedRoot.claim.predecessorProviderFileId === null,
  "The root claim is not an epoch-one root",
);
require(
  verifiedSuccessor.claim.writerEpoch === verifiedRoot.claim.writerEpoch + 1 &&
    verifiedSuccessor.claim.providerFileId ===
      verifiedRoot.claim.nextSuccessorProviderFileId &&
    verifiedSuccessor.claim.predecessorReservedSuccessorProviderFileId ===
      verifiedRoot.claim.nextSuccessorProviderFileId &&
    verifiedSuccessor.claim.predecessorClaimSha256 === verifiedRoot.fileSha256,
  "The successor claim does not occupy the exact reserved root slot",
);
require(
  verifiedTerminated.claim.state === "TERMINATED" &&
    verifiedTerminated.header.role === "OWNERSHIP_TOMBSTONE" &&
    verifiedTerminated.claim.writerEpoch ===
      verifiedSuccessor.claim.writerEpoch + 1 &&
    verifiedTerminated.claim.providerFileId ===
      verifiedSuccessor.claim.nextSuccessorProviderFileId &&
    verifiedTerminated.claim.predecessorClaimSha256 ===
      verifiedSuccessor.fileSha256 &&
    verifiedTerminated.claim.nextSuccessorProviderFileId === null &&
    verifiedTerminated.claim.activeDeviceId === null &&
    verifiedTerminated.claim.baselinePublicationSha256 === null &&
    verifiedTerminated.claim.recoveryCredentialGeneration === null &&
    verifiedTerminated.claim.tombstoneId !== null,
  "The terminal tombstone carries active or recoverable state",
);
require(
  verifiedBaseline.manifest.baseline === true &&
    verifiedBaseline.manifest.publicationSequence === 0 &&
    verifiedBaseline.manifest.plannedClaimProviderFileId ===
      verifiedRoot.claim.providerFileId &&
    verifiedBaseline.manifest.plannedClaimId === verifiedRoot.claim.claimId &&
    verifiedBaseline.manifest.ownershipClaimSha256 === null &&
    verifiedRoot.claim.baselinePublicationSha256 === verifiedBaseline.fileSha256,
  "The epoch-one baseline is not bound to its ownership root",
);
require(
  verifiedCurrent.manifest.publicationSequence ===
    verifiedBaseline.manifest.publicationSequence + 1 &&
    verifiedCurrent.manifest.predecessorPublicationId ===
      verifiedBaseline.manifest.publicationId &&
    verifiedCurrent.manifest.predecessorPublicationSha256 ===
      verifiedBaseline.fileSha256 &&
    verifiedCurrent.manifest.localGeneration >=
      verifiedBaseline.manifest.localGeneration &&
    verifiedCurrent.manifest.ownershipClaimSha256 === verifiedRoot.fileSha256 &&
    verifiedCurrent.manifest.plannedClaimProviderFileId === null,
  "The retained publication pair is not an exact successor pair",
);
function writeFixture(name, fixture) {
  writeFileSync(
    join(outputDirectory, `${name}.json`),
    `${JSON.stringify(fixture, null, 2)}\n`,
  );
}

mkdirSync(outputDirectory, { recursive: true });
writeFixture("ownership-root", rootClaim);
writeFixture("ownership-successor", successorClaim);
writeFixture("ownership-terminated", terminatedClaim);
writeFixture("publication-baseline", baselinePublication);
writeFixture("publication-successor", successorPublication);
