/***************************TCP: 基础TCP协议（无拥塞控制）*****************/
/***** GBN发送端 + SR接收端（带缓存+累积确认+Delayed ACK） ******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;
import java.util.Timer;
import java.util.TimerTask;
import java.net.InetAddress;

public class TCP_Receiver extends TCP_Receiver_ADT {
	
	private TCP_PACKET ackPack;	// ACK报文
	// TCP协议：SR的缓存能力 + GBN的累积确认
	private ReceiverWindow receiverWindow;  // 接收窗口（缓存乱序包）
	private static final int WINDOW_SIZE = 10;
	
	// Delayed ACK机制
	private Timer delayedAckTimer;  // 延迟ACK定时器
	private static final long DELAYED_ACK_TIMEOUT = 500;  // 500ms延迟
	private int pendingAckSeq = -1;  // 待发送的ACK序号
	
	/*构造函数*/
	public TCP_Receiver() {
		super();
		super.initTCP_Receiver(this);
		// TCP协议初始化：创建接收窗口（SR的缓存能力）
		receiverWindow = new ReceiverWindow(WINDOW_SIZE, 0);
		delayedAckTimer = null;
		System.out.println("TCP协议接收端启动 - 窗口大小=" + WINDOW_SIZE + ", 支持Delayed ACK");
	}

	@Override
	// TCP协议接收方法：SR缓存 + GBN累积确认 + Delayed ACK
	//当网络上有一个数据包到达接收端电脑。
	//底层框架捕获这个包。
	//框架自动调用你写的 rdt_recv(TCP_PACKET recvPack) 方法。
	public void rdt_recv(TCP_PACKET recvPack) {
		// 步骤1：校验数据完整性
		if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
			// 数据没有损坏
			int seq = recvPack.getTcpH().getTh_seq();
			
			// TCP协议核心：使用SR的缓存机制
			int result = receiverWindow.bufferPacket(recvPack);
			
			if (result == ReceiverWindow.IS_BASE) {
				// ===== 情况1：收到期望的包（按序到达）=====
				System.out.println("TCP接收 - seq=" + seq + " 是期望的包（按序）");
				
				// 交付该包及 循环交付所有连续缓存的包
				TCP_PACKET deliverablePacket;
				while ((deliverablePacket = receiverWindow.getPacketToDeliver()) != null) {
					dataQueue.add(deliverablePacket.getTcpS().getData());
				}
				
				// TCP Delayed ACK：按序包启动延迟ACK（500ms后发送）
				// 这是为了减少网络上的 ACK 包数量 以及 重复确认
				//receiverWindow.getBase() 维护的是下一个期望接收的序号。也就是最左边的未接收到的包
				int ackSeq = receiverWindow.getBase() - 1;  // 累积确认：base-1
				scheduleDelayedAck(ackSeq, recvPack.getSourceAddr());
				System.out.println("TCP Delayed ACK - 将在500ms后回复ACK=" + ackSeq);
				
			} else if (result == ReceiverWindow.ORDERED) {
				// ===== 情况2：乱序包（窗口内）- 缓存并立即发送因为乱序意味着可能丢包了，必须赶紧告诉发送方（重复 ACK），催它快重传
				System.out.println("TCP接收 - seq=" + seq + " 是乱序包（已缓存）");
				// 取消延迟ACK，立即发送重复ACK
				cancelDelayedAck();
				//Duplicate--重复
				// 立即回复Duplicate ACK（累积确认：base-1）
				int dupAckSeq = receiverWindow.getBase() - 1;
				if (dupAckSeq >= 0) {
					sendAck(dupAckSeq, recvPack.getSourceAddr());
					System.out.println("TCP立即发送Duplicate ACK=" + dupAckSeq + " (乱序触发)");
				}
				
			} else if (result == ReceiverWindow.DUPLICATE) {
				// ===== 情况3：重复包- 立即重发ACK =====
				System.out.println("TCP接收 - seq=" + seq + " 是重复包（已交付）");
				
				// 立即重发ACK
				int dupAckSeq = receiverWindow.getBase() - 1;
				if (dupAckSeq >= 0) {
					sendAck(dupAckSeq, recvPack.getSourceAddr());
					System.out.println("TCP重发ACK=" + dupAckSeq + " (重复包触发)");
				}
				
			} else if (result == ReceiverWindow.UNORDERED) {
				// ===== 情况4：窗口外的包 - 丢弃 =====
				System.out.println("TCP丢弃 - seq=" + seq + " 超出窗口范围");
			}
			
		} else {
			// 数据损坏：直接丢弃，不回复ACK
			// 发送方的超时定时器会触发重传
			System.out.println("校验失败 - seq=" + recvPack.getTcpH().getTh_seq());
			System.out.println("计算值=" + CheckSum.computeChkSum(recvPack) + ", 接收值=" + recvPack.getTcpH().getTh_sum());
		}
		
		System.out.println();
		
		// 定期将累积的数据写入文件
		if (dataQueue.size() >= 20) {
			deliver_data();
		}
	}
	
	/**
	 * TCP Delayed ACK机制：延迟500ms发送ACK
	 * 如果期间有反向数据可捎带，则取消此定时器
	 */
	private void scheduleDelayedAck(final int ackSeq, final InetAddress destAddr) {
		// 取消之前的延迟ACK
		cancelDelayedAck();
		
		// 启动新的延迟ACK定时器
		pendingAckSeq = ackSeq;
		delayedAckTimer = new Timer();
		delayedAckTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				// 500ms后发送ACK
				sendAck(ackSeq, destAddr);
				System.out.println("TCP Delayed ACK触发 - 发送ACK=" + ackSeq);
				pendingAckSeq = -1;
			}
		}, DELAYED_ACK_TIMEOUT);
	}
	
	/**
	 * 取消延迟ACK定时器
	 */
	private void cancelDelayedAck() {
		if (delayedAckTimer != null) {
			delayedAckTimer.cancel();
			delayedAckTimer.purge();
			delayedAckTimer = null;
		}
	}
	
	/**
	 * 发送ACK（累积确认）
	 */
	private void sendAck(int ackSeq, InetAddress destAddr) {
		tcpH.setTh_ack(ackSeq);
		ackPack = new TCP_PACKET(tcpH, tcpS, destAddr);
		tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
		tcpH.setTh_eflag((byte)7);  // ACK包得模拟错误
		reply(ackPack);
	}

	@Override
	//交付数据（将数据写入文件）；不需要修改
	public void deliver_data() {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;
		
		try {
			writer = new BufferedWriter(new FileWriter(fw, true));
			
			//循环检查data队列中是否有新交付数据
			while(!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();
				
				//将数据写入文件
				for(int i = 0; i < data.length; i++) {
					writer.write(data[i] + "\n");
				}
				
				writer.flush();		//清空输出缓存
			}
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	//回复ACK报文段
	public void reply(TCP_PACKET replyPack) {
		//直接发送数据报，保留之前设置的eflag值
		client.send(replyPack);
	}
	
}
