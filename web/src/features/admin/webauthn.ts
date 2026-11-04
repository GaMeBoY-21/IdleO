import type { components } from "@/api/schema.gen";

/**
 * The browser's half of a passkey (ADR 0051): the server's options to `navigator.credentials`, and its answer back, with
 * every binary value as base64url text.
 */
type Schemas = components["schemas"];

function bytes(text: string): ArrayBuffer {
  const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out.buffer;
}

function text(buffer: ArrayBuffer): string {
  let binary = "";
  new Uint8Array(buffer).forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function descriptors(list: Schemas["CredentialDescriptor"][]): PublicKeyCredentialDescriptor[] {
  return list.map((credential) => ({
    type: "public-key",
    id: bytes(credential.id),
    transports: credential.transports as AuthenticatorTransport[],
  }));
}

/** Whether this browser can use a passkey at all. */
export function passkeysAvailable(): boolean {
  return typeof window !== "undefined" && "PublicKeyCredential" in window;
}

/** Makes a passkey on this device, and what the server needs to keep it. */
export async function makePasskey(
  options: Schemas["PasskeyRegistrationOptions"],
  name: string,
): Promise<Schemas["PasskeyRegistration"]> {
  const credential = (await navigator.credentials.create({
    publicKey: {
      challenge: bytes(options.challenge),
      rp: { id: options.rpId, name: options.rpName },
      user: { id: bytes(options.userId), name: options.userName, displayName: options.userDisplayName },
      pubKeyCredParams: options.algorithms.map((alg) => ({ type: "public-key", alg })),
      authenticatorSelection: { residentKey: "required", userVerification: "required" },
      attestation: "none",
      excludeCredentials: descriptors(options.exclude),
      timeout: options.timeoutMs,
    },
  })) as PublicKeyCredential;
  const response = credential.response as AuthenticatorAttestationResponse;
  return {
    challengeId: options.challengeId,
    name,
    credentialId: text(credential.rawId),
    clientDataJson: text(response.clientDataJSON),
    attestationObject: text(response.attestationObject),
    transports: response.getTransports(),
  };
}

/** Signs the server's challenge with a passkey on this device. */
export async function signWithPasskey(
  options: Schemas["PasskeyAssertionOptions"],
): Promise<Schemas["PasskeyAssertion"]> {
  const credential = (await navigator.credentials.get({
    publicKey: {
      challenge: bytes(options.challenge),
      rpId: options.rpId,
      allowCredentials: descriptors(options.allow),
      userVerification: "required",
      timeout: options.timeoutMs,
    },
  })) as PublicKeyCredential;
  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    challengeId: options.challengeId,
    credentialId: text(credential.rawId),
    clientDataJson: text(response.clientDataJSON),
    authenticatorData: text(response.authenticatorData),
    signature: text(response.signature),
    ...(response.userHandle ? { userHandle: text(response.userHandle) } : {}),
  };
}
