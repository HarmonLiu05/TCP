/***************************TCP Tahoe: 完整拥塞控制协议
**************************** 慢开始 + 拥塞避免 + 快重传 + 超时重传 */

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;

public class TCP_Sender extends TCP_Sender_ADT {
	
	// TCP Tahoe发送窗口：动态链表 + 拥塞控制
	private SenderWindow senderWindow;
	
	/*构造函数*/
	public TCP_Sender() {
		super();
		super.initTCP_Sender(this);
		// TCP Tahoe初始化：不需要预定义WINDOW_SIZE
		senderWindow = new SenderWindow(client, this);
		System.out.println("TCP Tahoe发送端启动 - 拥塞控制开启");
	}
	
	@Override
	// TCP Tahoe发送方法
	public void rdt_send(int dataIndex, int[] appData) {
		
		// TCP Tahoe流控：窗口满时自旋等待
		while (senderWindow.isFull()) {
			// 窗口满时，处理ACK来释放空间
			waitACK();
			Thread.yield();  // 让出CPU，让ACK处理线程有机会运行
		}
		
		// 获取下一个序列号
		int currentSeq = senderWindow.getNextSeq();
		
		// 封装TCP数据包
		tcpH.setTh_seq(currentSeq);
		tcpS.setData(appData);
		TCP_PACKET tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);
		
		// 计算校验和
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		
		// 关键：必须 clone，避免引用被后续修改
		TCP_PACKET clonedPack = null;
		try {
			clonedPack = tcpPack.clone();
			senderWindow.pushPacket(clonedPack);
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		}
		
		// 调用 sendPacket 执行发送，传入刚刚加入的包
		senderWindow.sendPacket(clonedPack, this);
		
		// TCP Tahoe关键：每次发送后都处理待处理的ACK
		waitACK();
	}
	
	@Override
	// 通过不可靠信道发送数据包
	public void udt_send(TCP_PACKET stcpPack) {
		// TCP Tahoe测试配置：
		// eflag=0 无差错（快速测试）- 推荐用于性能测试
		// eflag=4 出错/丢包（中等测试）
		// eflag=7 出错/丢包/延迟（完整测试，会很慢）
		tcpH.setTh_eflag((byte)7);  // 完整测试配置
		client.send(stcpPack);
	}
	
	@Override
	// TCP Tahoe ACK处理
	public void waitACK() {
		// 一次性处理所有堆积的ACK，避免延迟
		while (!ackQueue.isEmpty()) {
			int ackSeq = ackQueue.poll();
			// 调用窗口的 ackPacket 方法
			senderWindow.ackPacket(ackSeq);
		}
	}

	@Override
	// 接收ACK报文：底层框架收到ACK后会回调此方法
	public void recv(TCP_PACKET recvPack) {
		int ackSeq = recvPack.getTcpH().getTh_ack();
		
		System.out.println(">>> 收到ACK - seq=" + ackSeq + " <<<");
		
		// TCP Tahoe关键：直接处理ACK，确保定时器被取消
		// 不能只放入队列，因为rdt_send结束后没人调用waitACK
		senderWindow.ackPacket(ackSeq);
	}
	
}
