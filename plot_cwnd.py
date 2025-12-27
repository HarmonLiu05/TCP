#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TCP Reno 拥塞窗口可视化脚本
从日志文件中提取 CWND 和 SSTHRESH 数据并绘图
包含快重传、快恢复、以及超时重传是序
"""

import re
import matplotlib.pyplot as plt
import sys

def parse_log_file(log_file):
    """解析日志文件，提取时间、cwnd和ssthresh数据"""
    times = []
    cwnds = []
    ssthreshs = []
    
    # 正则表达式匹配: CWND_LOG: TIME: 1234, CWND: 16.00, SSTHRESH: 8
    pattern = r'CWND_LOG: TIME: (\d+), CWND: ([\d.]+), SSTHRESH: (\d+)'
    
    try:
        with open(log_file, 'r', encoding='utf-8') as f:
            for line in f:
                match = re.search(pattern, line)
                if match:
                    time_ms = int(match.group(1))
                    cwnd = float(match.group(2))
                    ssthresh = int(match.group(3))
                    
                    times.append(time_ms / 1000.0)  # 转换为秒
                    cwnds.append(cwnd)
                    ssthreshs.append(ssthresh)
        
        print(f"成功解析 {len(times)} 条数据记录")
        return times, cwnds, ssthreshs
    
    except FileNotFoundError:
        print(f"错误: 找不到文件 '{log_file}'")
        print("请先运行 PowerShell 命令提取数据:")
        print('  Select-String -Path tcp_output.log -Pattern "CWND_LOG" | ForEach-Object { $_.Line } | Out-File -FilePath cwnd_data.txt -Encoding UTF8')
        sys.exit(1)
    except Exception as e:
        print(f"解析错误: {e}")
        sys.exit(1)

def plot_cwnd(times, cwnds, ssthreshs, output_file='cwnd_plot.png'):
    """绘制 CWND 和 SSTHRESH 随时间变化的图表"""
    
    plt.figure(figsize=(12, 6))
    
    # 绘制 CWND
    plt.plot(times, cwnds, 'b-', linewidth=2, label='CWND (Congestion Window)', marker='o', markersize=3)
    
    # 绘制 SSTHRESH
    plt.plot(times, ssthreshs, 'r--', linewidth=2, label='SSTHRESH (Slow Start Threshold)', marker='s', markersize=3)
    
    # 图表配置
    plt.xlabel('Time (seconds)', fontsize=12)
    plt.ylabel('Window Size (packets)', fontsize=12)
    plt.title('TCP Reno Congestion Control - CWND & SSTHRESH (with Fast Recovery)', fontsize=14, fontweight='bold')
    plt.legend(loc='best', fontsize=10)
    plt.grid(True, alpha=0.3)
    
    # 设置坐标轴
    plt.xlim(left=0)
    plt.ylim(bottom=0)
    
    # 保存图片
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"图表已保存到: {output_file}")
    
    # 显示图表
    plt.show()

def main():
    """主函数"""
    if len(sys.argv) > 1:
        log_file = sys.argv[1]
    else:
        log_file = 'cwnd_data.txt'  # 默认使用提取后的文件
        print(f"使用默认数据文件: {log_file}")
        print("用法: python plot_cwnd.py <数据文件路径>")
        print("提示: 先运行 PowerShell 命令提取数据:")
        print('  Select-String -Path tcp_output.log -Pattern "CWND_LOG" | ForEach-Object { $_.Line } | Out-File -FilePath cwnd_data.txt -Encoding UTF8')
        print()
    
    # 解析日志
    times, cwnds, ssthreshs = parse_log_file(log_file)
    
    if len(times) == 0:
        print("警告: 未找到任何 CWND_LOG 数据")
        print("请确保运行 TCP 程序时输出了日志，格式如:")
        print("CWND_LOG: TIME: 1234, CWND: 16.00, SSTHRESH: 8")
        sys.exit(1)
    
    # 绘图
    plot_cwnd(times, cwnds, ssthreshs)
    
    # 统计信息
    print(f"\n数据统计:")
    print(f"  总时长: {times[-1]:.2f} 秒")
    print(f"  最大 CWND: {max(cwnds):.2f}")
    print(f"  最小 CWND: {min(cwnds):.2f}")
    print(f"  SSTHRESH 变化次数: {len(set(ssthreshs)) - 1}")

if __name__ == '__main__':
    main()
