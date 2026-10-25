#!/usr/bin/env bash
set -euo pipefail

version='0.4.131'
release_url="https://github.com/t8y2/dbx/releases/download/packages-v${version}"
install_parent="${HOME:?HOME must be set}/.local/opt/dbx"
install_target="${install_parent}/packages-v${version}"

case "$(uname -s):$(uname -m)" in
  Linux:aarch64|Linux:arm64) ;;
  *) echo 'This installer is only for 64-bit Linux ARM (aarch64).' >&2; exit 2 ;;
esac

if ! getconf GNU_LIBC_VERSION >/dev/null 2>&1; then
  echo 'DBX Packages Linux ARM64 archives require glibc; musl/Alpine is unsupported.' >&2
  exit 2
fi

if [[ -e "$install_target" ]]; then
  echo "Refusing to overwrite existing install path: $install_target" >&2
  exit 1
fi

mkdir -p "$install_parent"
work_dir="$(mktemp -d "${install_parent}/.packages-v${version}.XXXXXX")"
cleanup() {
  case "${work_dir:-}" in
    "${install_parent}"/.packages-v"${version}".*)
      if [[ -d "$work_dir" ]]; then rm -rf -- "$work_dir"; fi
      ;;
  esac
}
trap cleanup EXIT

cli_archive='dbx-cli-linux-arm64-gnu.tar.gz'
mcp_archive='dbx-mcp-linux-arm64-gnu.tar.gz'

curl --fail --location --silent --show-error --retry 2 \
  "${release_url}/${cli_archive}" -o "${work_dir}/${cli_archive}"
curl --fail --location --silent --show-error --retry 2 \
  "${release_url}/${mcp_archive}" -o "${work_dir}/${mcp_archive}"

printf '%s  %s\n' \
  'e136f406519823f285ff01fc9607bb054c3e3f6b9704ac3f27ae35550432d5d1' "$cli_archive" \
  '6f13e7539d680f36763bfc070da459a25d7c90177a3679a729190576b96d7e6d' "$mcp_archive" \
  | (cd "$work_dir" && sha256sum --check --strict -)

mkdir -p "${work_dir}/cli" "${work_dir}/mcp" "${work_dir}/release"
tar -xzf "${work_dir}/${cli_archive}" -C "${work_dir}/cli"
tar -xzf "${work_dir}/${mcp_archive}" -C "${work_dir}/mcp"
test -f "${work_dir}/cli/dbx"
test -f "${work_dir}/mcp/dbx-mcp"
install -m 0755 "${work_dir}/cli/dbx" "${work_dir}/release/dbx"
install -m 0755 "${work_dir}/mcp/dbx-mcp" "${work_dir}/release/dbx-mcp"
mv -- "${work_dir}/release" "$install_target"

printf 'Installed verified DBX CLI and MCP %s under:\n%s\n' "$version" "$install_target"
printf 'No system paths, services, databases, or credentials were changed.\n'
