import { createCipheriv, createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const lineageId = "11111111-1111-1111-8111-111111111111";
const key = Buffer.from(
  "000102030405060708090a0b0c0d0e0f" +
    "101112131415161718191a1b1c1d1e1f",
  "hex",
);
const nonce = Buffer.from("101112131415161718191a1b", "hex");
const tinkPrefix = Buffer.from("010000002a", "hex");
const manifest = {
  blobSetId: "blob-set-fixture-a",
  contentSha256: "a".repeat(64),
  totalByteCount: 11,
  chunks: [
    {
      index: 0,
      providerObjectId: "provider-chunk-0",
      ciphertextSha256: "b".repeat(64),
      plaintextByteCount: 5,
    },
    {
      index: 1,
      providerObjectId: "provider-chunk-1",
      ciphertextSha256: "c".repeat(64),
      plaintextByteCount: 6,
    },
  ],
};

function lengthPrefix(value) {
  const bytes = Buffer.from(value, "utf8");
  const length = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length);
  return Buffer.concat([length, bytes]);
}

const objectId = `attachment-manifest:${manifest.blobSetId}`;
const associatedData = Buffer.concat([
  "open-tasks:cloud-header-identity:v1",
  "MANIFEST",
  "1",
  "1",
  "1",
  lineageId,
  objectId,
  "",
  "",
].map(lengthPrefix));
const payloadJson = JSON.stringify(manifest);
const plaintext = Buffer.from(payloadJson, "utf8");
const cipher = createCipheriv("aes-256-gcm", key, nonce);
cipher.setAAD(associatedData);
const ciphertext = Buffer.concat([
  tinkPrefix,
  nonce,
  cipher.update(plaintext),
  cipher.final(),
  cipher.getAuthTag(),
]);
const ciphertextSha256 = createHash("sha256").update(ciphertext).digest("hex");
const header = {
  magic: "OPEN_TASKS",
  family: "MANIFEST",
  schemaVersion: 1,
  cryptoVersion: 1,
  minimumReaderVersion: 1,
  vaultId: lineageId,
  objectId,
  ciphertextLength: ciphertext.length,
  ciphertextSha256,
  chunkIndex: null,
  chunkCount: null,
};
const headerJson = JSON.stringify(header);
const headerBytes = Buffer.from(headerJson, "utf8");
const headerLength = Buffer.alloc(4);
headerLength.writeUInt32BE(headerBytes.length);
const frame = Buffer.concat([headerLength, headerBytes, ciphertext]);
const fixture = {
  lineageId,
  keyHex: key.toString("hex"),
  nonceHex: nonce.toString("hex"),
  associatedDataHex: associatedData.toString("hex"),
  payloadJson,
  plaintextHex: plaintext.toString("hex"),
  ciphertextHex: ciphertext.toString("hex"),
  headerJson,
  frameHex: frame.toString("hex"),
  frameSha256: createHash("sha256").update(frame).digest("hex"),
  manifest,
};
const outputDirectory = join(
  process.cwd(),
  "core/data/src/test/resources/backup-format/attachment-v1",
);
mkdirSync(outputDirectory, { recursive: true });
writeFileSync(
  join(outputDirectory, "blob-set-manifest.json"),
  `${JSON.stringify(fixture, null, 2)}\n`,
);
