import {
  createCipheriv,
  createHash,
} from "node:crypto";
import {
  mkdirSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";

const key = Buffer.from(
  "000102030405060708090a0b0c0d0e0f" +
    "101112131415161718191a1b1c1d1e1f",
  "hex",
);
const tinkPrefix = Buffer.from("010000002a", "hex");

function lengthPrefix(value) {
  const bytes = Buffer.from(value, "utf8");
  const length = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length);
  return Buffer.concat([length, bytes]);
}

function associatedData(testCase) {
  return Buffer.concat([
    "open-tasks:cloud-header-identity:v1",
    testCase.family,
    "1",
    "1",
    "1",
    "vault-alpha",
    testCase.objectId,
    testCase.chunkIndex === null ? "" : String(testCase.chunkIndex),
    testCase.chunkCount === null ? "" : String(testCase.chunkCount),
  ].map(lengthPrefix));
}

function ciphertext(testCase, aad) {
  const cipher = createCipheriv("aes-256-gcm", key, testCase.nonce);
  cipher.setAAD(aad);
  const encrypted = Buffer.concat([
    cipher.update(testCase.plaintext),
    cipher.final(),
  ]);
  return Buffer.concat([
    tinkPrefix,
    testCase.nonce,
    encrypted,
    cipher.getAuthTag(),
  ]);
}

const cases = [
  {
    name: "manifest",
    family: "MANIFEST",
    objectId: "manifest-0001",
    chunkIndex: null,
    chunkCount: null,
    nonce: Buffer.from("101112131415161718191a1b", "hex"),
    plaintext: Buffer.from("manifest-v1", "utf8"),
  },
  {
    name: "snapshot",
    family: "SNAPSHOT",
    objectId: "snapshot-0001",
    chunkIndex: null,
    chunkCount: null,
    nonce: Buffer.from("202122232425262728292a2b", "hex"),
    plaintext: Buffer.from("snapshot-v1", "utf8"),
  },
  {
    name: "operation-segment",
    family: "OPERATION_SEGMENT",
    objectId: "segment-0001",
    chunkIndex: null,
    chunkCount: null,
    nonce: Buffer.from("303132333435363738393a3b", "hex"),
    plaintext: Buffer.from("operation-segment-v1", "utf8"),
  },
  {
    name: "attachment-chunk",
    family: "ATTACHMENT_CHUNK",
    objectId: "attachment-0001-chunk-00",
    chunkIndex: 0,
    chunkCount: 26,
    nonce: Buffer.from("404142434445464748494a4b", "hex"),
    plaintext: Buffer.from("attachment-chunk-v1", "utf8"),
  },
];

const outputDirectory = join(
  process.cwd(),
  "core/data/src/test/resources/cloud-format/v1-authenticated",
);
mkdirSync(outputDirectory, { recursive: true });

for (const testCase of cases) {
  const aad = associatedData(testCase);
  const encrypted = ciphertext(testCase, aad);
  const checksum = createHash("sha256")
    .update(encrypted)
    .digest("hex");
  const header = {
    magic: "OPEN_TASKS",
    family: testCase.family,
    schemaVersion: 1,
    cryptoVersion: 1,
    minimumReaderVersion: 1,
    vaultId: "vault-alpha",
    objectId: testCase.objectId,
    ciphertextLength: encrypted.length,
    ciphertextSha256: checksum,
    chunkIndex: testCase.chunkIndex,
    chunkCount: testCase.chunkCount,
  };
  const headerJson = JSON.stringify(header);
  const headerBytes = Buffer.from(headerJson, "utf8");
  const headerLength = Buffer.alloc(4);
  headerLength.writeUInt32BE(headerBytes.length);
  const frame = Buffer.concat([
    headerLength,
    headerBytes,
    encrypted,
  ]);
  const fixture = {
    family: testCase.family,
    schemaVersion: 1,
    cryptoVersion: 1,
    minimumReaderVersion: 1,
    vaultId: "vault-alpha",
    objectId: testCase.objectId,
    chunkIndex: testCase.chunkIndex,
    chunkCount: testCase.chunkCount,
    keyHex: key.toString("hex"),
    nonceHex: testCase.nonce.toString("hex"),
    associatedDataHex: aad.toString("hex"),
    plaintextHex: testCase.plaintext.toString("hex"),
    ciphertextHex: encrypted.toString("hex"),
    headerJson,
    frameHex: frame.toString("hex"),
  };
  writeFileSync(
    join(outputDirectory, `${testCase.name}.json`),
    `${JSON.stringify(fixture, null, 2)}\n`,
  );
}
