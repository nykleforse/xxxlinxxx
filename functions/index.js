const {onDocumentCreated, onDocumentWritten} = require("firebase-functions/v2/firestore");
const {initializeApp} = require("firebase-admin/app");
const {getFirestore} = require("firebase-admin/firestore");
const {getMessaging} = require("firebase-admin/messaging");

initializeApp();

const db = getFirestore();
const messaging = getMessaging();

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
  if (call.state !== "ringing") return;
  if (before && before.state === "ringing" && before.sessionId === call.sessionId) return;

  const calleeId = call.calleeId;
  const callerId = call.callerId;
  if (!calleeId || !callerId || calleeId === callerId) return;

  const token = await fcmTokenForUser(calleeId);
  if (!token) return;

  await sendToToken(token, {
    type: "call",
    callId: event.params.callId,
    sessionId: String(call.sessionId || ""),
    callerId,
  });
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
