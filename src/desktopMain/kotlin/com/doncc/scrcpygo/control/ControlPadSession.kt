package com.doncc.scrcpygo.control

/**
 * 一次投屏对应的控制条会话。
 *
 * process 用于在进程退出回调里判断自己是否仍是当前会话，
 * 避免同一设备快速重连时旧回调误删新控制条。
 */
class ControlPadSession(
    val serial: String,
    val model: String,
    val process: Process,
    val slotIndex: Int
)
