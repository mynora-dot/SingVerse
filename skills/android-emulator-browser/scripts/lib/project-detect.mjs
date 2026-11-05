import { access, readFile } from "node:fs/promises";
import path from "node:path";

async function exists(filePath) {
  try {
    await access(filePath);
    return true;
  } catch {
    return false;
  }
}

export async function detectProject(projectRoot) {
  const root = path.resolve(projectRoot);
  const profile = {
    buildSystem: "flutter",
    frameworks: [],
    launchCommands: {},
    packageCandidates: [],
    projectRoot: root,
  };

  if (await exists(path.join(root, "pubspec.yaml"))) {
    profile.frameworks.push("flutter");
    profile.launchCommands.debug = "flutter build apk --debug";
  }

  const androidConfigText = await Promise.all(
    [
      "android/app/build.gradle",
      "android/app/build.gradle.kts",
      "android/app/src/main/AndroidManifest.xml",
    ].map((file) => readFile(path.join(root, file), "utf8").catch(() => "")),
  );
  for (const text of androidConfigText) {
    for (const match of text.matchAll(/(?:applicationId|namespace|package)\s*[=:]\s*["']([^"']+)["']/g)) {
      profile.packageCandidates.push(match[1]);
    }
  }

  profile.frameworks = [...new Set(profile.frameworks)];
  profile.packageCandidates = [...new Set(profile.packageCandidates)];
  return profile;
}
