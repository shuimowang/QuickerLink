import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { basename, dirname, join } from "node:path";
import { pathToFileURL } from "node:url";

const OFFICIAL_REPOSITORY = "shuimowang/QuickerLink";
const MAX_RELEASES = 10;
const MAX_TAG_LENGTH = 80;
const MAX_APK_BYTES = 150 * 1024 * 1024;
const MAX_CHECKSUM_BYTES = 4 * 1024;
const MAX_INPUT_BYTES = 1024 * 1024;
const MAX_INT32 = 2_147_483_647;
const TAG_PATTERN =
  /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;

function fail(message) {
  throw new Error(message);
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function validateTag(tag) {
  if (typeof tag !== "string" || tag.length > MAX_TAG_LENGTH) {
    fail("Release tag is invalid");
  }
  const match = TAG_PATTERN.exec(tag);
  if (match === null || match.slice(1, 4).some((part) => Number(part) > MAX_INT32)) {
    fail(`Release tag is not supported: ${tag}`);
  }
}

function normalizeAsset(assets, repository, tag, name, maxBytes) {
  const matches = assets.filter((asset) => isObject(asset) && asset.name === name);
  if (matches.length !== 1) {
    fail(`Release ${tag} must contain exactly one ${name} asset`);
  }

  const asset = matches[0];
  const expectedUrl = `https://github.com/${repository}/releases/download/${tag}/${name}`;
  if (
    asset.state !== "uploaded" ||
    asset.browser_download_url !== expectedUrl ||
    !Number.isSafeInteger(asset.size) ||
    asset.size < 1 ||
    asset.size > maxBytes
  ) {
    fail(`Release ${tag} contains invalid metadata for ${name}`);
  }

  return {
    state: "uploaded",
    name,
    browser_download_url: expectedUrl,
    size: asset.size,
  };
}

function normalizeRelease(release, repository) {
  if (!isObject(release) || release.draft !== false) {
    fail("Published release metadata is invalid");
  }
  if (typeof release.prerelease !== "boolean") {
    fail(`Release ${String(release.tag_name)} has an invalid prerelease flag`);
  }

  const tag = release.tag_name;
  validateTag(tag);
  const expectedPageUrl = `https://github.com/${repository}/releases/tag/${tag}`;
  if (release.html_url !== expectedPageUrl || !Array.isArray(release.assets)) {
    fail(`Release ${tag} has invalid page or asset metadata`);
  }

  const apkName = `quicker-link-${tag}-release.apk`;
  const checksumName = `${apkName}.sha256`;
  return {
    draft: false,
    prerelease: release.prerelease,
    tag_name: tag,
    html_url: expectedPageUrl,
    assets: [
      normalizeAsset(release.assets, repository, tag, apkName, MAX_APK_BYTES),
      normalizeAsset(release.assets, repository, tag, checksumName, MAX_CHECKSUM_BYTES),
    ],
  };
}

export function buildUpdateIndex(rawReleases, options = {}) {
  const repository = options.repository ?? OFFICIAL_REPOSITORY;
  const expectedTag = options.expectedTag ?? "";
  if (repository !== OFFICIAL_REPOSITORY) {
    fail(`Refusing to generate an index for ${repository}`);
  }
  if (!Array.isArray(rawReleases) || rawReleases.length < 1 || rawReleases.length > MAX_RELEASES) {
    fail("GitHub release response must contain between 1 and 10 releases");
  }
  if (typeof expectedTag !== "string") {
    fail("Expected release tag is invalid");
  }
  if (expectedTag !== "") {
    validateTag(expectedTag);
  }

  const releases = [];
  const seenTags = new Set();
  for (const release of rawReleases) {
    if (!isObject(release) || typeof release.draft !== "boolean") {
      fail("GitHub release response contains invalid metadata");
    }
    if (release.draft) {
      continue;
    }

    const normalized = normalizeRelease(release, repository);
    if (seenTags.has(normalized.tag_name)) {
      fail(`GitHub release response contains duplicate tag ${normalized.tag_name}`);
    }
    seenTags.add(normalized.tag_name);
    releases.push(normalized);
  }

  if (releases.length < 1) {
    fail("GitHub release response contains no publishable releases");
  }
  if (expectedTag !== "" && !seenTags.has(expectedTag)) {
    fail(`Expected release ${expectedTag} is not present in the update index`);
  }

  return {
    schema_version: 1,
    repository,
    releases,
  };
}

function parseArguments(arguments_) {
  const values = {
    input: "",
    output: "",
    repository: OFFICIAL_REPOSITORY,
    expectedTag: "",
  };
  const names = new Map([
    ["--input", "input"],
    ["--output", "output"],
    ["--repository", "repository"],
    ["--expected-tag", "expectedTag"],
  ]);
  const seenArguments = new Set();

  for (let index = 0; index < arguments_.length; index += 2) {
    const argument = arguments_[index];
    const name = names.get(argument);
    const value = arguments_[index + 1];
    if (name === undefined || value === undefined || value === "" || seenArguments.has(argument)) {
      fail(`Invalid command-line argument: ${String(argument)}`);
    }
    seenArguments.add(argument);
    values[name] = value;
  }

  if (values.input === "" || values.output === "") {
    fail("Both --input and --output are required");
  }
  return values;
}

async function run() {
  const options = parseArguments(process.argv.slice(2));
  const input = await readFile(options.input);
  if (input.byteLength > MAX_INPUT_BYTES) {
    fail("GitHub release response is too large");
  }

  let rawReleases;
  try {
    rawReleases = JSON.parse(input.toString("utf8"));
  } catch (error) {
    throw new Error("GitHub release response is not valid JSON", { cause: error });
  }
  const index = buildUpdateIndex(rawReleases, options);
  const outputDirectory = dirname(options.output);
  const temporaryOutput = join(
    outputDirectory,
    `.${basename(options.output)}.${process.pid}.tmp`,
  );
  await mkdir(outputDirectory, { recursive: true });
  try {
    await writeFile(temporaryOutput, `${JSON.stringify(index, null, 2)}\n`, { flag: "wx" });
    await rename(temporaryOutput, options.output);
  } finally {
    await rm(temporaryOutput, { force: true });
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  run().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
