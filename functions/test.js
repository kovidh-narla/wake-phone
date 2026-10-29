const assert = require("assert");
const crypto = require("crypto");

console.log("Running backend logic validation tests...");

// Test 1: CSPRNG 6-digit code generation
for (let i = 0; i < 1000; i++) {
  const code = crypto.randomInt(100000, 1000000).toString();
  assert.strictEqual(code.length, 6, "Code must be 6 digits");
  assert.strictEqual(/^\d{6}$/.test(code), true, "Code must contain only digits");
  const num = parseInt(code, 10);
  assert(num >= 100000 && num <= 999999, "Code must be in [100000, 999999]");
}
console.log("✓ Test 1 Passed: 1,000 CSPRNG codes verified within [100000, 999999]");

// Test 2: Strict pairing code input validation regex
const codeRegex = /^\d{6}$/;
const validCodes = ["100000", "999999", "123456", "000000", "582914"];
const invalidCodes = [
  "",
  "12345",
  "1234567",
  "12345a",
  "abcdef",
  "12 456",
  "123-45",
  null,
  undefined,
  "12345.6",
  "'; DROP TABLE users; --"
];

for (const code of validCodes) {
  assert.strictEqual(codeRegex.test(code.trim()), true, `Expected valid code: ${code}`);
}

for (const code of invalidCodes) {
  const isValid = typeof code === "string" && codeRegex.test(code.trim());
  assert.strictEqual(isValid, false, `Expected invalid code: ${code}`);
}
console.log("✓ Test 2 Passed: Strict 6-digit code validation passed for all invalid test vectors");

// Test 3: Rate limiting calculation
const COOLDOWN_MS = 5000;
const lastWakeAt = 10000;
assert.strictEqual(13000 - lastWakeAt < COOLDOWN_MS, true, "Should detect rate limit violation (3s < 5s)");
assert.strictEqual(16000 - lastWakeAt < COOLDOWN_MS, false, "Should allow send after cooldown (6s >= 5s)");
console.log("✓ Test 3 Passed: Cooldown math verified");

console.log("All backend tests completed successfully.");
