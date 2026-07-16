#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import subprocess
import shutil
import re
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
import threading
import queue
import webbrowser

# 获取当前脚本的绝对路径目录
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def find_executable(name):
    """
    寻找可执行文件路径：优先检测脚本同级目录，其次检测系统 PATH。
    """
    # 优先检测本地路径
    local_path = os.path.join(SCRIPT_DIR, name)
    if os.path.exists(local_path) and os.access(local_path, os.X_OK):
        return local_path
    
    # 其次检测系统 PATH
    system_path = shutil.which(name)
    if system_path:
        return system_path
        
    return None

# 自动定位 adb 和 scrcpy 的路径
ADB_PATH = find_executable("adb")
SCRCPY_PATH = find_executable("scrcpy")

class ScrcpyConnectorApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Scrcpy 快捷连接器")
        self.root.geometry("680x450")
        self.root.resizable(False, False)
        
        # 设置样式
        self.style = ttk.Style()
        self.style.theme_use("clam")
        
        # 初始化设备列表数据
        self.devices = []
        # 初始化运行中的进程管理器
        self.running_processes = {}
        
        # 创建 GUI 组件
        self.create_widgets()
        
        # 初始加载设备列表
        self.refresh_device_list()

    def create_widgets(self):
        # 底部作者开源信息栏 (方案 B)
        footer_frame = ttk.Frame(self.root, padding=(10, 0, 10, 10))
        footer_frame.pack(side="bottom", fill="x")
        
        github_url = "https://github.com/doncc/scrcpy-go"
        
        # 使用 Label 作为超链接样式
        link_label = tk.Label(
            footer_frame,
            text=f"⭐ 觉得好用请给个 Star: {github_url}",
            fg="#0066cc",      # 经典的超链接蓝色
            cursor="hand2",     # 鼠标悬停时显示手型
            font=("Arial", 9)
        )
        link_label.pack(side="left")
        
        # 绑定点击链接时打开浏览器
        link_label.bind("<Button-1>", lambda event: webbrowser.open_new(github_url))
        
        # 鼠标悬停时的下划线效果
        link_label.bind("<Enter>", lambda event: link_label.config(font=("Arial", 9, "underline")))
        link_label.bind("<Leave>", lambda event: link_label.config(font=("Arial", 9)))

        # 整体左右分栏
        main_frame = ttk.Frame(self.root, padding=(10, 10, 10, 0))
        main_frame.pack(fill="both", expand=True)
        
        # 左栏：设备列表 (分配扩展空间)
        left_frame = ttk.LabelFrame(main_frame, text=" 已连接设备列表 (双击直接启动) ", padding=5)
        left_frame.pack(side="left", fill="both", expand=True, padx=(0, 5))
        
        scrollbar = ttk.Scrollbar(left_frame)
        scrollbar.pack(side="right", fill="y")
        
        self.device_listbox = tk.Listbox(
            left_frame, 
            yscrollcommand=scrollbar.set, 
            font=("Arial", 10), 
            selectmode=tk.SINGLE,
            highlightthickness=0
        )
        self.device_listbox.pack(fill="both", expand=True, side="left")
        scrollbar.config(command=self.device_listbox.yview)
        
        # 绑定双击事件
        self.device_listbox.bind("<Double-Button-1>", lambda event: self.connect_selected_device())
        
        # 右栏：控制面板区域
        right_frame = ttk.Frame(main_frame)
        right_frame.pack(side="right", fill="y", padx=(5, 0))
        
        # 1. 运行环境检测
        path_frame = ttk.LabelFrame(right_frame, text=" 运行环境检测 ", padding=5)
        path_frame.pack(fill="x", pady=(0, 5))
        
        adb_status = ADB_PATH if ADB_PATH else "未找到 (请确保 adb 存在)"
        scrcpy_status = SCRCPY_PATH if SCRCPY_PATH else "未找到 (请确保 scrcpy 存在)"
        
        ttk.Label(path_frame, text=f"ADB: {adb_status}", foreground="green" if ADB_PATH else "red").pack(anchor="w")
        ttk.Label(path_frame, text=f"Scrcpy: {scrcpy_status}", foreground="green" if SCRCPY_PATH else "red").pack(anchor="w")
        
        # 2. 设备操作按钮
        op_frame = ttk.LabelFrame(right_frame, text=" 设备操作 ", padding=5)
        op_frame.pack(fill="x", pady=5)
        
        ttk.Button(op_frame, text="连接选中设备", command=self.connect_selected_device).pack(fill="x", pady=2)
        ttk.Button(op_frame, text="断开选中设备", command=self.disconnect_selected_device).pack(fill="x", pady=2)
        ttk.Button(op_frame, text="查看 Logcat 日志", command=self.open_logcat_window).pack(fill="x", pady=2)
        ttk.Button(op_frame, text="刷新设备列表", command=self.refresh_device_list).pack(fill="x", pady=2)
        
        # 3. 手动 TCP 连接
        tcp_frame = ttk.LabelFrame(right_frame, text=" 手动 TCP 连接 ", padding=5)
        tcp_frame.pack(fill="x", pady=5)
        
        ttk.Label(tcp_frame, text="设备 IP (端口默认 5555):").pack(anchor="w", pady=(0, 2))
        self.ip_entry = ttk.Entry(tcp_frame, width=28)
        self.ip_entry.insert(0, "192.168.100.254")
        self.ip_entry.pack(fill="x", pady=(0, 5))
        
        # 并排排列连接与断开按钮
        tcp_btn_frame = ttk.Frame(tcp_frame)
        tcp_btn_frame.pack(fill="x")
        ttk.Button(tcp_btn_frame, text="TCP 连接", command=self.connect_tcp_device).pack(side="left", fill="x", expand=True, padx=(0, 2))
        ttk.Button(tcp_btn_frame, text="TCP 断开", command=self.disconnect_tcp_device).pack(side="right", fill="x", expand=True, padx=(2, 0))
        
        # 4. 参数配置与快捷方式注册
        bottom_frame = ttk.LabelFrame(right_frame, text=" 参数与配置 ", padding=5)
        bottom_frame.pack(fill="x", pady=5)
        
        ttk.Label(bottom_frame, text="Scrcpy 参数:").pack(anchor="w", pady=(0, 2))
        self.args_entry = ttk.Entry(bottom_frame, width=28)
        self.args_entry.insert(0, "--no-audio")
        self.args_entry.pack(fill="x", pady=(0, 5))
        
        ttk.Button(bottom_frame, text="创建桌面快捷方式", command=self.install_desktop_shortcut).pack(fill="x")

    def run_command(self, cmd):
        """
        辅助函数：执行命令并返回输出与错误
        """
        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
            return res.stdout.strip(), ""
        except subprocess.CalledProcessError as e:
            return e.stdout.strip(), e.stderr.strip()
        except Exception as e:
            return "", str(e)

    def refresh_device_list(self):
        """
        刷新已连接的 ADB 设备列表
        """
        self.device_listbox.delete(0, tk.END)
        self.devices = []
        
        if not ADB_PATH:
            self.device_listbox.insert(tk.END, "未检测到 ADB，无法获取设备列表。")
            return
            
        stdout, stderr = self.run_command([ADB_PATH, "devices", "-l"])
        if stderr:
            self.device_listbox.insert(tk.END, f"获取设备失败: {stderr}")
            return
            
        lines = stdout.splitlines()
        # 解析 adb devices -l 输出
        # 第一行为 "List of devices attached"
        for line in lines[1:]:
            line = line.strip()
            if not line:
                continue
            
            # 例如: "192.168.100.254:5555   device product:sdk_gphone64_arm64 model:gphone64_arm64..."
            parts = re.split(r'\s+', line)
            if len(parts) >= 2:
                serial = parts[0]
                status = parts[1]
                
                # 解析 model 属性
                model = "未知设备"
                for p in parts[2:]:
                    if p.startswith("model:"):
                        model = p.split(":", 1)[1]
                        break
                
                # 区分是 TCP 连接还是 USB 连接，方便展示
                conn_type = "TCP" if ":" in serial else "USB"
                display_str = f"[{conn_type}] {model} ({serial}) - {status}"
                
                self.devices.append({"serial": serial, "model": model, "type": conn_type})
                self.device_listbox.insert(tk.END, display_str)
                
        if not self.devices:
            self.device_listbox.insert(tk.END, "未发现已连接的设备，请插入 USB 或使用 TCP 连接")

    def get_custom_args(self):
        """
        获取用户输入的附加参数列表
        """
        args_str = self.args_entry.get().strip()
        if not args_str:
            return []
        # 按空格切分，保留正常的参数数组
        return re.split(r'\s+', args_str)

    def launch_scrcpy(self, serial):
        """
        启动 scrcpy 连接特定设备
        """
        if not SCRCPY_PATH:
            messagebox.showerror("错误", "找不到 scrcpy 执行程序，无法启动连接！")
            return
            
        cmd = [SCRCPY_PATH, "-s", serial] + self.get_custom_args()
        
        # 将 scrcpy_server 环境变量等配置好以防本地执行报错
        env = os.environ.copy()
        # 如果是本地打包的 scrcpy，将当前路径加入临时环境变量中，以便 scrcpy 找到 scrcpy-server
        if os.path.exists(os.path.join(SCRIPT_DIR, "scrcpy-server")):
            env["SCRCPY_SERVER_PATH"] = os.path.join(SCRIPT_DIR, "scrcpy-server")
            
        if ADB_PATH:
            env["ADB"] = ADB_PATH

        try:
            # 异步启动 scrcpy，免得阻塞 GUI 线程
            proc = subprocess.Popen(cmd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            # 记录该设备的进程
            self.running_processes[serial] = proc
        except Exception as e:
            messagebox.showerror("错误", f"启动 scrcpy 失败:\n{str(e)}")

    def connect_selected_device(self):
        """
        连接在 Listbox 中选中的设备
        """
        selected_indices = self.device_listbox.curselection()
        if not selected_indices:
            messagebox.showwarning("提示", "请先在列表中选中一个设备！")
            return
            
        idx = selected_indices[0]
        if idx >= len(self.devices):
            # 防止点击了 "未发现已连接的设备" 提示行
            return
            
        device = self.devices[idx]
        self.launch_scrcpy(device["serial"])

    def disconnect_selected_device(self):
        """
        断开选中设备的投屏进程（如果是 TCP 设备则同时执行 adb disconnect）
        """
        selected_indices = self.device_listbox.curselection()
        if not selected_indices:
            messagebox.showwarning("提示", "请先在列表中选中一个设备！")
            return
            
        idx = selected_indices[0]
        if idx >= len(self.devices):
            return
            
        device = self.devices[idx]
        serial = device["serial"]
        
        # 1. 尝试结束该设备的投屏进程
        proc = self.running_processes.get(serial)
        if proc and proc.poll() is None:
            try:
                proc.terminate()
                proc.wait(timeout=1.0) # 等待进程退出
            except Exception:
                try:
                    proc.kill() # 强行杀掉
                except Exception:
                    pass
            # 从进程管理器中移除
            self.running_processes.pop(serial, None)
            
        # 2. 刷新列表
        self.refresh_device_list()

    def connect_tcp_device(self):
        """
        手动通过 TCP IP 连接设备（仅做 ADB 连接，不启动 Scrcpy）
        """
        if not ADB_PATH:
            messagebox.showerror("错误", "找不到 ADB 执行程序，无法发起连接！")
            return
            
        ip = self.ip_entry.get().strip()
        if not ip:
            messagebox.showwarning("提示", "请输入设备的 IP 地址！")
            return
            
        # 自动补全默认 5555 端口
        if ":" not in ip:
            ip_port = f"{ip}:5555"
        else:
            ip_port = ip
            
        # 执行 adb connect
        stdout, stderr = self.run_command([ADB_PATH, "connect", ip_port])
        
        self.refresh_device_list()
        if "connected to" in stdout:
            messagebox.showinfo("TCP 连接成功", f"已成功连接到设备 {ip_port}。\n您可以在设备列表中选中它，并点击“连接选中设备”进行投屏。")
        else:
            err_msg = stderr if stderr else stdout
            messagebox.showerror("TCP 连接失败", f"连接 {ip_port} 失败，错误信息:\n{err_msg}")

    def disconnect_tcp_device(self):
        """
        手动断开指定 TCP 设备的 ADB 网络连接
        """
        if not ADB_PATH:
            messagebox.showerror("错误", "找不到 ADB 执行程序，无法断开连接！")
            return
            
        ip = self.ip_entry.get().strip()
        if not ip:
            messagebox.showwarning("提示", "请输入要断开的设备 IP 地址！")
            return
            
        if ":" not in ip:
            ip_port = f"{ip}:5555"
        else:
            ip_port = ip
            
        # 执行 adb disconnect
        stdout, stderr = self.run_command([ADB_PATH, "disconnect", ip_port])
        
        self.refresh_device_list()
        if "disconnected" in stdout or (stderr and "disconnected" in stderr) or not stderr:
            messagebox.showinfo("TCP 断开成功", f"已成功断开与设备 {ip_port} 的网络连接。")
        else:
            err_msg = stderr if stderr else stdout
            messagebox.showerror("TCP 断开失败", f"断开 {ip_port} 失败，错误信息:\n{err_msg}")

    def install_desktop_shortcut(self):
        """
        在用户 Linux 桌面上生成 .desktop 快捷方式，自动加入应用菜单
        """
        desktop_dir = os.path.expanduser("~/.local/share/applications")
        os.makedirs(desktop_dir, exist_ok=True)
        
        desktop_file_path = os.path.join(desktop_dir, "scrcpy-go.desktop")
        
        # 寻找图标，默认使用本目录下自带的 scrcpy.png
        icon_path = os.path.join(SCRIPT_DIR, "scrcpy.png")
        if not os.path.exists(icon_path):
            icon_path = "phone" # 回退到系统通用手机图标
            
        desktop_content = f"""[Desktop Entry]
Version=1.0
Type=Application
Name=Scrcpy Go
Comment=Scrcpy 快捷连接工具
Exec=python3 {os.path.join(SCRIPT_DIR, "scrcpy-go.py")}
Icon={icon_path}
Terminal=false
Categories=Utility;Development;
StartupNotify=false
StartupWMClass=scrcpy-go
"""
        try:
            with open(desktop_file_path, "w", encoding="utf-8") as f:
                f.write(desktop_content)
            # 赋予执行权限
            os.chmod(desktop_file_path, 0o755)
            messagebox.showinfo("注册成功", f"已成功将快捷连接器注册至应用菜单！\n文件保存于:\n{desktop_file_path}")
        except Exception as e:
            messagebox.showerror("注册失败", f"创建快捷方式时发生错误:\n{str(e)}")

    def open_logcat_window(self):
        """
        打开选中设备的 Logcat 日志窗口
        """
        selected_indices = self.device_listbox.curselection()
        if not selected_indices:
            messagebox.showwarning("提示", "请先在列表中选中一个设备！")
            return
            
        idx = selected_indices[0]
        if idx >= len(self.devices):
            return
            
        device = self.devices[idx]
        LogcatWindow(self.root, device["serial"], device["model"])

# ==============================================================================
# Logcat 日志控制台窗口类
# ==============================================================================
class LogcatWindow(tk.Toplevel):
    def __init__(self, parent, serial, model):
        super().__init__(parent)
        self.serial = serial
        self.model = model
        self.title(f"Logcat 控制台 - {model} ({serial})")
        self.geometry("900x550")
        
        # 记录日志状态与缓存
        self.is_running = False
        self.queue_loop_active = False
        self.log_cache = []
        self.current_pids = set()
        self.log_process = None
        self.read_thread = None
        self.pid_thread = None
        self.package_name = ""
        
        self.log_queue = queue.Queue()
        
        self.create_widgets()
        self.configure_tags()
        
        # 默认不启动抓取，只在点击“开始”后建立 ADB 连接并流式读取
        
        # 注册关闭事件
        self.protocol("WM_DELETE_WINDOW", self.on_close)

    def create_widgets(self):
        # 顶部过滤与控制工具栏
        toolbar = ttk.Frame(self, padding=5)
        toolbar.pack(fill="x", side="top")
        
        ttk.Label(toolbar, text="过滤包名:").pack(side="left", padx=5)
        self.filter_entry = ttk.Entry(toolbar, width=30)
        self.filter_entry.pack(side="left", padx=5)
        self.filter_entry.bind("<Return>", lambda event: self.apply_filter())
        
        ttk.Button(toolbar, text="应用过滤", command=self.apply_filter).pack(side="left", padx=5)
        

        
        self.save_btn = ttk.Button(toolbar, text="保存日志", command=self.save_logs)
        self.save_btn.pack(side="right", padx=5)
        
        self.clear_btn = ttk.Button(toolbar, text="清空屏幕", command=self.clear_screen)
        self.clear_btn.pack(side="right", padx=5)
        
        self.toggle_btn = ttk.Button(toolbar, text="开始", command=self.toggle_logging)
        self.toggle_btn.pack(side="right", padx=5)

        # 中部日志输出区
        main_frame = ttk.Frame(self, padding=5)
        main_frame.pack(fill="both", expand=True)
        
        scrollbar = ttk.Scrollbar(main_frame)
        scrollbar.pack(side="right", fill="y")
        
        self.log_text = tk.Text(
            main_frame, 
            yscrollcommand=scrollbar.set, 
            wrap="none", 
            font=("Courier New", 9), 
            state="disabled",
            bg="#FFFFFF"
        )
        self.log_text.pack(fill="both", expand=True, side="left")
        scrollbar.config(command=self.log_text.yview)

        # 底部状态栏 (独立一行，解决横向挤压显示不全的问题)
        status_bar = ttk.Frame(self, padding=2, relief="sunken")
        status_bar.pack(fill="x", side="bottom")
        
        self.status_label = ttk.Label(status_bar, text="日志已暂停 (点击开始抓取)", foreground="gray")
        self.status_label.pack(side="left", padx=5)

    def configure_tags(self):
        # 配置染色高亮 Tag
        self.log_text.tag_config("V", foreground="#BBBBBB") # Verbose
        self.log_text.tag_config("D", foreground="#888888") # Debug
        self.log_text.tag_config("I", foreground="#008800") # Info (深绿)
        self.log_text.tag_config("W", foreground="#FF8C00") # Warning (橙黄)
        self.log_text.tag_config("E", foreground="#FF0000") # Error (红)
        self.log_text.tag_config("F", foreground="#FF0000", background="#FFCCCC") # Fatal
        self.log_text.tag_config("default", foreground="#000000")

    def toggle_logging(self):
        if self.is_running:
            self.stop_logging()
            self.toggle_btn.config(text="开始")
        else:
            self.start_logging()
            self.toggle_btn.config(text="暂停")

    def start_logging(self):
        if self.is_running:
            return
        self.is_running = True
        
        # 激活/维持 GUI 刷新队列循环
        if not self.queue_loop_active:
            self.process_queue()
        
        # 启动日志读取线程
        self.read_thread = threading.Thread(target=self.read_logs, daemon=True)
        self.read_thread.start()
        
        # 启动包名 PID 轮询线程
        self.pid_thread = threading.Thread(target=self.update_pids_loop, daemon=True)
        self.pid_thread.start()

    def stop_logging(self):
        self.is_running = False
        if self.log_process:
            try:
                self.log_process.terminate()
            except Exception:
                pass
            self.log_process = None

    def apply_filter(self):
        pkg = self.filter_entry.get().strip()
        self.package_name = pkg
        self.current_pids.clear()
        
        # 变更过滤条件时自动清空历史以便直观展示
        self.clear_screen()
        
        if pkg:
            self.status_label.config(text=f"过滤包名: {pkg}", foreground="green")
            # 立即进行一次同步 PID 获取以防止最初漏掉日志
            self.current_pids = self.get_app_pids(pkg)
            if not self.current_pids:
                self.insert_log_line(">>> [系统提示] 未找到该包名对应的进程 PID，正在等待应用启动...\n", "W")
        else:
            self.status_label.config(text="全局日志抓取中" if self.is_running else "日志已暂停 (点击开始抓取)", foreground="gray" if self.is_running else "gray")

    def clear_screen(self):
        self.log_text.config(state="normal")
        self.log_text.delete(1.0, tk.END)
        self.log_text.config(state="disabled")
        # 清空日志缓存
        self.log_cache.clear()

    def insert_log_line(self, raw_line, level):
        self.log_text.config(state="normal")
        if not raw_line.endswith('\n'):
            raw_line += '\n'
        self.log_text.insert(tk.END, raw_line, (level,))
        self.log_text.see(tk.END)
        self.log_text.config(state="disabled")

    def insert_log_batch(self, batch):
        """
        批量向文本框中插入日志，以减轻重绘负担，并实现滚动裁剪限制最大行数为 3000 行
        """
        self.log_text.config(state="normal")
        for level, lines in batch.items():
            chunk = "".join(lines)
            self.log_text.insert(tk.END, chunk, (level,))
            
        # 限制总显示行数最大为 3000 行
        num_lines = int(self.log_text.index('end-1c').split('.')[0])
        if num_lines > 3000:
            self.log_text.delete('1.0', f'{num_lines - 3000 + 1}.0')
            
        self.log_text.see(tk.END)
        self.log_text.config(state="disabled")

    def save_logs(self):
        if not self.log_cache:
            messagebox.showwarning("提示", "日志缓存为空，无可保存内容！")
            return
            
        file_path = filedialog.asksaveasfilename(
            parent=self,
            title="保存日志文件",
            defaultextension=".log",
            filetypes=[("Log Files", "*.log"), ("Text Files", "*.txt"), ("All Files", "*.*")]
        )
        if not file_path:
            return
            
        try:
            with open(file_path, "w", encoding="utf-8") as f:
                f.writelines(self.log_cache)
            messagebox.showinfo("成功", f"日志文件已成功导出至:\n{file_path}")
        except Exception as e:
            messagebox.showerror("导出失败", f"保存日志时发生错误:\n{str(e)}")

    def get_app_pids(self, package_name):
        if not ADB_PATH or not package_name:
            return set()
        pids = set()
        try:
            # 优先使用 pidof
            cmd = [ADB_PATH, "-s", self.serial, "shell", "pidof", package_name]
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=1.5)
            output = res.stdout.strip()
            if output:
                return set(output.split())
        except Exception:
            pass
            
        try:
            # 备选使用 ps 扫描
            cmd = [ADB_PATH, "-s", self.serial, "shell", "ps -A || ps"]
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=2.0)
            for line in res.stdout.splitlines():
                if package_name in line:
                    parts = re.split(r'\s+', line.strip())
                    if len(parts) >= 2 and parts[1].isdigit():
                        pids.add(parts[1])
        except Exception:
            pass
        return pids

    def update_pids_loop(self):
        while self.is_running:
            if self.package_name:
                self.current_pids = self.get_app_pids(self.package_name)
            # 每 2 秒轮询一次
            for _ in range(20):
                if not self.is_running:
                    break
                import time
                time.sleep(0.1)

    def read_logs(self):
        # 加上 -T 1 参数，仅抓取启动/开始瞬间之后的新日志，彻底消灭初次加载带来的卡顿
        cmd = [ADB_PATH, "-s", self.serial, "logcat", "-v", "threadtime", "-T", "1"]
        env = os.environ.copy()
        if ADB_PATH:
            env["ADB"] = ADB_PATH
            
        try:
            self.log_process = subprocess.Popen(
                cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1
            )
            
            # 正则表达式匹配: 07-16 14:50:23.123  1234  5678 I Tag: Msg
            log_pattern = re.compile(
                r'^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?):(.*)$'
            )
            
            while self.is_running and self.log_process.poll() is None:
                line = self.log_process.stdout.readline()
                if not line:
                    break
                
                raw_line = line
                line_stripped = line.rstrip('\n')
                
                match = log_pattern.match(line_stripped)
                if match:
                    timestamp, pid, tid, level, tag, msg = match.groups()
                    
                    if self.package_name:
                        # 过滤非该应用 PID 的日志
                        if pid not in self.current_pids:
                            continue
                            
                    self.log_queue.put((raw_line, level))
                else:
                    # 对于非标准系统日志，只在没有配置包名过滤时输出
                    if not self.package_name:
                        self.log_queue.put((raw_line, "default"))
            
            # 读取退出时检测错误原因
            if self.log_process:
                try:
                    self.log_process.wait(timeout=0.5)
                except subprocess.TimeoutExpired:
                    pass
                if self.log_process.returncode is not None and self.log_process.returncode != 0:
                    err_msg = self.log_process.stderr.read()
                    if err_msg:
                        self.log_queue.put((f"\n>>> [ADB 异常退出] (错误码: {self.log_process.returncode})\n{err_msg.strip()}\n", "E"))
        except Exception as e:
            self.log_queue.put((f"Logcat 读取出现异常: {str(e)}\n", "E"))
        finally:
            if self.log_process:
                try:
                    self.log_process.terminate()
                except Exception:
                    pass
                self.log_process = None

    def process_queue(self):
        self.queue_loop_active = True
        batch = {}
        try:
            while True:
                raw_line, level = self.log_queue.get_nowait()
                self.log_cache.append(raw_line)
                if level not in batch:
                    batch[level] = []
                batch[level].append(raw_line)
                self.log_queue.task_done()
        except queue.Empty:
            pass
            
        if batch:
            self.insert_log_batch(batch)
            
        if self.is_running or not self.log_queue.empty():
            self.after(50, self.process_queue)
        else:
            self.queue_loop_active = False

    def on_close(self):
        self.stop_logging()
        self.log_cache.clear()
        self.destroy()

if __name__ == "__main__":
    root = tk.Tk(className="scrcpy-go")
    
    # 尝试加载并设置窗口图标
    icon_path = os.path.join(SCRIPT_DIR, "scrcpy.png")
    if os.path.exists(icon_path):
        try:
            img = tk.PhotoImage(file=icon_path)
            root.iconphoto(True, img)
        except Exception:
            pass
            
    app = ScrcpyConnectorApp(root)
    root.mainloop()
