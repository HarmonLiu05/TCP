package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.message.TCP_PACKET;

/**
 * 发送方窗口元素
 * 继承自 WindowElem，增加独立计时器
 * 参考实验报告 P10
 */
public class SenderElem extends WindowElem {
    // 独立计时器：SR协议核心特性，每个包有自己的超时重传定时器
    private UDT_Timer timer;
    
    /**
     * 构造函数
     */
    public SenderElem() {
        super();
        this.timer = null;
    }
    
    /**
     * 启动定时器
     * 参考实验报告 P10 - scheduleTask 方法
     * @param client 客户端对象
     * @param packet 要发送的数据包
     * @param timeout 超时时间（毫秒）
     */
    public void scheduleTask(Client client, TCP_PACKET packet, long timeout) {
        // 如果已经有定时器在运行，先停止
        if (timer != null) {
            timer.cancel();
        }
        
        // 创建新的定时器
        timer = new UDT_Timer();
        // 启动周期性重传任务
        timer.schedule(new UDT_RetransTask(client, packet), timeout, timeout);
    }
    
    /**
     * 确认数据包
     * 参考实验报告 P10 - ackPacket 方法
     * 收到 ACK 后调用此方法，标记为已确认并停止计时器
     */
    public void ackPacket() {
        // 更新标记为已确认
        this.flag = ACKED;
        
        // 停止并清理定时器
        if (timer != null) {
            timer.cancel();
            timer.purge();  // 清除已取消的任务
            timer = null;
        }
    }
    
    /**
     * 重置元素
     * 覆盖父类方法，同时清理定时器
     */
    @Override
    public void reset() {
        super.reset();
        if (timer != null) {
            timer.cancel();
            timer.purge();  // 清除已取消的任务
            timer = null;
        }
    }
    
    /**
     * 获取定时器（用于调试）
     */
    public UDT_Timer getTimer() {
        return timer;
    }
}
