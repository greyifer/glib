import assert from "node:assert/strict";
import test from "node:test";
import {
  ApiError,
  hashToken,
  mojangUuid,
  normalizeUsername,
  orderedPair,
  parseBearer,
  validateMessage,
} from "./domain.js";

test("normalizes valid Minecraft usernames", () => {
  assert.equal(normalizeUsername("Grey_Ifer"), "grey_ifer");
});

test("rejects invalid usernames", () => {
  assert.throws(() => normalizeUsername("space name"), ApiError);
  assert.throws(() => normalizeUsername("abcdefghijklmnopq"), ApiError);
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
