package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.message.TCP_PACKET;

/**
 * 发送方窗口管理类 - GBN协议
 * 使用数组实现的循环队列管理发送缓冲区
 * GBN关键：单一定时器 + 累积确认 + 超时重传所有
 */
public class SenderWindow {
    // 窗口数组：使用数组实现循环队列
    private WindowElem[] window;  // GBN不需要每个元素都有定时器，使用基类WindowElem
    // 窗口大小
    private int size;
    // 窗口基序号：最早未确认的包的序列号
    private int base;
    // 下一个待发送的包的序列号
    private int nextToSend;
    // 队尾指针：指向下一个可用位置
    private int rear;
    // 超时时间（毫秒）
    private static final long TIMEOUT_MS = 3000;
    // 客户端对象
    private Client client;
    // GBN关键：单一全局定时器（只为base计时）
    private UDT_Timer baseTimer;
    // 发送方引用（用于超时重传）
    private TCP_Sender sender;
    
    /**
     * 构造函数
     * @param size 窗口大小
     * @param client 客户端对象
     * @param sender 发送方对象
     */
    public SenderWindow(int size, Client client, TCP_Sender sender) {
        this.size = size;
        this.window = new WindowElem[size];  // GBN使用WindowElem，不需要每个包的定时器
        this.base = 0;
        this.nextToSend = 0;
        this.rear = 0;
        this.client = client;
        this.sender = sender;
        this.baseTimer = null;  // GBN单一定时器
        
        // 初始化窗口数组
        for (int i = 0; i < size; i++) {
            window[i] = new WindowElem();  // 使用基类WindowElem
        }
    }
    
    /**
     * 获取索引：序列号转换为数组索引
     * @param seq 序列号
     * @return 数组索引
     */
    private int getIdx(int seq) {
        return seq % size;
    }
    
    /**
     * 检查窗口是否已满
     * @return 窗口是否已满
     */
    public boolean isFull() {
        return (rear - base) >= size;
    }
    
    /**
     * 将新包加入窗口
     * @param packet 要加入的数据包
     */
    public void pushPacket(TCP_PACKET packet) {
        int idx = getIdx(rear);
        window[idx].setPacket(packet);
        window[idx].setFlag(WindowElem.NOT_ACKED);
        rear++;
        
        System.out.println("GBN入窗 - seq=" + packet.getTcpH().getTh_seq() + 
                         " (rear=" + rear + ")");
    }
    
    /**
     * 发送数据包并启动/重启定时器
     * GBN关键：只有一个全局定时器，用于最早的未确认包
     * @param sender 发送方对象（用于访问 udt_send）
     */
    public void sendPacket(TCP_Sender sender) {
        if (nextToSend < rear) {
            int idx = getIdx(nextToSend);
            TCP_PACKET packet = window[idx].getPacket();
            
            // 发送数据包
            sender.udt_send(packet);
            
            System.out.println("GBN发送 - seq=" + packet.getTcpH().getTh_seq() + 
                             " (base=" + base + ", nextToSend=" + nextToSend + ", rear=" + rear + ")");
            
            // 移动 nextToSend 指针
            nextToSend++;
            
            // GBN关键：定时器管理
            // 如果是窗口从空变为非空（即base位置的包被发送），启动定时器
            if (base == nextToSend - 1) {  // 刚才发送的是base位置的包
                startTimer();
            }
        }
    }
    
    /**
     * 启动全局定时器
     * GBN关键：只有一个定时器，用于base位置
     */
    private void startTimer() {
        // 先停止旧的定时器
        stopTimer();
        
        // 创建新的定时器，超时时重传所有未确认的包
        baseTimer = new UDT_Timer();
        baseTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                onTimeout();
            }
        }, TIMEOUT_MS);
        
        System.out.println("GBN启动定时器 - base=" + base);
    }
    
    /**
     * 停止全局定时器
     */
    private void stopTimer() {
        if (baseTimer != null) {
            baseTimer.cancel();
            baseTimer.purge();
            baseTimer = null;
            System.out.println("GBN停止定时器");
        }
    }
    
    /**
     * 超时处理：GBN关键 - 重传所有未确认的包
     */
    private void onTimeout() {
        System.out.println("\n!!! GBN超时 - 重传窗口内所有包 [" + base + "," + nextToSend + ") !!!");
        
        // 重传所有已发送但未确认的包
        for (int i = base; i < nextToSend; i++) {
            int idx = getIdx(i);
            TCP_PACKET packet = window[idx].getPacket();
            if (packet != null) {
                sender.udt_send(packet);
                System.out.println("GBN重传 - seq=" + packet.getTcpH().getTh_seq());
            }
        }
        
        // 重启定时器
        startTimer();
    }
    /**
     * 处理 ACK - GBN关键：累积确认
     * 收到ACK n，表示 n 及之前的所有包都已确认
     * @param seq 收到的 ACK 序列号
     */
    public void ackPacket(int seq) {
        System.out.println("\n=== GBN处理ACK - seq=" + seq + " (base=" + base + ", rear=" + rear + ") ===");
        
        // GBN累积确认：ACK n 表示 n 及之前的所有包都已确认
        if (seq >= base && seq < rear) {
            // 标记所有 <= seq 的包为已确认
            for (int i = base; i <= seq && i < rear; i++) {
                int idx = getIdx(i);
                if (!window[idx].isAcked()) {
                    window[idx].setFlag(WindowElem.ACKED);
                    System.out.println("  >>> GBN确认 - seq=" + i + " (idx=" + idx + ") <<<");
                }
            }
            
            // 滑动窗口
            slideWindow();
            
        } else if (seq < base) {
            System.out.println("  GBN重复ACK - seq=" + seq + " < base=" + base + " (已确认过)");
        } else {
            System.out.println("  GBN警告 - seq=" + seq + " 不在窗口内 [" + base + "," + rear + ")");
        }
        
        System.out.println("=== ACK处理完成 ===\n");
    }
    
    /**
     * 滑动窗口 - GBN关键：累积滑动
     * 从 base 开始，连续滑动所有已确认的包
     */
    private void slideWindow() {
        int slideCount = 0;
        int oldBase = base;
        
        // 循环检查 base 指向的包是否已确认
        while (base < rear) {
            int idx = getIdx(base);
            
            if (window[idx].isAcked()) {
                // 重置该元素
                window[idx].reset();
                // 滑动 base
                base++;
                slideCount++;
            } else {
                // base 指向的包未确认，停止滑动
                break;
            }
        }
        
        if (slideCount > 0) {
            System.out.println("GBN窗口滑动 - 从 base=" + oldBase + " 滑动到 " + base + 
                             " (滑动" + slideCount + "个包)");
            
            // GBN关键：窗口滑动后的定时器管理
            if (base == rear) {
                // 窗口已空，停止定时器
                stopTimer();
                System.out.println("GBN窗口已空 - 停止定时器");
            } else {
                // 窗口仍有未确认的包，重启定时器
                startTimer();
            }
        }
    }
    
    /**
     * 获取下一个序列号
     * @return 下一个可用的序列号
     */
    public int getNextSeq() {
        return rear;
    }
}
