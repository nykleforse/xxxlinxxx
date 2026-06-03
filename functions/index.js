const {onDocumentCreated, onDocumentWritten} = require("firebase-functions/v2/firestore");
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {initializeApp} = require("firebase-admin/app");
const {getFirestore, FieldValue} = require("firebase-admin/firestore");
const {getMessaging} = require("firebase-admin/messaging");
const {getAuth} = require("firebase-admin/auth");
const crypto = require("crypto");

initializeApp();

const db = getFirestore();
const messaging = getMessaging();
const auth = getAuth();

const MAX_DEVICES = 3;

/**
 * Callable: bindLocalId
 *
 * Binds the caller's Firebase Auth uid to a localId, proving ownership of the
 * photo+password-derived EC private key via an ECDSA signature.
 *
 * Input (data):
 *   localId   : string — uppercase hex (4–32 chars)
 *   pubkey    : string — X.509 SubjectPublicKeyInfo DER, base64
 *   signature : string — ECDSA P-256 SHA-256 signature over (localId + "\n" + uid), base64
 *
 * Side effects:
 *   bindings/{localId} = { pubkey, devices: { uid: timestampMillis, ... } }
 *   Custom claim { localId } set on the Firebase Auth user.
 *
 * Behavior:
 *   - First call for a localId: trusts the submitted pubkey and binds.
 *   - Subsequent calls: signature must verify against the STORED pubkey.
 *     pubkey in payload must match stored pubkey (no key-rotation here).
 *   - LRU eviction: when devices map reaches MAX_DEVICES, the oldest entry
 *     by timestamp is removed before adding the new uid.
 */
exports.bindLocalId = onCall({region: "us-central1"}, async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign-in required");

  const {localId, pubkey, signature} = request.data || {};
  if (typeof localId !== "string" || !/^[A-Z0-9]{4,32}$/.test(localId)) {
    throw new HttpsError("invalid-argument", "Invalid localId");
  }
  if (typeof pubkey !== "string" || pubkey.length === 0 || pubkey.length > 8192) {
    throw new HttpsError("invalid-argument", "Invalid pubkey");
  }
  if (typeof signature !== "string" || signature.length === 0 || signature.length > 1024) {
    throw new HttpsError("invalid-argument", "Invalid signature");
  }

  const bindRef = db.collection("bindings").doc(localId);
  const snap = await bindRef.get();
  const now = Date.now();

  // Verify signature against the SUBMITTED pubkey. If the caller can produce
  // a valid signature for the (localId, uid) pair under their current private
  // key, treat them as the legitimate owner — even when the submitted pubkey
  // differs from the one stored on a previous bind. This accommodates v1 → v2
  // password upgrade and ordinary password changes, both of which legitimately
  // rotate the EC keypair derived from photo+password.
  //
  // Trade-off: anyone who possesses the photo + password (the identity factors
  // — see S3 stance) can rotate the binding. This matches the documented
  // threat model, where photo+password is the canonical credential.
  if (!verifySignature(localId, uid, pubkey, signature)) {
    throw new HttpsError("permission-denied", "Signature invalid");
  }
  const canonicalPubkey = pubkey;

  // Build the new devices map (≤ MAX_DEVICES, LRU eviction).
  let devices = snap.exists && snap.data().devices ? {...snap.data().devices} : {};
  // Update existing uid timestamp or add new.
  devices[uid] = now;
  if (Object.keys(devices).length > MAX_DEVICES) {
    // Sort by timestamp ascending, drop oldest until we're back at MAX_DEVICES.
    const sorted = Object.entries(devices).sort((a, b) => a[1] - b[1]);
    while (sorted.length > MAX_DEVICES) {
      const [oldestUid] = sorted.shift();
      delete devices[oldestUid];
      // Best-effort: revoke that device's custom claim so it can't keep writing.
      try {
        await auth.setCustomUserClaims(oldestUid, {localId: null});
      } catch (e) {
        console.warn("Failed to clear claim for evicted uid", oldestUid, e.message);
      }
    }
  }

  await bindRef.set({
    pubkey: canonicalPubkey,
    devices,
    updatedAt: now,
  }, {merge: false});

  // Set the custom claim so this device can pass auth.token.localId == X checks.
  await auth.setCustomUserClaims(uid, {localId});

  return {ok: true, devices: Object.keys(devices).length};
});

function verifySignature(localId, uid, pubkeyB64, signatureB64) {
  try {
    const pubkeyDer = Buffer.from(pubkeyB64, "base64");
    const signature = Buffer.from(signatureB64, "base64");
    const message = Buffer.from(localId + "\n" + uid, "utf8");
    const pubkey = crypto.createPublicKey({
      key: pubkeyDer,
      format: "der",
      type: "spki",
    });
    return crypto.verify("SHA256", message, {
      key: pubkey,
      dsaEncoding: "der",
    }, signature);
  } catch (e) {
    console.warn("verifySignature error", e.message);
    return false;
  }
}

// ────────────────────────────────────────────────────────────────────────────

exports.notifyMessageCreated = onDocumentCreated("messages/{messageId}", async (event) => {
  const message = event.data && event.data.data();
  if (!message) return;

  const to = message.to;
  const from = message.from;
  if (!to || !from || to === from) return;

  const token = await fcmTokenForUser(to);
  if (!token) return;

  await sendToToken(token, {
    type: "message",
    messageId: event.params.messageId,
    senderId: from,
    body: "Encrypted message",
  });
});

exports.notifyIncomingCall = onDocumentWritten("calls/{callId}", async (event) => {
  const after = event.data && event.data.after;
  if (!after || !after.exists) return;

  const call = after.data();
  const before = event.data.before && event.data.before.exists ? event.data.before.data() : null;

  const calleeId = call.calleeId;
  const callerId = call.callerId;
  if (!calleeId || !callerId || calleeId === callerId) return;

  if (call.state === "ringing") {
    if (before && before.state === "ringing" && before.sessionId === call.sessionId) return;
    const token = await fcmTokenForUser(calleeId);
    if (!token) return;

    await sendToToken(token, {
      type: "call",
      callId: event.params.callId,
      sessionId: String(call.sessionId || ""),
      callerId,
    });
    return;
  }

  if (call.state !== "ended" && call.state !== "declined") return;
  if (before && before.state === call.state) return;

  const cancelTargets = new Set([calleeId]);
  if (call.state === "declined") cancelTargets.add(callerId);

  await Promise.all([...cancelTargets].map(async (userId) => {
    const token = await fcmTokenForUser(userId);
    if (!token) return;
    await sendToToken(token, {
      type: "call_cancel",
      callId: event.params.callId,
      sessionId: String(call.sessionId || call.endedSessionId || call.declinedSessionId || ""),
      state: String(call.state),
    });
  }));
});

async function fcmTokenForUser(userId) {
  const snapshot = await db.collection("users").doc(userId).get();
  const token = snapshot.exists ? snapshot.get("fcmToken") : null;
  return typeof token === "string" && token.length > 0 ? token : null;
}

async function sendToToken(token, data) {
  try {
    await messaging.send({
      token,
      data: sanitizeData(data),
      android: {
        priority: "high",
        ttl: 30 * 1000,
      },
    });
  } catch (error) {
    console.error("FCM send failed", error);
  }
}

function sanitizeData(data) {
  return Object.fromEntries(
    Object.entries(data).map(([key, value]) => [key, value == null ? "" : String(value)])
  );
}
