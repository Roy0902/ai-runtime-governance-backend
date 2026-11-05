# Project-local environment for the backend repository.
# Reuses the pinned JDK 25 / Maven / Node installations kept in the
# ai-runtime-governance docs repository. Dot-source from this folder:
#   . .\local-env.ps1

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$sharedEnv = Join-Path $workspaceRoot 'ai-runtime-governance\local-env\env.ps1'
if (-not (Test-Path -LiteralPath $sharedEnv)) {
    throw "Shared environment not found: $sharedEnv"
}
. $sharedEnv

# Load local secrets from the gitignored .env file, without overriding values
# already present in the shell environment.
$envFile = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith('#')) { return }
        $name, $value = $line -split '=', 2
        if (-not $name -or $null -eq $value) { return }
        $name = $name.Trim()
        $value = $value.Trim().Trim('"').Trim("'")
        if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}
