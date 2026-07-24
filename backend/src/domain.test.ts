import assert from "node:assert/strict";
import test from "node:test";
import {
  ApiError,
  hashToken,
  mojangUuid,
  normalizePlayerSearch,
  normalizeUsername,
  orderedPair,
  parseBearer,
  parseMessageId,
  parsePresenceStatus,
  parseReportKind,
  parseReportStatus,
  validatePassword,
  validateGroupName,
  validateMessage,
} from "./domain.js";

test("normalizes valid Minecraft usernames", () => {
  assert.equal(normalizeUsername("Grey_Ifer"), "grey_ifer");
});

test("rejects invalid usernames", () => {
  assert.throws(() => normalizeUsername("space name"), ApiError);
  assert.throws(() => normalizeUsername("abcdefghijklmnopq"), ApiError);
});

test("normalizes player autocomplete queries", () => {
  assert.equal(normalizePlayerSearch("  Grey_  "), "grey_");
  assert.throws(() => normalizePlayerSearch("g"), ApiError);
  assert.throws(() => normalizePlayerSearch("grey ifer"), ApiError);
});

test("orders UUID strings consistently and rejects self targeting", () => {
  const high = "ffffffff-ffff-4fff-8fff-ffffffffffff";
  const low = "00000000-0000-4000-8000-000000000000";
  assert.deepEqual(orderedPair(high, low), [low, high]);
  assert.throws(() => orderedPair(low, low), ApiError);
});

test("validates and trims chat messages", () => {
  assert.equal(validateMessage("  hello  "), "hello");
  assert.throws(() => validateMessage("   "), ApiError);
  assert.throws(() => validateMessage("x".repeat(501)), ApiError);
});

test("validates group names and presence states", () => {
  assert.equal(validateGroupName("  Builders  "), "Builders");
  assert.throws(() => validateGroupName("   "), ApiError);
  assert.throws(() => validateGroupName("x".repeat(49)), ApiError);
  assert.equal(parsePresenceStatus("DND"), "DND");
  assert.throws(() => parsePresenceStatus("BUSY"), ApiError);
});

test("hashes tokens without retaining the original", () => {
  assert.equal(hashToken("secret"), "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b");
  assert.equal(parseBearer(`Bearer ${"a".repeat(43)}`), "a".repeat(43));
  assert.throws(() => parseBearer("Bearer short"), ApiError);
});

test("formats Mojang UUIDs", () => {
  assert.equal(
    mojangUuid("069a79f444e94726a5befca90e38aaf5"),
    "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  );
});

test("validates moderation input without accepting loose identifiers", () => {
  assert.equal(parseMessageId("42"), "42");
  assert.throws(() => parseMessageId("0"), ApiError);
  assert.throws(() => parseMessageId("1 OR 1=1"), ApiError);
  assert.equal(parseReportKind("DIRECT"), "DIRECT");
  assert.equal(parseReportStatus("RESOLVED"), "RESOLVED");
  assert.throws(() => parseReportKind("DM"), ApiError);
  assert.throws(() => parseReportStatus("DELETED"), ApiError);
  assert.equal(validatePassword("correct horse battery staple"), "correct horse battery staple");
  assert.throws(() => validatePassword("too-short"), ApiError);
});
