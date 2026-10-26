import type { components } from "../src/api/schema.gen";
import * as data from "./fixtures";

/**
 * A renter's own images and their checks, invented (ADR 0042), as the API serves them: a ready image with the three kinds of
 * warning, one still downloading, one blocked by the blocklist, one whose check didn't finish, and a private one. Sizes
 * are in decimal bytes, as registries count them, and every count follows from the list beside it.
 */
type Schemas = components["schemas"];
export type Image = Schemas["ImageResponse"];
export type ImageList = Schemas["ImageList"];
export type Vulnerabilities = Schemas["VulnerabilityList"];

const MINUTE = 60_000;
const at = (ms: number) => new Date(data.NOW.getTime() - ms).toISOString();
const GB = 1e9;

/** What the check of a trained-model image found: it needs CUDA 12.4, runs as root and has an AWS key in a file. */
export const readyImage: Image = {
  id: "01a1765a-0000-7000-8000-0000000000a1",
  reference: "ghcr.io/asha/train:v3",
  privateRegistry: true,
  state: "ready",
  createdAt: at(50 * MINUTE),
  checkedAt: at(44 * MINUTE),
  result: {
    digest: "sha256:1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809",
    pinnedReference:
      "ghcr.io/asha/train@sha256:1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809",
    compressedBytes: 8.4 * GB,
    unpackedBytes: 19.2 * GB,
    layers: 14,
    cudaNeeded: "12.4",
    entrypoint: ["/opt/entry.sh"],
    cmd: ["python", "train.py"],
    workingDir: "/app",
    user: "root",
    hasShell: true,
    vulnerabilities: { critical: 2, high: 7, medium: 31, low: 5 },
    warnings: [
      {
        code: "secret",
        message: "Your image seems to contain an AWS key in /app/.env. Anyone with this image can read it.",
      },
      {
        code: "vulnerabilities",
        message: "Trivy found 2 critical and 7 high known vulnerabilities in your image's packages.",
      },
      {
        code: "root_user",
        message:
          "Your image runs as root. We always run your job as user 10001, so code that expects root (writing outside /work and /tmp, installing packages) may fail.",
      },
    ],
    checkedAt: at(44 * MINUTE),
  },
};

/** Downloading: 3.1 of 8.4 GB. */
export const downloadingImage: Image = {
  id: "01a1765a-0000-7000-8000-0000000000a2",
  reference: "nvcr.io/nvidia/pytorch:24.10-py3",
  privateRegistry: false,
  state: "checking",
  createdAt: at(2 * MINUTE),
  progress: { phase: "downloading", doneBytes: 3.1 * GB, totalBytes: 8.4 * GB },
};

/** Blocked: a program on the blocklist was in a layer. The renter's own view never names the file. */
export const blockedImage: Image = {
  id: "01a1765a-0000-7000-8000-0000000000a3",
  reference: "docker.io/someone/tools:latest",
  privateRegistry: false,
  state: "blocked",
  reason: "blocklisted",
  detail:
    "Your image holds software that isn't allowed on IdleO (the acceptable use policy), so it can't run here.",
  createdAt: at(3 * 60 * MINUTE),
  checkedAt: at(3 * 60 * MINUTE - 4 * MINUTE),
};

/** Not checked: the scanner stopped, nothing was decided, and it can't be booked. */
export const notCheckedImage: Image = {
  id: "01a1765a-0000-7000-8000-0000000000a4",
  reference: "quay.io/asha/big:v1",
  privateRegistry: false,
  state: "failed",
  reason: "not_checked",
  detail:
    "The check didn't finish: the scanner stopped reporting 3 times. Nothing was decided, and the image can't be booked until a check completes: check it again.",
  createdAt: at(26 * 60 * MINUTE),
  checkedAt: at(26 * 60 * MINUTE - 30 * MINUTE),
};

/** Wrong platform: blocked before anything was downloaded. */
export const wrongPlatformImage: Image = {
  id: "01a1765a-0000-7000-8000-0000000000a5",
  reference: "docker.io/asha/arm-only:v1",
  privateRegistry: false,
  state: "blocked",
  reason: "wrong_platform",
  detail:
    "Your image is for linux/arm64/v8 only, and hosts here run linux/amd64 images. Build it for linux/amd64 (docker buildx build --platform linux/amd64) and push it again.",
  createdAt: at(30 * 60 * MINUTE),
  checkedAt: at(30 * 60 * MINUTE - MINUTE),
};

export const imageList = (
  images: Image[] = [readyImage, downloadingImage, blockedImage, notCheckedImage],
): ImageList => ({
  images,
  canUse: true,
  platform: "linux/amd64",
});

/** Without a verified PAN: why, and what to do. */
export const noPan: ImageList = {
  images: [],
  canUse: false,
  whyNot:
    "Your own images are for renters with a verified PAN. Give yours in Payout details (Hosting, Payout details); once it's verified you can check and run your own image.",
  platform: "linux/amd64",
};

/** The list of what Trivy found for the ready image: the counts are the list's own. */
export const vulnerabilities: Vulnerabilities = {
  counts: { critical: 1, high: 1, medium: 0, low: 0 },
  vulnerabilities: [
    {
      severity: "CRITICAL",
      id: "CVE-2024-0001",
      packageName: "openssl",
      installed: "3.0.2",
      fixed: "3.0.13",
      title: "A flaw in certificate parsing.",
    },
    {
      severity: "HIGH",
      id: "CVE-2024-0042",
      packageName: "zlib",
      installed: "1.2.11",
      fixed: "",
      title: "A buffer overflow in inflate.",
    },
  ],
};

/** An admin's list of submissions. */
export type AdminImage = Schemas["AdminImage"];
export const adminImages = (): { items: AdminImage[]; nextCursor: null } => ({
  items: [
    {
      id: readyImage.id,
      userId: "01a11300-0000-7000-8000-00000000000a",
      reference: readyImage.reference,
      privateRegistry: true,
      state: "ready",
      reason: null,
      digest: readyImage.result!.digest,
      critical: 2,
      high: 7,
      secrets: 2,
      compressedBytes: readyImage.result!.compressedBytes,
      createdAt: readyImage.createdAt,
      checkedAt: readyImage.checkedAt ?? null,
    },
    {
      id: blockedImage.id,
      userId: "01a11300-0000-7000-8000-00000000000b",
      reference: blockedImage.reference,
      privateRegistry: false,
      state: "blocked",
      reason: "blocklisted",
      digest: "sha256:9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0",
      critical: 0,
      high: 0,
      secrets: 0,
      compressedBytes: 1.2 * GB,
      createdAt: blockedImage.createdAt,
      checkedAt: blockedImage.checkedAt ?? null,
    },
  ],
  nextCursor: null,
});
