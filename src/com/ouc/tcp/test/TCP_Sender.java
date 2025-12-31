/***************************3.0: 超时重传（处理丢包）
**************************** Modified for RDT 3.0 */

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	private volatile int flag = 0;
	private UDT_Timer timer;	//RDT 3.0: 超时重传定时器
	private int lastAckReceived = 0;  // 记录上一次收到的ACK号
	
	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
	}
	
	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报
	public void rdt_send(int dataIndex, int[] appData) {
		
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号：
		tcpS.setData(appData);
		tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);		
		//更新带有checksum的TCP 报文头		
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		
		//发送TCP数据报
		udt_send(tcpPack);
		flag = 0;
		
		//RDT 3.0: 启动超时重传定时器
		timer = new UDT_Timer();
		timer.schedule(new UDT_RetransTask(client, tcpPack), 3000, 3000);  // 3秒超时
		
		//等待ACK报文
		while (flag==0);
		
		//RDT 3.0: 收到ACK后，停止定时器
		timer.cancel();
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送
	public void udt_send(TCP_PACKET stcpPack) {
		//设置错误控制标志
		// eflag=0: 无差错; eflag=1: 只出错; eflag=2: 只丢包; eflag=3: 只延迟
		// RDT 3.0: 设置为2（只丢包）来测试超时重传
		tcpH.setTh_eflag((byte)2);		
		//System.out.println("to send: "+stcpPack.getTcpH().getTh_seq());				
		//发送数据报
		client.send(stcpPack);
	}
	
	@Override
	//需要修改
	public void waitACK() {
		//循环检查ackQueue
		//循环检查确认号对列中是否有新收到的ACK		
		if(!ackQueue.isEmpty()){
			int currentAck=ackQueue.poll();
			// System.out.println("CurrentAck: "+currentAck);
			if (currentAck == tcpPack.getTcpH().getTh_seq()){
				System.out.println("Clear: "+tcpPack.getTcpH().getTh_seq());
				flag = 1;
				//break;
			}else{
				System.out.println("Retransmit: "+tcpPack.getTcpH().getTh_seq());
				udt_send(tcpPack);
				flag = 0;
			}
		}
	}

	@Override
	//接收到ACK报文：RDT 3.0 先检查ACK校验和，再处理ACK
	public void recv(TCP_PACKET recvPack) {
		int receivedAck = recvPack.getTcpH().getTh_ack();
		int currentSeq = tcpPack.getTcpH().getTh_seq();
		
		System.out.println("RDT3.0 - Receive ACK: " + receivedAck + " (Current seq: " + currentSeq + ")");
		
		//首先检查ACK包本身的校验和
		if(CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) {
			//ACK包损坏，忽略，依赖超时重传
			System.out.println("RDT3.0 - Corrupted ACK detected, ignore and wait for timeout");
			System.out.println();
			return;
		}
		
		// RDT 3.0: ACK包完好，检查收到的ACK
		if (receivedAck == currentSeq) {
			// 情况1：收到正确ACK，确认号与当前发送序号匹配
			System.out.println("RDT3.0 - Correct ACK, Clear: " + currentSeq);
			lastAckReceived = receivedAck;
			
			//RDT 3.0: 停止定时器
			if (timer != null) {
				timer.cancel();
			}
			
			flag = 1;  // 允许发送下一包
		} else if (receivedAck == lastAckReceived && receivedAck != 0) {
			// 情况2：收到重复ACK（可能是之前包的ACK）
			System.out.println("RDT3.0 - Duplicate ACK (ACK=" + receivedAck + "), ignore");
			// 不做处理，等待超时重传
		} else if (receivedAck < currentSeq) {
			// 情况3：收到旧的ACK
			System.out.println("RDT3.0 - Old ACK (ACK=" + receivedAck + " < seq=" + currentSeq + "), ignore");
			lastAckReceived = receivedAck;
			// 不做处理，等待超时重传
		} else {
			// 情况4：其他情况
			System.out.println("RDT3.0 - Unexpected ACK, ignore");
		}
		System.out.println();
	}
	
}
