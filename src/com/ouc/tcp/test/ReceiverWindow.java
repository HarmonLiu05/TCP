package com.ouc.tcp.test;

import com.ouc.tcp.message.TCP_PACKET;

/**
 * 接收方窗口管理类 - TCP协议
 * 使用数组实现的循环队列管理接收缓冲区
 * TCP协议：SR的缓存能力 + GBN的累积确认
 */
public class ReceiverWindow {
    // 窗口数组：使用数组实现循环队列
    private ReceiverElem[] window;
    // 窗口大小
    private int size;
    // 窗口基序号：期望接收的下一个包的序列号
    private int base;
    
    // bufferPacket 返回值常量
    public static final int UNORDERED = -1;  // 乱序太远，窗口外的包
    public static final int DUPLICATE = 0;   // 重复包（base之前）
    public static final int IS_BASE = 1;     // 恰好是base位置的包
    public static final int ORDERED = 2;     // 窗口内的乱序包
    
    /**
     * 构造函数
     * @param size 窗口大小
     * @param startSeq 起始序列号
     */
    public ReceiverWindow(int size, int startSeq) {
        this.size = size;
        this.window = new ReceiverElem[size];
        this.base = startSeq;
        
        // 初始化窗口数组
        for (int i = 0; i < size; i++) {
            window[i] = new ReceiverElem();
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
     * 缓存数据包 - TCP协议
     * @param packet 收到的数据包
     * @return 状态码：UNORDERED/DUPLICATE/IS_BASE/ORDERED
     */
    public int bufferPacket(TCP_PACKET packet) {
        int seq = packet.getTcpH().getTh_seq();
        
        // 情况1：窗口之前的包（重复包）
        if (seq < base) {
            System.out.println("TCP接收窗口 - seq=" + seq + " 是重复包（已交付），仍需回复ACK");
            return DUPLICATE;
        }
        
        // 情况2：窗口之外的包（乱序太远）
        if (seq >= base + size) {
            System.out.println("TCP接收窗口 - seq=" + seq + " 超出窗口范围 [" + base + "," + (base+size-1) + "]");
            return UNORDERED;
        }
        
        // 情况3：窗口内的包
        int idx = getIdx(seq);
        
        // 检查是否已经缓存过（重复接收）
        if (window[idx].isBuffered() && window[idx].getPacket() != null) {
            int cachedSeq = window[idx].getPacket().getTcpH().getTh_seq();
            if (cachedSeq == seq) {
                System.out.println("TCP接收窗口 - seq=" + seq + " 重复接收（已在缓冲区），仍需回复ACK");
                return (seq == base) ? IS_BASE : ORDERED;
            }
        }
        
        // 缓存该包
        window[idx].setPacket(packet);
        window[idx].markBuffered();
        
        if (seq == base) {
            System.out.println("TCP接收窗口 - seq=" + seq + " 是期望的包（base）");
            return IS_BASE;
        } else {
            System.out.println("TCP接收窗口 - seq=" + seq + " 是乱序包（已缓存）");
            return ORDERED;
        }
    }
    
    /**
     * 获取可交付的数据包 - TCP协议
     * @return 可交付的数据包，如果没有则返回 null
     */
    public TCP_PACKET getPacketToDeliver() {
        int idx = getIdx(base);
        
        // 检查 base 位置是否已缓存
        if (window[idx].isBuffered()) {
            TCP_PACKET packet = window[idx].getPacket();
            
            // 重置该元素
            window[idx].reset();
            
            // 滑动窗口
            base++;
            
            System.out.println("TCP交付 - seq=" + packet.getTcpH().getTh_seq() + 
                             " (新base=" + base + ")");
            
            return packet;
        }
        
        return null;
    }
    
    /**
     * 获取当前 base 值（用于调试）
     */
    public int getBase() {
        return base;
    }
}
