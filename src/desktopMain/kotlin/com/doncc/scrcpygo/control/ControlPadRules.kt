package com.doncc.scrcpygo.control

import kotlin.math.roundToInt

/**
 * 控制条相关的纯规则：
 * 1. 计算主屏右侧的初始位置与多设备槽位；
 * 2. 生成 scrcpy 窗口标题并判断用户是否已自定义标题；
 * 3. 推导控制条与投屏窗口的 Z 轴层级关系。
 *
 * 这里不依赖 Compose 或窗口系统，便于单元测试。
 */
object ControlPadRules {
    /** 控制条与屏幕右缘的间距（dp） */
    const val RIGHT_MARGIN_DP = 12

    /** 相邻控制条之间的间距（dp） */
    const val PAD_GAP_DP = 8

    /**
     * 计算控制条左上角像素坐标。
     *
     * 槽位 0 贴右缘垂直居中，槽位递增时向左排列；
     * 左侧空间不足时夹取到工作区左边缘，避免把窗口移到屏幕外。
     */
    fun calculatePosition(
        workAreaX: Int,
        workAreaY: Int,
        workAreaWidth: Int,
        workAreaHeight: Int,
        padWidthPx: Int,
        padHeightPx: Int,
        marginPx: Int,
        gapPx: Int,
        slotIndex: Int
    ): PixelPosition {
        require(padWidthPx > 0 && padHeightPx > 0) { "控制条尺寸必须大于 0" }
        require(workAreaWidth > 0 && workAreaHeight > 0) { "工作区尺寸必须大于 0" }

        val rightEdgeX = workAreaX + workAreaWidth - marginPx - padWidthPx
        val slotOffset = slotIndex.coerceAtLeast(0) * (padWidthPx + gapPx)
        val x = (rightEdgeX - slotOffset).coerceAtLeast(workAreaX)
        val y = (workAreaY + (workAreaHeight - padHeightPx) / 2).coerceAtLeast(workAreaY)
        return PixelPosition(x, y)
    }

    /**
     * 计算控制条贴住投屏窗口的位置。
     * 控制条顶部与窗口顶部平齐；优先贴窗口右侧，右侧放不下时翻到左侧；
     * 两侧都放不下时退到工作区右缘，保证控制条仍在屏幕内。
     */
    fun calculateDockedPosition(
        windowX: Int,
        windowY: Int,
        windowWidth: Int,
        padWidthPx: Int,
        padHeightPx: Int,
        workAreaX: Int,
        workAreaY: Int,
        workAreaWidth: Int,
        workAreaHeight: Int
    ): PixelPosition {
        require(padWidthPx > 0 && padHeightPx > 0) { "控制条尺寸必须大于 0" }
        require(workAreaWidth > 0 && workAreaHeight > 0) { "工作区尺寸必须大于 0" }

        val windowRight = windowX + windowWidth
        val leftX = windowX - padWidthPx
        val workAreaRight = workAreaX + workAreaWidth
        val x = when {
            windowRight + padWidthPx <= workAreaRight -> windowRight
            leftX >= workAreaX -> leftX
            else -> workAreaRight - padWidthPx
        }.coerceAtLeast(workAreaX)

        val maxY = (workAreaY + workAreaHeight - padHeightPx).coerceAtLeast(workAreaY)
        return PixelPosition(x, windowY.coerceIn(workAreaY, maxY))
    }

    /**
     * 推导控制条是否应该强制置顶。
     *
     * 正常贴靠时跟随投屏窗口的置顶状态：投屏窗口置顶则控制条也置顶，
     * 否则两者都留在普通层级，由 Z 轴规则保持相邻。
     * 平台不支持窗口查找或投屏窗口长期找不到时退回固定右缘，
     * 此时控制条必须始终置顶，避免被其他窗口盖住而无法操作。
     */
    fun shouldPadBeAlwaysOnTop(
        locatorSupported: Boolean,
        fallbackMode: Boolean,
        mirrorAlwaysOnTop: Boolean
    ): Boolean {
        if (!locatorSupported || fallbackMode) return true
        return mirrorAlwaysOnTop
    }

    /**
     * 根据当前 Z 轴层级关系推导控制条的下一次重排动作。
     *
     * 目标：控制条始终紧贴在投屏窗口正上方，并且与投屏窗口处在同一层级带
     * （同为普通窗口或同为置顶窗口）；投屏窗口与投屏窗口之上的那个窗口
     * 决定了控制条究竟应该放在哪个窗口下面。
     *
     * @param padWindowId 控制条自身的原生窗口 id
     * @param padAlwaysOnTop 控制条当前是否处于置顶层级
     * @param mirrorAlwaysOnTop 投屏窗口当前是否处于置顶层级
     * @param windowAboveMirrorId 紧排在投屏窗口上方的第一个窗口 id，没有则为 null
     * @param windowAboveMirrorAlwaysOnTop 上述窗口是否处于置顶层级
     */
    fun planPadZOrder(
        padWindowId: Long,
        padAlwaysOnTop: Boolean,
        mirrorAlwaysOnTop: Boolean,
        windowAboveMirrorId: Long?,
        windowAboveMirrorAlwaysOnTop: Boolean
    ): PadZOrderAction {
        if (padWindowId == 0L) {
            return PadZOrderAction.NoOp
        }
        // 控制条已经紧贴在投屏窗口正上方，且两者处于同一层级带时无需动作
        if (windowAboveMirrorId == padWindowId && padAlwaysOnTop == mirrorAlwaysOnTop) {
            return PadZOrderAction.NoOp
        }

        if (mirrorAlwaysOnTop) {
            if (windowAboveMirrorId == null || windowAboveMirrorId == padWindowId) {
                return PadZOrderAction.PlaceAtTopMost
            }
            return PadZOrderAction.PlaceBelowSibling(
                siblingWindowId = windowAboveMirrorId,
                targetAlwaysOnTop = true,
                changeTopMostBand = !padAlwaysOnTop
            )
        }

        if (windowAboveMirrorId != null &&
            !windowAboveMirrorAlwaysOnTop &&
            windowAboveMirrorId != padWindowId
        ) {
            return PadZOrderAction.PlaceBelowSibling(
                siblingWindowId = windowAboveMirrorId,
                targetAlwaysOnTop = false,
                changeTopMostBand = padAlwaysOnTop
            )
        }

        return PadZOrderAction.PlaceAtNormalTop(demoteFromTopMost = padAlwaysOnTop)
    }

    /**
     * 判断控制条获得焦点后是否需要把投屏窗口重新拉回控制条正下方。
     * 投屏窗口 id 无效时不动；投屏窗口已经紧贴在下方时也不动。
     */
    fun shouldRaiseMirrorBelowPad(
        mirrorWindowId: Long,
        windowBelowPadId: Long?
    ): Boolean {
        if (mirrorWindowId == 0L) return false
        if (windowBelowPadId == null) return true
        return windowBelowPadId != mirrorWindowId
    }

    /**
     * 从自底向上的窗口堆叠列表里，取指定窗口正上方的相邻窗口。
     */
    fun findWindowAboveInStack(
        stackBottomToTop: List<Long>,
        targetWindowId: Long
    ): Long? {
        if (targetWindowId == 0L) return null
        val index = stackBottomToTop.indexOf(targetWindowId)
        return if (index >= 0 && index + 1 < stackBottomToTop.size) {
            stackBottomToTop[index + 1]
        } else {
            null
        }
    }

    /**
     * 从自底向上的窗口堆叠列表里，取指定窗口正下方的相邻窗口。
     */
    fun findWindowBelowInStack(
        stackBottomToTop: List<Long>,
        targetWindowId: Long
    ): Long? {
        if (targetWindowId == 0L) return null
        val index = stackBottomToTop.indexOf(targetWindowId)
        return if (index > 0) stackBottomToTop[index - 1] else null
    }

    /**
     * 把 Win32 返回的物理像素窗口矩形换算成 AWT/Compose 使用的逻辑像素坐标。
     *
     * Windows 在 125%/150% 等缩放比例下，`GetWindowRect`/DWM 拿到的是物理像素，
     * 而 `Window.setLocation`、`GraphicsConfiguration.bounds` 用的是逻辑像素。
     * 两者混用会让控制条在缩放屏上整体偏移（150% 时坐标被再乘 1.5）。
     *
     * 左右/上下边沿分别取整再算宽高，避免宽高各自取整后出现 1px 缝隙。
     *
     * @param scale 窗口所在显示器的缩放比例（100% 为 1.0）
     */
    fun toLogicalBounds(bounds: ScrcpyWindowBounds, scale: Double): ScrcpyWindowBounds {
        if (scale <= 0.0 || !scale.isFinite() || scale == 1.0) return bounds
        val left = (bounds.x / scale).roundToInt()
        val top = (bounds.y / scale).roundToInt()
        val right = ((bounds.x + bounds.width) / scale).roundToInt()
        val bottom = ((bounds.y + bounds.height) / scale).roundToInt()
        return ScrcpyWindowBounds(
            x = left,
            y = top,
            width = (right - left).coerceAtLeast(1),
            height = (bottom - top).coerceAtLeast(1)
        )
    }

    /**
     * 判断窗口标题是否属于指定设备，并排除控制条自身。
     * 正常标题是“设备名 (完整序列号)”，用户自定义标题时这里匹配不到，贴合会退化为固定右缘。
     */
    fun matchesScrcpyWindowTitle(title: String, serial: String): Boolean {
        if (serial.isBlank()) return false
        val normalizedTitle = title.trim()
        if (normalizedTitle.startsWith("控制条")) return false
        return normalizedTitle.contains("($serial)")
    }

    /**
     * 从已占用槽位中找出最小可用槽位，保证控制条关闭后槽位可以复用。
     */
    fun allocateSlot(usedSlots: Set<Int>): Int {
        var slot = 0
        while (slot in usedSlots) {
            slot++
        }
        return slot
    }

    /**
     * 生成投屏窗口标题，包含完整序列号，保证同型号设备可以区分。
     */
    fun buildWindowTitle(model: String, serial: String): String {
        val normalizedModel = model.replace(Regex("\\s+"), " ").trim()
            .ifEmpty { "Android 设备" }
        val normalizedSerial = serial.trim()
        return if (normalizedSerial.isEmpty()) normalizedModel else "$normalizedModel ($normalizedSerial)"
    }

    /**
     * 判断用户自定义参数里是否已经指定了 scrcpy 窗口标题。
     * 支持分开写和等号写两种形式。
     */
    fun hasCustomWindowTitle(arguments: List<String>): Boolean {
        return arguments.any { it == "--window-title" || it.startsWith("--window-title=") }
    }
}

/**
 * 控制条下一次 Z 轴重排动作。
 */
sealed interface PadZOrderAction {
    /** 已经对齐，无需动作。 */
    data object NoOp : PadZOrderAction

    /** 直接放在置顶层最上面。 */
    data object PlaceAtTopMost : PadZOrderAction

    /**
     * 放在普通层级最上面。
     *
     * @param demoteFromTopMost 控制条当前处于置顶层，需要先降回普通层级
     */
    data class PlaceAtNormalTop(
        val demoteFromTopMost: Boolean
    ) : PadZOrderAction

    /**
     * 放到指定兄弟窗口正下方。
     *
     * @param siblingWindowId 目标兄弟窗口 id
     * @param targetAlwaysOnTop 控制条应处于的层级带
     * @param changeTopMostBand 是否需要切换层级带
     */
    data class PlaceBelowSibling(
        val siblingWindowId: Long,
        val targetAlwaysOnTop: Boolean,
        val changeTopMostBand: Boolean
    ) : PadZOrderAction
}

/**
 * 控制条左上角的像素坐标。
 */
data class PixelPosition(val x: Int, val y: Int)
