package com.doncc.scrcpygo.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseAssetSelectorTest {
    private val allAssets = listOf(
        ReleaseAsset("scrcpy-go-1.1.0-linux-x64.zip", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-linux-x64.zip"),
        ReleaseAsset("scrcpy-go-1.1.0-windows-x64.zip", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-windows-x64.zip"),
        ReleaseAsset("scrcpy-go-1.1.0-macOS-aarch64.zip", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-macOS-aarch64.zip"),
        ReleaseAsset("scrcpy-go-1.1.0-macOS-x64.zip", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-macOS-x64.zip"),
        ReleaseAsset("checksums.txt", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/checksums.txt")
    )

    @Test
    fun picksLinuxX64() {
        val asset = ReleaseAssetSelector.pick(allAssets, "linux", "x64")
        assertEquals("scrcpy-go-1.1.0-linux-x64.zip", asset?.name)
    }

    @Test
    fun picksWindowsX64() {
        val asset = ReleaseAssetSelector.pick(allAssets, "windows", "x64")
        assertEquals("scrcpy-go-1.1.0-windows-x64.zip", asset?.name)
    }

    @Test
    fun picksMacAarch64() {
        val asset = ReleaseAssetSelector.pick(allAssets, "macos", "aarch64")
        assertEquals("scrcpy-go-1.1.0-macOS-aarch64.zip", asset?.name)
    }

    @Test
    fun picksMacX64() {
        val asset = ReleaseAssetSelector.pick(allAssets, "macos", "x64")
        assertEquals("scrcpy-go-1.1.0-macOS-x64.zip", asset?.name)
    }

    @Test
    fun acceptsAmd64Alias() {
        val assets = listOf(
            ReleaseAsset("scrcpy-go-1.1.0-linux-amd64.zip", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-linux-amd64.zip")
        )
        val asset = ReleaseAssetSelector.pick(assets, "linux", "x64")
        assertEquals("scrcpy-go-1.1.0-linux-amd64.zip", asset?.name)
    }

    @Test
    fun fallsBackToPlatformOnlyAsset() {
        val assets = listOf(
            ReleaseAsset("scrcpy-go-1.1.0-linux.zip", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-linux.zip")
        )
        val asset = ReleaseAssetSelector.pick(assets, "linux", "x64")
        assertEquals("scrcpy-go-1.1.0-linux.zip", asset?.name)
    }

    @Test
    fun returnsNullWhenPlatformMissing() {
        val assets = listOf(
            ReleaseAsset("scrcpy-go-1.1.0-windows-x64.zip", "https://github.com/doncc/scrcpy-go/releases/download/v1.1.0/scrcpy-go-1.1.0-windows-x64.zip")
        )
        assertNull(ReleaseAssetSelector.pick(assets, "linux", "x64"))
    }
}
