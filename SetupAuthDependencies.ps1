$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$libraryDirectory = Join-Path $root 'lib'
New-Item -ItemType Directory -Force -Path $libraryDirectory | Out-Null

$dependencies = @(
    @{
        Name = 'sqlite-jdbc-3.50.3.0.jar'
        Url = 'https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar'
        Sha256 = 'a3f53a2aa15ae9425a9e793bbe9c8e5288febeb4b65ef5c1a4e80d4c2045cf08'
    },
    @{
        Name = 'slf4j-api-2.0.17.jar'
        Url = 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar'
        Sha256 = '7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832'
    },
    @{
        Name = 'slf4j-nop-2.0.17.jar'
        Url = 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-nop/2.0.17/slf4j-nop-2.0.17.jar'
        Sha256 = '3716f83649ec66161a2edefd4f49df34d1dd1c51cdcf941996c6987260f0a829'
    }
)

foreach ($dependency in $dependencies) {
    $target = Join-Path $libraryDirectory $dependency.Name
    if (-not (Test-Path -LiteralPath $target)) {
        Write-Host "Downloading $($dependency.Name)..."
        Invoke-WebRequest -Uri $dependency.Url -OutFile $target -UseBasicParsing
    }
    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $dependency.Sha256) {
        Remove-Item -LiteralPath $target -Force
        throw "Checksum verification failed for $($dependency.Name). The file was removed."
    }
}
