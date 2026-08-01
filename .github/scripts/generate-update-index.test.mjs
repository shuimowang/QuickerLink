import assert from "node:assert/strict";
import test from "node:test";

import { buildUpdateIndex } from "./generate-update-index.mjs";

const repository = "shuimowang/QuickerLink";

function release(tag = "v0.5.0-alpha.3") {
  const apkName = `quicker-link-${tag}-release.apk`;
  return {
    id: 123,
    draft: false,
    prerelease: true,
    tag_name: tag,
    html_url: `https://github.com/${repository}/releases/tag/${tag}`,
    name: `Quicker Link ${tag}`,
    body: "Fields not used by the app are deliberately omitted from the index.",
    assets: [
      {
        state: "uploaded",
        name: apkName,
        browser_download_url: `https://github.com/${repository}/releases/download/${tag}/${apkName}`,
        size: 57_000_000,
        content_type: "application/vnd.android.package-archive",
      },
      {
        state: "uploaded",
        name: `${apkName}.sha256`,
        browser_download_url: `https://github.com/${repository}/releases/download/${tag}/${apkName}.sha256`,
        size: 106,
      },
    ],
  };
}

test("emits only the update fields consumed by the Android client", () => {
  const source = release();
  const result = buildUpdateIndex([source], {
    repository,
    expectedTag: source.tag_name,
  });

  assert.deepEqual(Object.keys(result), ["schema_version", "repository", "releases"]);
  assert.deepEqual(Object.keys(result.releases[0]), [
    "draft",
    "prerelease",
    "tag_name",
    "html_url",
    "assets",
  ]);
  assert.deepEqual(Object.keys(result.releases[0].assets[0]), [
    "state",
    "name",
    "browser_download_url",
    "size",
  ]);
  assert.equal(result.releases[0].tag_name, source.tag_name);
  assert.equal(result.releases[0].assets.length, 2);
});

test("ignores drafts but refuses to publish an empty index", () => {
  const draft = { ...release(), draft: true, assets: [] };
  assert.throws(() => buildUpdateIndex([draft]), /no publishable releases/);
});

test("rejects a missing expected release instead of deploying a stale index", () => {
  assert.throws(
    () => buildUpdateIndex([release()], { expectedTag: "v0.5.0-alpha.4" }),
    /is not present/,
  );
});

test("rejects duplicate required assets", () => {
  const source = release();
  source.assets.push({ ...source.assets[0] });
  assert.throws(() => buildUpdateIndex([source]), /exactly one/);
});

test("rejects untrusted release and asset URLs", () => {
  const badPage = release();
  badPage.html_url = "https://example.com/release";
  assert.throws(() => buildUpdateIndex([badPage]), /invalid page/);

  const badAsset = release();
  badAsset.assets[0].browser_download_url = "https://example.com/app.apk";
  assert.throws(() => buildUpdateIndex([badAsset]), /invalid metadata/);
});

test("rejects repositories and versions outside the Android trust contract", () => {
  assert.throws(
    () => buildUpdateIndex([release()], { repository: "someone/QuickerLink" }),
    /Refusing/,
  );
  assert.throws(() => buildUpdateIndex([release("v01.0.0")]), /not supported/);
  assert.throws(() => buildUpdateIndex([release("v2147483648.0.0")]), /not supported/);
});
