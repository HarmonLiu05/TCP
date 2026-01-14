package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;
import java.util.LinkedList;
import java.util.Iterator;

/**
 * 发送方窗口管理类 - TCP Reno协议
 * 使用LinkedList动态数据结构（老师要求：禁止静态数组）
 * 实现完整的拥塞控制：慢开始、拥塞避免、快重传、快恢复、超时重传
 */
public class SenderWindow {
    // ===== 使用动态数据结构，禁止静态数组 =====
    private LinkedList<WindowElem> window;  // 使用双向链表，避免内存泄漏
    
    // ===== 拥塞控制变量 =====
    private double cwnd;           // 拥塞窗口（浮点数，支持精确增长）
    private int ssthresh;          // 慢开始阈值
    private static final int MAX_CWND = 64;  // 最大拥塞窗口
    private long startTime;        // 记录启动时间，用于日志
    
    // ===== 快重传相关 =====
    private int dupAckCount;       // 重复ACK计数
    private int lastAckSeq;        // 上一次收到的ACK序号
    
    // ===== TCP Reno特有：快恢复状态 =====
    private boolean isFastRecovery;  // 是否处于快恢复状态
    private int fastRecoveryEndSeq;  // 进入快恢复时，链表末尾包的序号
    
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
        // 初始化链表
        this.window = new LinkedList<>();
        
        // 初始化拥塞控制参数
        // 慢开始阶段：初始 cwnd 为 1
        this.cwnd = 1.0;           // 慢开始：初始窗口为 1
        this.ssthresh = 32;        // 阈值设为 32
        
        // 初始化快重传
        this.dupAckCount = 0;
        this.lastAckSeq = -1;
        this.isFastRecovery = false;  // 初始不在快恢复状态
        this.fastRecoveryEndSeq = -1; // 初始化快恢复结束序号
        
        // 初始化序列号
        this.nextSeqNum = 0;
        
        // 初始化定时器
        this.client = client;
        this.sender = sender;
        this.baseTimer = null;
        
        // 记录启动时间
        this.startTime = System.currentTimeMillis();
        
        System.out.println("TCP Reno启动 - cwnd=" + cwnd + ", ssthresh=" + ssthresh);
        logCwndChange();  // 记录初始状态
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
        WindowElem elem = new WindowElem(); // 创建一个新节点
        elem.setPacket(packet); // 把数据包塞进节点里
        elem.setFlag(WindowElem.NOT_ACKED);
        window.addLast(elem);// 【关键】把节点挂到链表末尾
        
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
     * TCP Reno: 超时后 cwnd=1，ssthresh减半，退出快恢复状态
     */
    private synchronized void onTimeout() {
        System.out.println("\n!!! TCP超时 - Reno协议 !!!");
        
        // 步骤1：拥塞控制状态重置
        ssthresh = Math.max(2, (int)cwnd / 2);  // ssthresh = max(2, cwnd/2)
        cwnd = 1.0;                              // cwnd 重置为 1
        dupAckCount = 0;                         // 重置重复ACK计数
        isFastRecovery = false;                  // 退出快恢复状态
        fastRecoveryEndSeq = -1;                 // 清除快恢复结束序号
        
        System.out.println("TCP超时重置 - cwnd=" + cwnd + ", ssthresh=" + ssthresh + ", 退出快恢复");
        logCwndChange();  // 记录cwnd变化
        
        // 步骤2：只重传窗口首个包（不是所有包）
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
     * 处理 ACK - TCP Reno核心逻辑
     * 老师要求2：累积确认感知 + 拥塞控制 + 快恢复
     */
    public synchronized void ackPacket(int ackSeq) {
        System.out.println("\n=== TCP处理ACK - seq=" + ackSeq + " (window.size=" + window.size() + ", cwnd=" + String.format("%.2f", cwnd) + ", FastRecovery=" + isFastRecovery + ") ===");
        
        // 步骤1：清理并计数 - 移除所有 seq <= ackSeq 的包 这里其实就是窗口滑动 因为你删除了这些已确认的节点
        //是的window的size变小了
        // 关键：计数本次ACK实际移除了多少个包
        //TCP 是累积确认。如果发了 1, 2, 3，接收方回了 ACK 3，代表 1, 2, 3 全收到了。
        //发送窗口必须把 1, 2, 3 全删掉，腾出空间发 4, 5, 6。
        int ackedCount = 0;
        Iterator<WindowElem> iter = window.iterator();
        while (iter.hasNext()) {
            WindowElem elem = iter.next();
            TCP_PACKET packet = elem.getPacket();
            if (packet != null) {
                int seq = packet.getTcpH().getTh_seq();
                // 【关键判断】：如果包的序号 <= 收到的ACK序号
                if (seq <= ackSeq) {
                    // 【动作】：从链表中删掉这个包
                    iter.remove();
                    ackedCount++;
                    System.out.println("  移除已确认包 - seq=" + seq);
                }
            }
        }
        
        // ========== 步骤2：分支判断 - 新ACK vs 重复ACK ==========
        
        if (ackedCount > 0) {
            // ===== Case A: 新ACK (ackedCount > 0) =====
            // 说明：对方收到了新数据，这是好消息！
            System.out.println("  TCP新ACK - 确认了 " + ackedCount + " 个包");
            
            // 【关键改进：按末尾序号判断是否退出快恢复】
            if (isFastRecovery) {
                // 检查ACK是否超过快恢复启动时的末尾包序号
                if (ackSeq > fastRecoveryEndSeq) {
                    // 收到的ACK已经超过快恢复启动时的末尾包，说明快恢复成功完成
                    System.out.println("  >>> 退出快恢复 (ACK=" + ackSeq + " > EndSeq=" + fastRecoveryEndSeq + ") - cwnd收缩: " + String.format("%.2f", cwnd) + " -> " + ssthresh + " <<<");
                    cwnd = ssthresh;  // 收缩窗口 (Deflate Window)
                    isFastRecovery = false;// 退出快恢复状态
                    fastRecoveryEndSeq = -1; // 清除快恢复结束序号
                    //logCwndChange();
                    
                    // 退出快恢复后，进入拥塞避免阶段（因为cwnd = ssthresh）
                    double increment = ackedCount * (1.0 / cwnd);
                    cwnd += increment;
                    System.out.println("  拥塞避免 - cwnd += " + String.format("%.4f", increment) + " => cwnd=" + String.format("%.2f", cwnd));
                    logCwndChange();
                } else {
                    // 收到新ACK，但还没超过快恢复时的末尾包
                    // 说明这些ACK确认的是快恢复期间发送的新包
                    // 应该让cwnd适度增长（部分窗口膨胀）
                    System.out.println("  >>> 快恢复中收到新ACK (ACK=" + ackSeq + " <= EndSeq=" + fastRecoveryEndSeq + ") - 继续快恢复，窗口膨胀 <<<");
                    cwnd += ackedCount;
                    System.out.println("  快恢复窗口膨胀 - cwnd += " + ackedCount + " => cwnd=" + String.format("%.2f", cwnd));
                    logCwndChange();
                }
            } else {
                // 正常的增长逻辑 慢开始 还是 拥塞避免
                // 【判断】：我是慢开始 还是 拥塞避免？
                if (cwnd < ssthresh) {
                    // === 慢开始阶段 ===
                    // 规则：每收到一个确认一个包，窗口加1。
                    // 效果：1->2->4->8，指数增长。
                    cwnd += ackedCount;
                    if(cwnd > ssthresh)cwnd = ssthresh;
                    System.out.println("  慢开始 - cwnd += " + ackedCount + " => cwnd=" + String.format("%.2f", cwnd));
                    logCwndChange();
                } else {
                    // === 拥塞避免阶段 ===
                    // 规则：cwnd >= ssthresh，增长要慢一点。
                    // 公式：cwnd = cwnd + 1.0
                    // 效果：每个RTT只加1，线性增长。
                    double increment = ackedCount * (1.0 / cwnd);
                    cwnd += increment;
                    System.out.println("  拥塞避免 - cwnd += " + String.format("%.4f", increment) + " => cwnd=" + String.format("%.2f", cwnd));
                    logCwndChange();
                }
            }
            
            // 限制最大cwnd
            if (cwnd > MAX_CWND) {
                cwnd = MAX_CWND;
            }
            
            // 重置重复ACK计数
            dupAckCount = 0;
            lastAckSeq = ackSeq;
            
            // 窗口滑动后定时器管理
            if (window.isEmpty()) {
                stopTimer();
                System.out.println("  窗口已空 - 停止定时器");
            } else {
                startTimer();
            }
            
        } else {
            // ===== Case B: 重复ACK (ackedCount == 0) =====
            //下面这一部分 主要是快重传 快恢复 这一块代码的作用：
            //计数：数是不是到了 3 个重复 ACK。
            //动作：如果到了 3 个，别等超时了，赶紧重传（这就叫快重传）。
            //状态切换：把 cwnd 设置得很大（ssthresh + 3），进入快恢复，保证在重传期间，如果还有ACK来，还能继续发新数据，保持数据流不断。
            if (ackSeq == lastAckSeq) {
                dupAckCount++; // 收到一样的ACK，计数器+1
                System.out.println("  TCP重复ACK - 计数=" + dupAckCount + "/3");
                // 【判断】：我是不是已经进入快恢复状态了？
                if (isFastRecovery) {
                    // 【关键点2：窗口膨胀】已在快恢复中，窗口膨胀
                    // === 情况 1：已经在快恢复中 ===
                    // 说明：我又收到了重复ACK。这意味着接收方又收到了一个乱序包。
                    // 根据 Reno 算法，既然包能到达接收方，网络没断，我可以多发一个包。
                    cwnd += 1.0;
                    System.out.println("  >>> 快恢复窗口膨胀 - cwnd += 1 => cwnd=" + String.format("%.2f", cwnd) + " <<<");
                    logCwndChange();
                    
                    // 尝试发送新数据（如果窗口允许）
                    // 注意：这里需要TCP_Sender配合fillWindow，暂时仅记录
                    System.out.println("  快恢复期间 - 允许发送新数据");
                    
                } else {
                    // 未进入快恢复，检查是否达到快重传条件
                    if (dupAckCount == 3) {
                        // !!! 触发快重传的核心时刻 !!!
                        // 【关键点1：快重传触发点】
                        System.out.println("\n!!! 快重传触发 (Reno) !!!");
                        
                        // 记录当前链表末尾包的序号（作为快恢复结束的判断标准）
                        if (!window.isEmpty()) {
                            WindowElem lastElem = window.peekLast();
                            if (lastElem != null && lastElem.getPacket() != null) {
                                fastRecoveryEndSeq = lastElem.getPacket().getTcpH().getTh_seq();
                                System.out.println("记录快恢复结束序号 - EndSeq=" + fastRecoveryEndSeq);
                            }
                        } else {
                            fastRecoveryEndSeq = ackSeq;
                        }
                        
                        // 拥塞控制：ssthresh减半，cwnd = ssthresh
                        ssthresh = Math.max(2, (int)cwnd / 2);
                        cwnd = ssthresh;
                        isFastRecovery = true;  // 进入快恢复状态
                        
                        System.out.println("快重传重置 - ssthresh=" + ssthresh + ", cwnd=" + String.format("%.2f", cwnd) + ", 进入快恢复");
                        logCwndChange();
                        
                        // 立即重传队首包
                        if (!window.isEmpty()) {
                            WindowElem elem = window.peekFirst();
                            if (elem != null && elem.getPacket() != null) {
                                sender.udt_send(elem.getPacket());
                                System.out.println("快重传 - seq=" + elem.getPacket().getTcpH().getTh_seq());
                            }
                        }
                    }
                }
            }
        }
        
        System.out.println("=== ACK处理完成 - window.size=" + window.size() + ", cwnd=" + String.format("%.2f", cwnd) + ", ssthresh=" + ssthresh + ", FastRecovery=" + isFastRecovery + " ===\n");
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
    
    /**
     * 记录cwnd和ssthresh变化（用于Python绘图）
     */
    private void logCwndChange() {
        long currentTime = System.currentTimeMillis() - startTime;
        System.out.println("CWND_LOG: TIME: " + currentTime + ", CWND: " + String.format("%.2f", cwnd) + ", SSTHRESH: " + ssthresh);
    }
}
