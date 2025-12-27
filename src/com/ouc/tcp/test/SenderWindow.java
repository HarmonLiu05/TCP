package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;
import java.util.LinkedList;
import java.util.Iterator;

/**
 * 发送方窗口管理类 - TCP Tahoe协议
 * 使用LinkedList动态数据结构（老师要求：禁止静态数组）
 * 实现完整的拥塞控制：慢开始、拥塞避免、快重传、超时重传
 */
public class SenderWindow {
    // ===== 老师要求1：使用动态数据结构，禁止静态数组 =====
    private LinkedList<WindowElem> window;  // 动态链表，避免内存泄漏
    
    // ===== 拥塞控制变量 =====
    private double cwnd;           // 拥塞窗口（浮点数，支持精确增长）
    private int ssthresh;          // 慢开始阈值
    private static final int MAX_CWND = 64;  // 最大拥塞窗口
    
    // ===== 快重传相关 =====
    private int dupAckCount;       // 重复ACK计数
    private int lastAckSeq;        // 上一次收到的ACK序号
    
    // ===== 序列号管理 =====
    private int nextSeqNum;        // 下一个要分配的序列号
    
    // ===== 定时器和客户端 =====
    private static final long TIMEOUT_MS = 3000;
    private Client client;
    private UDT_Timer baseTimer;   // 单一全局定时器
    private TCP_Sender sender;
    
    /**
     * 构造函数
     */
    public SenderWindow(Client client, TCP_Sender sender) {
        // 初始化动态链表
        this.window = new LinkedList<>();
        
        // 初始化拥塞控制参数
        // 慢开始阶段：初始 cwnd 为 1
        this.cwnd = 1.0;           // 慢开始：初始窗口为 1
        this.ssthresh = 16;        // 阈值设为 16
        
        // 初始化快重传
        this.dupAckCount = 0;
        this.lastAckSeq = -1;
        
        // 初始化序列号
        this.nextSeqNum = 0;
        
        // 初始化定时器
        this.client = client;
        this.sender = sender;
        this.baseTimer = null;
        
        System.out.println("TCP Tahoe启动 - cwnd=" + cwnd + ", ssthresh=" + ssthresh);
    }
    
    /**
     * 检查窗口是否已满 - 老师要求3：由cwnd决定
     */
    public synchronized boolean isFull() {
        return window.size() >= (int)cwnd;
    }
    
    /**
     * 检查窗口是否为空
     */
    public synchronized boolean isEmpty() {
        return window.isEmpty();
    }
    
    /**
     * 将新包加入窗口
     */
    public synchronized void pushPacket(TCP_PACKET packet) {
        WindowElem elem = new WindowElem();
        elem.setPacket(packet);
        elem.setFlag(WindowElem.NOT_ACKED);
        window.addLast(elem);
        
        System.out.println("TCP入窗 - seq=" + packet.getTcpH().getTh_seq() + 
                         " (window.size=" + window.size() + ", cwnd=" + String.format("%.2f", cwnd) + ")");
    }
    
    /**
     * 发送数据包并启动/重启定时器
     * @param packet 要发送的数据包
     */
    public synchronized void sendPacket(TCP_PACKET packet, TCP_Sender sender) {
        if (packet != null) {
            // 发送数据包
            sender.udt_send(packet);
            
            System.out.println("TCP发送 - seq=" + packet.getTcpH().getTh_seq() + 
                             " (window.size=" + window.size() + ", cwnd=" + String.format("%.2f", cwnd) + ")");
            
            // 如果窗口非空，确保定时器运行
            if (!window.isEmpty() && baseTimer == null) {
                startTimer();
            }
        }
    }
    
    /**
     * 启动全局定时器
     */
    private void startTimer() {
        stopTimer();
        
        baseTimer = new UDT_Timer();
        baseTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                onTimeout();
            }
        }, TIMEOUT_MS);
        
        System.out.println("TCP启动定时器");
    }
    
    /**
     * 停止全局定时器
     */
    private void stopTimer() {
        if (baseTimer != null) {
            baseTimer.cancel();
            baseTimer.purge();
            baseTimer = null;
        }
    }
    
    /**
     * 超时处理 - 老师要求4：只重传队首一个包
     * TCP Tahoe: 超时后 cwnd=1，ssthresh减半
     */
    private synchronized void onTimeout() {
        System.out.println("\n!!! TCP超时 - Tahoe协议 !!!");
        
        // 步骤1：拥塞控制状态重置
        ssthresh = Math.max(2, (int)cwnd / 2);  // ssthresh = max(2, cwnd/2)
        cwnd = 1.0;                              // cwnd 重置为 1
        dupAckCount = 0;                         // 重置重复ACK计数
        
        System.out.println("TCP超时重置 - cwnd=" + cwnd + ", ssthresh=" + ssthresh);
        
        // 步骤2：老师要求 - 只重传窗口首个包（不是所有包）
        if (!window.isEmpty()) {
            WindowElem elem = window.peekFirst();
            if (elem != null && elem.getPacket() != null) {
                TCP_PACKET packet = elem.getPacket();
                sender.udt_send(packet);
                System.out.println("TCP重传队首 - seq=" + packet.getTcpH().getTh_seq());
            }
        }
        
        // 步骤3：重启定时器（只在窗口非空时）
        if (!window.isEmpty()) {
            startTimer();
        } else {
            stopTimer();
        }
    }
    /**
     * 处理 ACK - TCP Tahoe核心逻辑
     * 老师要求2：累积确认感知 + 拥塞控制
     */
    public synchronized void ackPacket(int ackSeq) {
        System.out.println("\n=== TCP处理ACK - seq=" + ackSeq + " (window.size=" + window.size() + ", cwnd=" + String.format("%.2f", cwnd) + ") ===");
        
        // 步骤1：清理并计数 - 移除所有 seq <= ackSeq 的包
        // 关键：计数本次ACK实际移除了多少个包
        int ackedCount = 0;
        Iterator<WindowElem> iter = window.iterator();
        while (iter.hasNext()) {
            WindowElem elem = iter.next();
            TCP_PACKET packet = elem.getPacket();
            if (packet != null) {
                int seq = packet.getTcpH().getTh_seq();
                if (seq <= ackSeq) {
                    // 老师要求2：显式 remove 防止内存泄漏
                    iter.remove();
                    ackedCount++;
                    System.out.println("  移除已确认包 - seq=" + seq);
                }
            }
        }
        
        // 判断是否是重复ACK
        if (ackedCount == 0) {
            // ===== 情况1：重复ACK - 快重传逻辑 =====
            if (ackSeq == lastAckSeq) {
                dupAckCount++;
                System.out.println("  TCP重复ACK - 计数=" + dupAckCount + "/3");
                
                // 收到 3 个重复ACK，触发快重传
                if (dupAckCount == 3) {
                    System.out.println("\n!!! 快重传触发 !!!");
                    
                    // 拥塞控制：Tahoe特性 - ssthresh减半，cwnd重置为1
                    ssthresh = Math.max(2, (int)cwnd / 2);
                    cwnd = 1.0;  // Tahoe: 快重传后cwnd重置为1，进入慢开始
                    dupAckCount = 0;
                    
                    System.out.println("快重传重置 - cwnd=" + String.format("%.2f", cwnd) + ", ssthresh=" + ssthresh);
                    
                    // 重传队首包
                    if (!window.isEmpty()) {
                        WindowElem elem = window.peekFirst();
                        if (elem != null && elem.getPacket() != null) {
                            sender.udt_send(elem.getPacket());
                            System.out.println("快重传 - seq=" + elem.getPacket().getTcpH().getTh_seq());
                        }
                    }
                }
            }
        } else {
            // ===== 情况2：新ACK - 拥塞控制窗口增长 =====
            System.out.println("  TCP新ACK - 确认了 " + ackedCount + " 个包");
            
            dupAckCount = 0;  // 重置重复ACK计数
            lastAckSeq = ackSeq;
            
            // 步骤2：拥塞控制状态机 - 关键修复：累积确认感知
            if (cwnd < ssthresh) {
                // 慢开始阶段：指数增长
                // 老师要求2：一个ACK确认N个包，cwnd 应该增加 N
                cwnd += ackedCount;
                System.out.println("  慢开始 - cwnd += " + ackedCount + " => cwnd=" + String.format("%.2f", cwnd));
            } else {
                // 拥塞避免阶段：线性增长
                // 老师要求4：每收到ACK增加 ackedCount * (1/cwnd)
                double increment = ackedCount * (1.0 / cwnd);
                cwnd += increment;
                System.out.println("  拥塞避免 - cwnd += " + String.format("%.4f", increment) + " => cwnd=" + String.format("%.2f", cwnd));
            }
            
            // 限制最大cwnd
            if (cwnd > MAX_CWND) {
                cwnd = MAX_CWND;
            }
            
            // 窗口滑动后定时器管理
            if (window.isEmpty()) {
                stopTimer();
                System.out.println("  窗口已空 - 停止定时器");
            } else {
                startTimer();
            }
        }
        
        System.out.println("=== ACK处理完成 - window.size=" + window.size() + ", cwnd=" + String.format("%.2f", cwnd) + ", ssthresh=" + ssthresh + " ===\n");
    }
    
    /**
     * 获取下一个序列号
     */
    public int getNextSeq() {
        return nextSeqNum++;
    }
    
    /**
     * 填充窗口 - 在cwnd允许的范围内发送数据
     */
    public void fillWindow() {
        // 由 TCP_Sender 的 rdt_send 调用，此处只提供接口
    }
}
