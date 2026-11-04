#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
MANIFEST="$PROJECT_DIR/config/javadriveless-artifact.json"
[[ -f "$MANIFEST" && ! -L "$MANIFEST" ]] || { echo 'Public artifact manifest missing or unsafe' >&2; exit 1; }

metadata="$(python3 -I - "$MANIFEST" <<'PY'
import json, re, sys

def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate manifest key')
        result[key] = value
    return result

m = json.load(open(sys.argv[1]), object_pairs_hook=unique)
if set(m) != {'schema', 'repository', 'commit', 'version', 'archive_sha256', 'jar_sha256', 'pom_sha256'} or type(m['schema']) is not int or m['schema'] != 1:
    raise ValueError('invalid manifest schema')
if m['repository'] != 'https://github.com/vitorcamillo/javaDriveless':
    raise ValueError('invalid public repository')
if not re.fullmatch(r'[0-9a-f]{40}', m['commit']) or m['version'] != '1.12.3':
    raise ValueError('invalid commit or version')
for key in ('archive_sha256', 'jar_sha256', 'pom_sha256'):
    if not re.fullmatch(r'[0-9a-f]{64}', m[key]):
        raise ValueError('invalid artifact checksum')
for key in ('commit', 'archive_sha256', 'version', 'jar_sha256', 'pom_sha256'):
    print(m[key])
PY
)"
mapfile -t fields <<< "$metadata"
[[ "${#fields[@]}" -eq 5 ]] || { echo 'Invalid public artifact metadata' >&2; exit 1; }
repository="${JAVA_DRIVERLESS_MAVEN_REPO:-$HOME/.m2/repository}"
[[ "$repository" == /* ]] || { echo 'Maven repository must be absolute' >&2; exit 1; }

for tool in curl sha256sum tar timeout mvn; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Required tool missing: $tool" >&2; exit 1; }
done

workspace="$(mktemp -d)"
trap 'rm -rf -- "$workspace"' EXIT
curl --fail --location --silent --show-error --connect-timeout 10 --max-time 120 --retry 2 \
  "https://codeload.github.com/vitorcamillo/javaDriveless/tar.gz/${fields[0]}" -o "$workspace/source.tar.gz"
printf '%s  %s\n' "${fields[1]}" "$workspace/source.tar.gz" | sha256sum -c -
mkdir "$workspace/source"
tar -xzf "$workspace/source.tar.gz" --strip-components=1 -C "$workspace/source"
printf '%s  %s\n' "${fields[4]}" "$workspace/source/pom.xml" | sha256sum -c -
timeout 900 mvn -B -ntp -f "$workspace/source/pom.xml" -DskipTests "-Dmaven.repo.local=$repository" install
artifact="$repository/io/github/vitorcamillo/javadriveless/${fields[2]}/javadriveless-${fields[2]}"
printf '%s  %s\n' "${fields[3]}" "$artifact.jar" "${fields[4]}" "$artifact.pom" | sha256sum -c -
