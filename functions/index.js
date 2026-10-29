const crypto = require("crypto");
const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

const CODE_LIFETIME_MS = 10 * 60 * 1000; // 10 minutes
const CODE_GEN_COOLDOWN_MS = 30 * 1000; // 30 seconds cooldown between code generation per phone
const MAX_FAILED_ATTEMPTS = 5;
const LOCKOUT_DURATION_MS = 15 * 60 * 1000; // 15 minutes lockout after 5 consecutive failed guesses
const WAKE_COOLDOWN_MS = 5000; // 5 seconds cooldown between wake pings

// 1. Phone registers as receiver and gets a cryptographically secure 6-digit pairing code
exports.registerReceiver = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "Authentication required."
    );
  }

  const phoneUid = context.auth.uid;
  const fcmToken = data?.fcmToken;

  if (!fcmToken || typeof fcmToken !== "string" || fcmToken.trim().length === 0) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "Valid FCM token is required."
    );
  }

  const now = Date.now();
  const receiverRef = db.collection("receivers").doc(phoneUid);

  // Use a Firestore transaction to atomically check code generation rate-limit,
  // invalidate any previous active pairing code, and generate a new code.
  const pairingCode = await db.runTransaction(async (transaction) => {
    const receiverDoc = await transaction.get(receiverRef);

    if (receiverDoc.exists) {
      const receiverData = receiverDoc.data();
      const lastGenAt = receiverData.lastCodeGeneratedAt || 0;

      // Rate limit code generation per phone
      if (now - lastGenAt < CODE_GEN_COOLDOWN_MS) {
        const waitSec = Math.ceil((CODE_GEN_COOLDOWN_MS - (now - lastGenAt)) / 1000);
        throw new functions.https.HttpsError(
          "resource-exhausted",
          `Please wait ${waitSec} second(s) before generating a new pairing code.`
        );
      }

      // Invalidate/delete any previous active code
      if (receiverData.activePairingCode) {
        const oldCodeRef = db.collection("pairingCodes").doc(receiverData.activePairingCode);
        transaction.delete(oldCodeRef);
      }
    }

    // Generate cryptographically secure random 6-digit code in range [100000, 999999]
    const code = crypto.randomInt(100000, 1000000).toString();
    const expiresAt = now + CODE_LIFETIME_MS;

    const newCodeRef = db.collection("pairingCodes").doc(code);
    transaction.set(newCodeRef, {
      phoneUid: phoneUid,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      expiresAt: expiresAt,
    });

    transaction.set(
      receiverRef,
      {
        fcmToken: fcmToken.trim(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        lastCodeGeneratedAt: now,
        activePairingCode: code,
      },
      { merge: true }
    );

    return code;
  });

  return { pairingCode: pairingCode, expiresInMinutes: 10 };
});

// 2. Tablet pairs with phone using the 6-digit pairing code atomically
exports.pairTablet = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "Authentication required."
    );
  }

  const tabletUid = context.auth.uid;
  const rawCode = data?.code;

  // Strict 6-digit numeric validation
  if (typeof rawCode !== "string" || !/^\d{6}$/.test(rawCode.trim())) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "A 6-digit numeric pairing code is required."
    );
  }

  const code = rawCode.trim();
  const now = Date.now();
  const attemptRef = db.collection("pairingAttempts").doc(tabletUid);
  const codeDocRef = db.collection("pairingCodes").doc(code);
  const pairedDeviceRef = db.collection("pairedDevices").doc(tabletUid);

  await db.runTransaction(async (transaction) => {
    // 1. Check brute force protection for caller
    const attemptDoc = await transaction.get(attemptRef);
    let failedAttempts = 0;

    if (attemptDoc.exists) {
      const attemptData = attemptDoc.data();
      const lockedUntil = attemptData.lockedUntil || 0;

      if (lockedUntil > now) {
        const remainingMinutes = Math.ceil((lockedUntil - now) / 60000);
        throw new functions.https.HttpsError(
          "resource-exhausted",
          `Too many failed attempts. Try again in ${remainingMinutes} minute(s).`
        );
      }

      if (now - (attemptData.lastAttemptAt || 0) > LOCKOUT_DURATION_MS) {
        failedAttempts = 0;
      } else {
        failedAttempts = attemptData.failedAttempts || 0;
      }
    }

    // 2. Fetch pairing code document
    const codeDoc = await transaction.get(codeDocRef);

    if (!codeDoc.exists) {
      failedAttempts += 1;
      const updateData = {
        failedAttempts: failedAttempts,
        lastAttemptAt: now,
      };
      if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
        updateData.lockedUntil = now + LOCKOUT_DURATION_MS;
      }
      transaction.set(attemptRef, updateData, { merge: true });

      throw new functions.https.HttpsError(
        "not-found",
        "Invalid or expired pairing code."
      );
    }

    const codeData = codeDoc.data();

    // 3. Verify server-side expiration
    if (now > codeData.expiresAt) {
      transaction.delete(codeDocRef);

      failedAttempts += 1;
      const updateData = {
        failedAttempts: failedAttempts,
        lastAttemptAt: now,
      };
      if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
        updateData.lockedUntil = now + LOCKOUT_DURATION_MS;
      }
      transaction.set(attemptRef, updateData, { merge: true });

      throw new functions.https.HttpsError(
        "deadline-exceeded",
        "Pairing code has expired. Please generate a new one on your phone."
      );
    }

    const targetPhoneUid = codeData.phoneUid;

    // 4. Prevent pairing with self
    if (targetPhoneUid === tabletUid) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "Cannot pair a device with itself."
      );
    }

    // 5. Link tablet to target phone
    transaction.set(pairedDeviceRef, {
      targetPhoneUid: targetPhoneUid,
      pairedAt: admin.firestore.FieldValue.serverTimestamp(),
      lastWakeAt: 0,
    });

    // 6. Delete consumed code atomically (single-use)
    transaction.delete(codeDocRef);

    // 7. Clear receiver's activePairingCode reference
    const receiverRef = db.collection("receivers").doc(targetPhoneUid);
    transaction.update(receiverRef, {
      activePairingCode: admin.firestore.FieldValue.delete(),
    });

    // 8. Reset failed attempts for caller
    transaction.delete(attemptRef);
  });

  return { success: true };
});

// 3. Tablet triggers wake-up notification to paired phone with atomic cooldown
exports.wakePhone = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "Authentication required."
    );
  }

  const tabletUid = context.auth.uid;
  const now = Date.now();
  const pairRef = db.collection("pairedDevices").doc(tabletUid);

  let targetFcmToken;

  // Enforce 5-second cooldown and retrieve receiver token atomically
  await db.runTransaction(async (transaction) => {
    const pairDoc = await transaction.get(pairRef);

    if (!pairDoc.exists) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "This tablet is not paired with any phone."
      );
    }

    const pairData = pairDoc.data();
    const lastWakeAt = pairData.lastWakeAt || 0;

    if (now - lastWakeAt < WAKE_COOLDOWN_MS) {
      const waitSec = Math.ceil((WAKE_COOLDOWN_MS - (now - lastWakeAt)) / 1000);
      throw new functions.https.HttpsError(
        "resource-exhausted",
        `Rate limit reached. Please wait ${waitSec} second(s) before sending again.`
      );
    }

    const targetPhoneUid = pairData.targetPhoneUid;
    const receiverRef = db.collection("receivers").doc(targetPhoneUid);
    const receiverDoc = await transaction.get(receiverRef);

    if (!receiverDoc.exists || !receiverDoc.data()?.fcmToken) {
      throw new functions.https.HttpsError(
        "not-found",
        "Paired phone is not reachable or FCM token is missing."
      );
    }

    targetFcmToken = receiverDoc.data().fcmToken;

    // Atomically record wake timestamp
    transaction.update(pairRef, { lastWakeAt: now });
  });

  // Send high-priority notification through Firebase Admin SDK
  const message = {
    token: targetFcmToken,
    notification: {
      title: "WakePing",
      body: "Your phone is being woken up.",
    },
    android: {
      priority: "high",
      notification: {
        channelId: "wake_ping_channel",
        priority: "high",
        defaultSound: true,
      },
    },
    data: {
      type: "wake_ping",
      timestamp: String(now),
    },
  };

  try {
    const response = await admin.messaging().send(message);
    return { success: true, messageId: response };
  } catch (error) {
    console.error("Error sending FCM notification:", error);
    throw new functions.https.HttpsError(
      "internal",
      "Failed to deliver notification to the phone."
    );
  }
});
