# Generates the palette resources for the dhltcompat mod:
#   - one shared solid-white 16x16 block texture
#   - 224 models (14 blocks x 16 metas), all using that white texture
#   - 14 blockstates
# The actual color is applied at runtime by DhApiBlockColorOverrideEvent
# (see DhLtCompatMod), so a single white texture is enough and keeps
# texture memory minimal.
# Must stay in sync with Palette.java (BLOCK_COUNT / META_COUNT).

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$assetsDir = Join-Path $root 'src\main\resources\assets\dhltcompat'
$texDir = Join-Path $assetsDir 'textures\blocks'
$bsDir = Join-Path $assetsDir 'blockstates'
$modelDir = Join-Path $assetsDir 'models\block'
New-Item -ItemType Directory -Force $texDir, $bsDir, $modelDir | Out-Null

$blockCount = 14
$metaCount = 16

# shared white texture (must live under textures/blocks so MC 1.12.2
# stitches it into the block atlas automatically)
$bmp = New-Object System.Drawing.Bitmap 16, 16
try {
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.Clear([System.Drawing.Color]::FromArgb(255, 255, 255, 255))
    $gfx.Dispose()
    $bmp.Save((Join-Path $texDir 'palette_white.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $bmp.Dispose()
}

# models
for ($block = 0; $block -lt $blockCount; $block++) {
    for ($meta = 0; $meta -lt $metaCount; $meta++) {
        $json = @"
{
  "parent": "block/cube_all",
  "textures": {
    "all": "dhltcompat:blocks/palette_white"
  }
}
"@
        [System.IO.File]::WriteAllText((Join-Path $modelDir "palette_${block}_$meta.json"), $json)
    }
}

# blockstates
for ($block = 0; $block -lt $blockCount; $block++) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('{')
    [void]$sb.AppendLine('  "variants": {')
    for ($meta = 0; $meta -lt $metaCount; $meta++) {
        $comma = ','
        if ($meta -eq ($metaCount - 1)) { $comma = '' }
        [void]$sb.AppendLine("    `"variant=$meta`": { `"model`": `"dhltcompat:palette_${block}_$meta`" }$comma")
    }
    [void]$sb.AppendLine('  }')
    [void]$sb.AppendLine('}')
    [System.IO.File]::WriteAllText((Join-Path $bsDir "palette_$block.json"), $sb.ToString())
}

Write-Host "Generated 1 texture, $($blockCount * $metaCount) models, $blockCount blockstates under $assetsDir"
