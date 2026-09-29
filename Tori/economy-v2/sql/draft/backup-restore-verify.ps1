param([Parameter(Mandatory)][string]$BackupFile)
$ErrorActionPreference = 'Stop'
$source = $env:TORI_BACKUP_SOURCE_DSN
$target = $env:TORI_RESTORE_TEST_DSN
if ([string]::IsNullOrWhiteSpace($source) -or [string]::IsNullOrWhiteSpace($target)) {
    throw 'Set TORI_BACKUP_SOURCE_DSN and TORI_RESTORE_TEST_DSN (without passwords).'
}
if ($source -match '(?i)password\s*=' -or $target -match '(?i)password\s*=' -or
    $source -match '://[^/\s:]+:[^@\s]+@' -or $target -match '://[^/\s:]+:[^@\s]+@') {
    throw 'Do not include passwords in DSNs. Use PGPASSFILE or a password prompt.'
}
foreach ($tool in @('psql', 'pg_dump', 'pg_restore')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Missing PostgreSQL tool: $tool" }
}
$backupPath = [IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($backupPath)))) {
    throw 'Backup parent directory does not exist.'
}
if (Test-Path -LiteralPath $backupPath) { throw 'Backup file already exists; refusing to overwrite.' }
$sourceName = (& psql -X -A -t -v ON_ERROR_STOP=1 --dbname=$source -c 'SELECT current_database()').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Source DB inspection failed.' }
$targetName = (& psql -X -A -t -v ON_ERROR_STOP=1 --dbname=$target -c 'SELECT current_database()').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Restore DB inspection failed.' }
if ($sourceName -eq $targetName -or $targetName -notmatch '^[a-z0-9_]+_restore_test$') {
    throw 'Target must be a distinct database named *_restore_test.'
}
$emptyQuery = "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S')"
$targetObjects = (& psql -X -A -t -v ON_ERROR_STOP=1 --dbname=$target -c $emptyQuery).Trim()
if ($LASTEXITCODE -ne 0 -or $targetObjects -ne '0') { throw 'Restore target must be an empty database.' }
$integrity = Join-Path $PSScriptRoot 'restore_integrity.sql'
$before = (& psql -X -A -t -v ON_ERROR_STOP=1 --dbname=$source -f $integrity).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Source integrity query failed.' }
& pg_dump --format=custom --file=$backupPath --dbname=$source
if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed; do not use this backup.' }
& pg_restore --exit-on-error --no-owner --no-privileges --dbname=$target $backupPath
if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed; restore target requires investigation.' }
$after = (& psql -X -A -t -v ON_ERROR_STOP=1 --dbname=$target -f $integrity).Trim()
if ($LASTEXITCODE -ne 0 -or $before -ne $after) { throw 'Restore integrity mismatch.' }
Write-Output 'Backup restored into a separate test DB; schema, Flyway state and data aggregates match.'
