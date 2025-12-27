package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.message.TCP_PACKET;

/**
 * 发送方窗口管理类
 * 使用数组实现的循环队列管理发送缓冲区
 * 参考实验报告 P10-12
 */
public class SenderWindow {
    // 窗口数组：使用数组实现循环队列
    private SenderElem[] window;
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
    
    /**
     * 构造函数
     * @param size 窗口大小
     * @param client 客户端对象
     */
    public SenderWindow(int size, Client client) {
        this.size = size;
        this.window = new SenderElem[size];
        this.base = 0;
        this.nextToSend = 0;
        this.rear = 0;
        this.client = client;
        
        // 初始化窗口数组
        for (int i = 0; i < size; i++) {
            window[i] = new SenderElem();
        }
    }
    
    /**
     * 获取索引：序列号转换为数组索引
     * 参考实验报告 P10 - getIdx 方法
     * @param seq 序列号
     * @return 数组索引
     */
    private int getIdx(int seq) {
        return seq % size;
    }
    
    /**
     * 检查窗口是否已满
     * 参考实验报告 P11 - isFull 方法
     * @return 窗口是否已满
     */
    public boolean isFull() {
        return (rear - base) >= size;
    }
    
    /**
     * 将新包加入窗口
     * 参考实验报告 P11 - pushPacket 方法
     * @param packet 要加入的数据包
     */
    public void pushPacket(TCP_PACKET packet) {
        int idx = getIdx(rear);
        window[idx].setPacket(packet);
        window[idx].setFlag(WindowElem.NOT_ACKED);
        rear++;
        
        System.out.println("SR入窗 - seq=" + packet.getTcpH().getTh_seq() + 
                         " (rear=" + rear + ")");
    }
    
    /**
     * 发送数据包并启动定时器
     * 参考实验报告 P11 - sendPacket 方法
     * 非阻塞发送：只发送 nextToSend 指向的包
     * @param sender 发送方对象（用于访问 udt_send）
     */
    public void sendPacket(TCP_Sender sender) {
        if (nextToSend < rear) {
            int idx = getIdx(nextToSend);
            TCP_PACKET packet = window[idx].getPacket();
            
            // 发送数据包
            sender.udt_send(packet);
            
            // 启动该包的独立定时器
            window[idx].scheduleTask(client, packet, TIMEOUT_MS);
            
            System.out.println("SR发送 - seq=" + packet.getTcpH().getTh_seq() + 
                             " (base=" + base + ", nextToSend=" + nextToSend + ", rear=" + rear + ")");
            
            // 移动 nextToSend 指针
            nextToSend++;
        }
    }
    
    /**
     * 处理 ACK
     * 参考实验报告 P11-12 - ackPacket 方法
     * @param seq 收到的 ACK 序列号
     */
    public void ackPacket(int seq) {
        System.out.println("\n=== SR处理ACK - seq=" + seq + " (base=" + base + ", rear=" + rear + ") ===");
        
        // 遍历窗口，查找对应的包
        boolean found = false;
        for (int i = base; i < rear; i++) {
            int idx = getIdx(i);
            TCP_PACKET packet = window[idx].getPacket();
            
            if (packet != null) {
                int packetSeq = packet.getTcpH().getTh_seq();
                System.out.println("  检查 i=" + i + ", idx=" + idx + ", packetSeq=" + packetSeq + 
                                 ", isAcked=" + window[idx].isAcked());
                
                if (packetSeq == seq) {
                    found = true;
                    // 如枟该包还未确认，则确认它
                    if (!window[idx].isAcked()) {
                        window[idx].ackPacket();
                        System.out.println("  >>> SR确认 - seq=" + seq + " (i=" + i + ", idx=" + idx + ") <<<");
                    } else {
                        System.out.println("  SR重复ACK - seq=" + seq + " 已经确认过");
                    }
                    break;
                }
            }
        }
        
        if (!found) {
            System.out.println("  SR警告 - seq=" + seq + " 不在窗口内 [" + base + "," + rear + ")");
        }
        
        // 尝试滑动窗口
        slideWindow();
        System.out.println("=== ACK处理完成 ===\n");
    }
    
    /**
     * 滑动窗口
     * 参考实验报告 P12
     * 只有当 base 指向的包已确认时，才能滑动窗口
     */
    private void slideWindow() {
        // 循环检查 base 指向的包是否已确认
        int slideCount = 0;
        while (base < rear) {
            int idx = getIdx(base);
            System.out.println("SR检查滑动 - base=" + base + ", idx=" + idx + 
                             ", isAcked=" + window[idx].isAcked() + 
                             ", packet=" + (window[idx].getPacket() == null ? "null" : 
                                           "seq=" + window[idx].getPacket().getTcpH().getTh_seq()));
            
            if (window[idx].isAcked()) {
                // 重置该元素
                window[idx].reset();
                // 滑动 base
                base++;
                slideCount++;
                System.out.println("SR窗口滑动 - 新base=" + base);
            } else {
                // base 指向的包未确认，停止滑动
                System.out.println("SR停止滑动 - base=" + base + " 未确认");
                break;
            }
        }
        
        if (slideCount > 0) {
            System.out.println("SR滑动完成 - 滑动了 " + slideCount + " 个包，新base=" + base + ", rear=" + rear);
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
